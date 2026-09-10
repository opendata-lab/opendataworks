import test from "node:test";
import assert from "node:assert/strict";
import { unwrapToolResult } from "../src/kernel/event-normalizer.js";

test("a pi tool result is split into SDK-shaped output and sibling meta", () => {
  // Emitting the whole {content, details} wrapper as `output` is what stopped
  // the frontend finding a platform `kind`, so charts and tables fell back to
  // raw JSON.
  const chart = JSON.stringify({ kind: "chart_spec", chart_type: "bar" });
  const { output, output_meta } = unwrapToolResult({
    content: [{ type: "text", text: chart }],
    details: { exitCode: 0, bytes: 128 },
  });

  assert.deepEqual(output, [{ type: "text", text: chart }]);
  assert.deepEqual(output_meta, { exit_code: 0, byte_count: 128 });
});

test("non-text blocks survive the unwrap", () => {
  const { output } = unwrapToolResult({
    content: [
      { type: "text", text: "see below" },
      { type: "image", data: "iVBOR", mimeType: "image/png" },
    ],
  });
  assert.equal((output as unknown[]).length, 2);
  assert.equal((output as Array<{ type: string }>)[1].type, "image");
});

test("unrecognised details are namespaced rather than dropped", () => {
  const { output_meta } = unwrapToolResult({
    content: [],
    details: { exitCode: 1, somethingNew: "keep me" },
  });
  assert.equal(output_meta?.exit_code, 1);
  assert.deepEqual(output_meta?.engine_details, { somethingNew: "keep me" });
});

test("a payload that is already plain passes through untouched", () => {
  // A platform object carrying `kind` at the top level must not be rewrapped.
  const platform = { kind: "sql_execution", rows: [{ a: 1 }] };
  const { output, output_meta } = unwrapToolResult(platform);
  assert.deepEqual(output, platform);
  assert.equal(output_meta, null);
});

test("a string result stays a string", () => {
  const { output, output_meta } = unwrapToolResult("plain text" as unknown);
  assert.equal(output, "plain text");
  assert.equal(output_meta, null);
});

test("no details means no output_meta key at all", () => {
  const { output_meta } = unwrapToolResult({ content: [{ type: "text", text: "x" }] });
  assert.equal(output_meta, null);
});

test("the UI copy survives folding, so a large chart still replays", async () => {
  // The decoupling this locks: the model gets a digest, the transcript keeps the
  // whole payload. Before, both read the same object, so any chart past the fold
  // threshold was gone from history for good.
  const { EventNormalizer } = await import("../src/kernel/event-normalizer.js");
  const { RunStateMachine } = await import("../src/kernel/run-state-machine.js");
  const { UiToolResultRegistry } = await import("../src/kernel/ui-tool-result-registry.js");

  const spec = {
    kind: "chart_spec",
    version: 1,
    chart_type: "bar",
    columns: ["name", "value"],
    dataset: Array.from({ length: 900 }, (_, i) => ({ name: `项目-${i}`, value: i })),
    error: null,
  };
  const fullText = JSON.stringify(spec);
  assert.ok(Buffer.byteLength(fullText, "utf8") > 16 * 1024, "fixture must exceed the fold threshold");

  const registry = new UiToolResultRegistry();
  registry.set("call-1", {
    content: [{ type: "text", text: fullText }],
    meta: { model_context_folded: true, result_ref: "res_abc" },
  });

  const sm = new RunStateMachine("run-1", "task-1", "run-1");
  const normalizer = new EventNormalizer(sm, registry);

  // What the agent loop hands over is the folded digest, not the full result.
  const events = normalizer.normalize({
    type: "tool_execution_end",
    toolCallId: "call-1",
    toolName: "run_sql",
    isError: false,
    result: { content: [{ type: "text", text: '{"_type":"dataagent_folded_result"}' }] },
  } as never);

  const completed = events.find((e) => e.type === "tool.completed");
  assert.ok(completed, "tool.completed must be emitted");
  const payload = completed!.payload as Record<string, unknown>;
  const output = payload.output as Array<{ text: string }>;

  // The transcript holds the chart, not the digest.
  assert.equal(JSON.parse(output[0].text).kind, "chart_spec");
  assert.equal(JSON.parse(output[0].text).dataset.length, 900);
  // Provenance says the model saw something smaller.
  assert.equal((payload.output_meta as Record<string, unknown>).model_context_folded, true);
});

test("without a registered copy the event result is still persisted", async () => {
  // A registry miss must degrade to the old behaviour, not drop the event.
  const { EventNormalizer } = await import("../src/kernel/event-normalizer.js");
  const { RunStateMachine } = await import("../src/kernel/run-state-machine.js");
  const { UiToolResultRegistry } = await import("../src/kernel/ui-tool-result-registry.js");

  const registry = new UiToolResultRegistry();
  const normalizer = new EventNormalizer(new RunStateMachine("r", "t", "r"), registry);

  const events = normalizer.normalize({
    type: "tool_execution_end",
    toolCallId: "absent",
    toolName: "Bash",
    isError: false,
    result: { content: [{ type: "text", text: "ok" }], details: { exitCode: 0 } },
  } as never);

  const payload = events.find((e) => e.type === "tool.completed")!.payload as Record<string, unknown>;
  assert.deepEqual(payload.output, [{ type: "text", text: "ok" }]);
  assert.equal((payload.output_meta as Record<string, unknown>).exit_code, 0);
  assert.equal(registry.misses, 1, "the miss must be counted for observability");
});
