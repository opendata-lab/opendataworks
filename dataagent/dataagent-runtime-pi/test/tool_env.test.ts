/**
 * The shell environment handed to a model-driven command.
 *
 * The Cell's own environment carries provider API keys and database
 * credentials. Passing a copy of it to `bash -c` would make every one of them
 * readable with `env`, by a command the model chose. These tests pin the
 * allowlist so that stays impossible.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildShellEnv, runShell, createTools, SHELL_ENV_ALLOWLIST } from "../src/tools/tool-registry.js";
import { WorkspaceBoundaryEnforcer, type BoundaryPolicy } from "../src/policy/workspace-boundary-enforcer.js";

const SECRET_ENV = {
  PATH: "/usr/bin:/bin",
  HOME: "/home/agent",
  DATAAGENT_PYTHON_BIN: "/usr/bin/python3",
  DATAAGENT_SKILL_ROOT: "/skills/nl2sql",
  DATAAGENT_PLATFORM_SKILL_ROOT: "/skills/platform-tools",
  SKILLS_ROOT_DIR: "/skills",
  ODW_BACKEND_BASE_URL: "http://backend:8080/api/v1/ai",
  ODW_AGENT_SERVICE_TOKEN: "service-token-123",
  ANTHROPIC_API_KEY: "sk-secret-anthropic",
  OPENAI_API_KEY: "sk-secret-openai",
  MYSQL_PASSWORD: "dataagent123",
  DATAAGENT_RUNTIME_SECRET: "shared-hmac-secret",
  AWS_SECRET_ACCESS_KEY: "aws-secret",
} as NodeJS.ProcessEnv;

test("shell env carries only allowlisted variables", () => {
  const env = buildShellEnv(SECRET_ENV, {});

  assert.equal(env.PATH, "/usr/bin:/bin");
  assert.equal(env.HOME, "/home/agent");
  assert.equal(env.DATAAGENT_PYTHON_BIN, "/usr/bin/python3");
  assert.equal(env.DATAAGENT_SKILL_ROOT, "/skills/nl2sql");
  assert.equal(env.DATAAGENT_PLATFORM_SKILL_ROOT, "/skills/platform-tools");
  assert.equal(env.SKILLS_ROOT_DIR, "/skills");
  assert.equal(env.ODW_BACKEND_BASE_URL, "http://backend:8080/api/v1/ai");
  assert.equal(env.ODW_AGENT_SERVICE_TOKEN, "service-token-123");
});

test("provider and database credentials never reach the shell", () => {
  const env = buildShellEnv(SECRET_ENV, {});

  for (const leaked of [
    "ANTHROPIC_API_KEY",
    "OPENAI_API_KEY",
    "MYSQL_PASSWORD",
    "DATAAGENT_RUNTIME_SECRET",
    "AWS_SECRET_ACCESS_KEY",
  ]) {
    assert.equal(env[leaked], undefined, `${leaked} must not be visible to a model-driven shell`);
  }
  const serialized = JSON.stringify(env);
  assert.ok(!serialized.includes("sk-secret"), "no provider key may appear in the shell env");
  assert.ok(!serialized.includes("dataagent123"), "no database password may appear in the shell env");
});

test("runtime_env cannot smuggle a non-allowlisted variable through", () => {
  // The control plane's runtime_env is additive but still filtered: a bug or a
  // compromised profile upstream must not be able to widen the shell's view.
  const env = buildShellEnv(SECRET_ENV, {
    DATAAGENT_PYTHON_BIN: "/opt/py/bin/python3",
    ANTHROPIC_API_KEY: "sk-injected",
    EVIL_VAR: "x",
  });

  assert.equal(env.DATAAGENT_PYTHON_BIN, "/opt/py/bin/python3", "allowlisted value may be overridden");
  assert.equal(env.ANTHROPIC_API_KEY, undefined);
  assert.equal(env.EVIL_VAR, undefined);
});

test("static contract: platform-tools scripts declare no variables absent from SHELL_ENV_ALLOWLIST", () => {
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const candidateDirs = [
    path.resolve(testDir, "../../../.claude/skills/opendataworks-platform-tools"),
    path.resolve(testDir, "../../.claude/skills/opendataworks-platform-tools"),
    path.resolve(process.cwd(), "../.claude/skills/opendataworks-platform-tools"),
  ];
  const toolsDir = candidateDirs.find((dir) => fs.existsSync(dir));
  assert.ok(toolsDir, `Could not find opendataworks-platform-tools in: ${candidateDirs.join(", ")}`);

  const filesToScan: string[] = [];
  const binCli = path.join(toolsDir, "bin", "odw-cli");
  if (fs.existsSync(binCli)) {
    filesToScan.push(binCli);
  }

  const scriptsDir = path.join(toolsDir, "scripts");
  if (fs.existsSync(scriptsDir)) {
    for (const f of fs.readdirSync(scriptsDir)) {
      if (f.endsWith(".py")) {
        filesToScan.push(path.join(scriptsDir, f));
      }
    }
  }

  assert.ok(filesToScan.length >= 5, `Expected to scan platform scripts, found ${filesToScan.length}`);

  const declaredVars = new Set<string>();
  const varPattern = /(?:DATAAGENT|ODW)_[A-Z0-9_]+/g;

  for (const file of filesToScan) {
    const content = fs.readFileSync(file, "utf8");
    for (const match of content.matchAll(varPattern)) {
      declaredVars.add(match[0]);
    }
  }

  const missingFromAllowlist: string[] = [];
  for (const declaredVar of declaredVars) {
    if (!SHELL_ENV_ALLOWLIST.includes(declaredVar)) {
      missingFromAllowlist.push(declaredVar);
    }
  }

  assert.deepEqual(
    missingFromAllowlist,
    [],
    `Platform-tools declared variables missing from SHELL_ENV_ALLOWLIST: ${missingFromAllowlist.join(", ")}`
  );
});

test("a command really cannot read a secret from its environment", async () => {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "odw-shell-"));
  const result = await runShell("echo \"key=${ANTHROPIC_API_KEY:-ABSENT} pw=${MYSQL_PASSWORD:-ABSENT}\"", {
    cwd,
    env: buildShellEnv(SECRET_ENV, {}),
    timeoutMs: 10_000,
  });

  assert.equal(result.exitCode, 0);
  assert.match(result.stdout, /key=ABSENT/);
  assert.match(result.stdout, /pw=ABSENT/);
});

test("a shell command exceeding its timeout is killed", async () => {
  const cwd = fs.mkdtempSync(path.join(os.tmpdir(), "odw-shell-"));
  const result = await runShell("sleep 30", {
    cwd,
    env: buildShellEnv(SECRET_ENV, {}),
    timeoutMs: 300,
  });

  assert.equal(result.timedOut, true);
});

test("tools refuse a path outside the workspace before touching the filesystem", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-tools-"));
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), "odw-outside-"));
  fs.writeFileSync(path.join(outside, "secret.txt"), "top secret", "utf-8");

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

  const tools = createTools({
    boundary: new WorkspaceBoundaryEnforcer(policy),
    workspaceRoot: root,
    runtimeEnv: {},
  });

  // A denial must *throw*. AgentToolResult has no isError field: the agent loop
  // sets isError only when execute throws, so returning { isError: true } was
  // silently discarded and a denied call reached the model and the chat UI
  // marked as a success.
  const read = tools.find((t) => t.name === "Read")!;
  await assert.rejects(
    () => read.execute("call-1", { file_path: path.join(outside, "secret.txt") }),
    /outside workspace/
  );

  const bash = tools.find((t) => t.name === "Bash")!;
  await assert.rejects(
    () => bash.execute("call-2", { command: `cat ${outside}/secret.txt` }),
    /outside workspace/
  );
});

test("a failing shell command throws so the model and the UI both see an error", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-tools-"));
  const policy: BoundaryPolicy = {
    policy_version: 1,
    profile: "pi_agent_core",
    workspace_root: root,
    allowed_roots: [root],
    allowed_executables: [],
    discard_sinks: ["/dev/null"],
    tool_path_keys: {},
    operator_chars: "();<>|&",
    tool_result_root: null,
    readonly_commands: [],
  };
  const tools = createTools({
    boundary: new WorkspaceBoundaryEnforcer(policy),
    workspaceRoot: root,
    runtimeEnv: {},
  });
  const bash = tools.find((t) => t.name === "Bash")!;

  // The output has to survive: createErrorToolResult keeps only the message.
  await assert.rejects(
    () => bash.execute("call-3", { command: "echo before-failure; exit 7" }),
    (err: Error) => /exited with code 7/.test(err.message) && /before-failure/.test(err.message)
  );

  const ok = await bash.execute("call-4", { command: "echo fine" });
  assert.match(ok.content[0].type === "text" ? ok.content[0].text : "", /fine/);
});

test("a failed command inside a pipeline is not masked by a successful final stage", async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "odw-tools-"));
  const policy: BoundaryPolicy = {
    policy_version: 1,
    profile: "pi_agent_core",
    workspace_root: root,
    allowed_roots: [root],
    allowed_executables: [],
    discard_sinks: ["/dev/null"],
    tool_path_keys: {},
    operator_chars: "();<>|&",
    tool_result_root: null,
    readonly_commands: [],
  };
  const tools = createTools({
    boundary: new WorkspaceBoundaryEnforcer(policy),
    workspaceRoot: root,
    runtimeEnv: {},
  });
  const bash = tools.find((t) => t.name === "Bash")!;

  await assert.rejects(
    () => bash.execute("call-pipefail", { command: "(echo upstream-failure >&2; exit 9) | head -n 1" }),
    (err: Error) => /exited with code 9/.test(err.message) && /upstream-failure/.test(err.message)
  );
});
