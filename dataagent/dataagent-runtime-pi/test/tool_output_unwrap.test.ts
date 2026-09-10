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
    output: [{ type: "text", text: fullText }],
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
  // An unregistered lookup must degrade to the old behaviour, not drop the event.
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
  assert.equal(registry.unconsumed, 0, "an unregistered lookup is not a fault");
});

test("fold provenance takes the canonical names without discarding the tool's", async () => {
  // fetch_tool_result reports the source it read in details.result_ref. Folding
  // its output has to claim result_ref for the stored copy — a reader following
  // it must reach that copy — but overwriting silently swapped one plausible
  // ref for another, which is the kind of loss nobody notices.
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");

  const merged = mergeFoldProvenance(
    { result_ref: "res_source", is_tabular: true, original_bytes: 11 },
    { model_context_folded: true, result_ref: "res_fold", original_bytes: 99 }
  );

  assert.equal(merged.result_ref, "res_fold", "the fold must own the canonical name");
  assert.equal(merged.source_result_ref, "res_source", "the tool's ref must survive");
  assert.equal(merged.original_bytes, 99);
  assert.equal(merged.source_original_bytes, 11);
  // Non-colliding keys pass through untouched.
  assert.equal(merged.is_tabular, true);
  assert.equal(merged.model_context_folded, true);
});

test("a tool without colliding metadata gains nothing extra", async () => {
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");
  const merged = mergeFoldProvenance({ count: 3 }, { model_context_folded: true });
  assert.deepEqual(merged, { count: 3, model_context_folded: true });
});

test("folding past the persistence ceiling still reports the tool's details", async () => {
  // Past the ceiling nothing is registered, so the folded result is the only
  // copy the transcript gets. Replacing details rather than merging dropped
  // every detail the tool reported for exactly the largest results.
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");
  const { EventNormalizer } = await import("../src/kernel/event-normalizer.js");
  const { RunStateMachine } = await import("../src/kernel/run-state-machine.js");

  const foldedResult = {
    content: [{ type: "text", text: "digest" }],
    details: mergeFoldProvenance(
      { exitCode: 0, result_ref: "res_source" },
      { folded: true, result_ref: "res_fold", original_bytes: 900_000 }
    ),
  };

  const normalizer = new EventNormalizer(new RunStateMachine("r", "t", "r"));
  const events = normalizer.normalize({
    type: "tool_execution_end",
    toolCallId: "tc",
    toolName: "Bash",
    isError: false,
    result: foldedResult,
  } as never);

  const meta = (events.find((e) => e.type === "tool.completed")!.payload as Record<string, unknown>)
    .output_meta as Record<string, unknown>;
  // The digest identifies itself, which is why no separate ui_truncated flag
  // was added.
  assert.equal(meta.model_context_folded, true);
  assert.equal(meta.result_ref, "res_fold");
  // And the tool's own report is still there.
  assert.equal(meta.exit_code, 0);
  assert.equal((meta.engine_details as Record<string, unknown>).source_result_ref, "res_source");
});
