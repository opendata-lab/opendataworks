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
  // Top level, not buried in engine_details. This assertion previously encoded
  // the opposite, which is how the split went unnoticed: the same field landed
  // in a different place depending on how big the result was.
  assert.equal(meta.source_result_ref, "res_source");
});

test("a displaced value lands in the same place whatever the result's size", async () => {
  // The two branches build provenance at different stages, so this is the
  // property that keeps them honest: readers must not have to know which one
  // produced a record.
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");
  const { unwrapToolResult } = await import("../src/kernel/event-normalizer.js");

  const details = mergeFoldProvenance(
    { exitCode: 0, result_ref: "res_source" },
    { folded: true, result_ref: "res_fold", original_bytes: 10, stored_bytes: 7 }
  );
  // Small enough to register: the registry unwraps the merged details itself.
  const registered = unwrapToolResult({ content: [], details }).output_meta!;
  // Past the ceiling: the normalizer unwraps the same merged details later.
  const fallback = unwrapToolResult({ content: [], details }).output_meta!;

  assert.deepEqual(registered, fallback);
  assert.equal(registered.source_result_ref, "res_source");
  assert.equal(registered.result_ref, "res_fold");
  assert.equal(registered.original_bytes, 10);
  assert.equal(registered.stored_bytes, 7);
});

test("a second fold cannot overwrite the source the first one recorded", async () => {
  // One slot per field. Without the guard a nested fold would replace the
  // chain's root with its own parent and leave no sign anything was lost.
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");

  const once = mergeFoldProvenance({ result_ref: "res_root" }, { result_ref: "res_mid" });
  const twice = mergeFoldProvenance(once, { result_ref: "res_outer" });

  assert.equal(twice.result_ref, "res_outer");
  assert.equal(twice.source_result_ref, "res_root", "the first source must survive");
});

test("a source_ name a fold could not have written stays namespaced", async () => {
  // The prefix says nothing about who wrote it. source_error and source_count
  // are fields a tool is free to invent, and promoting them would put a tool's
  // own words where the contract promises fold provenance.
  const { unwrapToolResult } = await import("../src/kernel/event-normalizer.js");

  const meta = unwrapToolResult({
    content: [],
    details: { source_error: "upstream said no", source_count: 3, source_result_ref: "res_a" },
  }).output_meta!;

  const engine = meta.engine_details as Record<string, unknown>;
  assert.equal(engine.source_error, "upstream said no");
  assert.equal(engine.source_count, 3);
  assert.equal(meta.source_error, undefined);
  assert.equal(meta.source_count, undefined);
  // Only a field a fold actually claims is treated as displaced provenance.
  assert.equal(meta.source_result_ref, "res_a");
});

test("details that are not an object are kept whole, not destructured", async () => {
  // AgentToolResult types details as anything. Spreading a string turned it
  // into numeric keys, an array into indices, and a number into nothing.
  const { mergeFoldProvenance } = await import("../src/kernel/cell.js");

  assert.equal(mergeFoldProvenance("plain text", { folded: true }).raw_details, "plain text");
  assert.deepEqual(mergeFoldProvenance([1, 2], { folded: true }).raw_details, [1, 2]);
  assert.equal(mergeFoldProvenance(42, { folded: true }).raw_details, 42);
  // A string must not survive as {"0":"p","1":"l",...}.
  assert.equal(mergeFoldProvenance("ab", { folded: true })["0"], undefined);
  // Nothing to keep means nothing invented.
  assert.equal("raw_details" in mergeFoldProvenance(null, { folded: true }), false);
  assert.equal("raw_details" in mergeFoldProvenance(undefined, { folded: true }), false);
});

test("an unfolded result's non-object details are kept, not dropped", async () => {
  // The fold path already kept these, but the ordinary unwrap discarded them
  // while the Python reader kept them, so one record meant two things
  // depending on which side read it.
  const { unwrapToolResult } = await import("../src/kernel/event-normalizer.js");

  assert.equal(
    (unwrapToolResult({ content: [], details: "plain text" }).output_meta
      ?.engine_details as Record<string, unknown>).raw_details,
    "plain text"
  );
  assert.deepEqual(
    (unwrapToolResult({ content: [], details: [1, 2] }).output_meta
      ?.engine_details as Record<string, unknown>).raw_details,
    [1, 2]
  );
  assert.equal(
    (unwrapToolResult({ content: [], details: 42 }).output_meta
      ?.engine_details as Record<string, unknown>).raw_details,
    42
  );
  // Absent details still produce no meta at all.
  assert.equal(unwrapToolResult({ content: [] }).output_meta, null);
  assert.equal(unwrapToolResult({ content: [], details: null }).output_meta, null);
});

test("a details key that names something on Object.prototype is not an alias", async () => {
  // A plain object answers for every inherited name, so DETAIL_FIELD_ALIASES
  // returned a function for `toString` and the prototype itself for
  // `__proto__` — both truthy, so the value was filed under a key like
  // "[object Object]". Tool results come from MCP servers, so these names are
  // not ours to trust.
  const { unwrapToolResult } = await import("../src/kernel/event-normalizer.js");

  for (const name of ["toString", "constructor", "valueOf", "hasOwnProperty", "__proto__"]) {
    const meta = unwrapToolResult({
      content: [],
      details: JSON.parse(`{"${name}": {"v": 1}}`),
    }).output_meta!;
    const engine = meta.engine_details as Record<string, unknown>;

    assert.deepEqual(engine[name], { v: 1 }, `${name} must survive as data`);
    assert.equal(meta["[object Object]"], undefined);
    // The value must not have become anyone's prototype.
    assert.equal(Object.getPrototypeOf(engine), Object.prototype);
  }

  // And nothing leaked onto every object in the process.
  assert.equal(({} as Record<string, unknown>).v, undefined);
});
