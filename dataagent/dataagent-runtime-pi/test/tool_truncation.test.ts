import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createTools, runShell } from "../src/tools/tool-registry.js";
import {
  WorkspaceBoundaryEnforcer,
  type BoundaryPolicy,
} from "../src/policy/workspace-boundary-enforcer.js";

const MAX_READ_BYTES = 64 * 1024;
const MAX_OUTPUT_BYTES = 100 * 1024;

function toolsFor(root: string) {
  const policy: BoundaryPolicy = {
    policy_version: 1,
    profile: "pi_agent_core",
    workspace_root: root,
    allowed_roots: [root],
    allowed_executables: [],
    discard_sinks: ["/dev/null"],
    tool_path_keys: { Read: ["file_path"], LS: ["path"] },
    operator_chars: "();<>|&",
    tool_result_root: null,
    readonly_commands: [],
  };
  return createTools({
    boundary: new WorkspaceBoundaryEnforcer(policy),
    workspaceRoot: root,
    runtimeEnv: {},
  });
}

test("Read makes truncation visible and supports byte-offset continuation", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-read-window-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const filePath = "large.txt";
  const tail = "b".repeat(7_000);
  fs.writeFileSync(path.join(root, filePath), "a".repeat(MAX_READ_BYTES) + tail);

  const read = toolsFor(root).find((tool) => tool.name === "Read")!;
  const schema = read.parameters as any;
  assert.equal(schema.properties.offset.default, 0);
  assert.equal(schema.properties.limit.default, MAX_READ_BYTES);
  assert.equal(schema.properties.limit.maximum, MAX_READ_BYTES);

  const first = await read.execute("read-1", { file_path: filePath });
  const firstText = first.content[0].type === "text" ? first.content[0].text : "";
  assert.match(firstText, /returned byte range \[0, 65536\) of 72536 total bytes/);
  assert.match(firstText, /Do not treat this as the complete file/);
  assert.match(firstText, /Read\(file_path="large\.txt", offset=65536\)/);
  assert.deepEqual(first.details, {
    truncated: true,
    bytes: MAX_READ_BYTES,
    offset: 0,
    total_bytes: MAX_READ_BYTES + tail.length,
  });

  const second = await read.execute("read-2", { file_path: filePath, offset: MAX_READ_BYTES });
  const secondText = second.content[0].type === "text" ? second.content[0].text : "";
  assert.equal(secondText, tail, "the continuation must read from the requested byte offset");
  assert.doesNotMatch(secondText, /Read truncated/);
});

test("Read honors an explicit byte limit and gives the exact next offset", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-read-limit-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  fs.writeFileSync(path.join(root, "alphabet.txt"), "abcdefghijklmnopqrstuvwxyz");

  const read = toolsFor(root).find((tool) => tool.name === "Read")!;
  const result = await read.execute("read-limit", {
    file_path: "alphabet.txt",
    offset: 5,
    limit: 10,
  });
  const text = result.content[0].type === "text" ? result.content[0].text : "";

  assert.ok(text.startsWith("fghijklmno"));
  assert.match(text, /returned byte range \[5, 15\) of 26 total bytes/);
  assert.match(text, /Read\(file_path="alphabet\.txt", offset=15\)/);
});

test("Bash reports discarded bytes in model-visible successful output", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-bash-truncate-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const command = "printf '%*s' 120000 '' | tr ' ' x";
  const env = { PATH: process.env.PATH ?? "/usr/bin:/bin" };

  const shellResult = await runShell(command, { cwd: root, env, timeoutMs: 10_000 });
  assert.equal(Buffer.byteLength(shellResult.stdout, "utf8"), MAX_OUTPUT_BYTES);
  assert.equal(shellResult.droppedBytes, 120_000 - MAX_OUTPUT_BYTES);

  const bash = toolsFor(root).find((tool) => tool.name === "Bash")!;
  const result = await bash.execute("bash-truncate", { command });
  const text = result.content[0].type === "text" ? result.content[0].text : "";
  assert.match(text, /17600 bytes were discarded/);
  assert.match(text, /cannot be recovered/);
  assert.match(text, /filters, head, or tail/);
  assert.match(text, /redirect it to a file and then use Read/);
  assert.doesNotMatch(text, /fetch_tool_result/);
});

test("Bash includes the truncation warning in a non-zero exit error", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-bash-error-truncate-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const bash = toolsFor(root).find((tool) => tool.name === "Bash")!;

  await assert.rejects(
    () =>
      bash.execute("bash-error-truncate", {
        command: "printf '%*s' 120000 '' | tr ' ' x; exit 7",
      }),
    (error: Error) => {
      assert.match(error.message, /exited with code 7/);
      assert.match(error.message, /17600 bytes were discarded/);
      assert.match(error.message, /cannot be recovered/);
      assert.match(error.message, /redirect it to a file and then use Read/);
      assert.doesNotMatch(error.message, /fetch_tool_result/);
      return true;
    }
  );
});
