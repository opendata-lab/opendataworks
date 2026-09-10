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
    meta: { fold: { result_ref: "res_abc" } },
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
  assert.equal(
    ((payload.output_meta as Record<string, unknown>).fold as Record<string, unknown>).result_ref,
    "res_abc"
  );
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

test("details that are not an object are kept whole, not destructured", async () => {
  // AgentToolResult types details as anything. Spreading a string turned it
  // into numeric keys, an array into indices, and a number into nothing.
  const { withFoldProvenance } = await import("../src/kernel/cell.js");

  assert.equal(withFoldProvenance("plain text", { folded: true }).raw_details, "plain text");
  assert.deepEqual(withFoldProvenance([1, 2], { folded: true }).raw_details, [1, 2]);
  assert.equal(withFoldProvenance(42, { folded: true }).raw_details, 42);
  // A string must not survive as {"0":"p","1":"l",...}.
  assert.equal(withFoldProvenance("ab", { folded: true })["0"], undefined);
  // Nothing to keep means nothing invented.
  assert.equal("raw_details" in withFoldProvenance(null, { folded: true }), false);
  assert.equal("raw_details" in withFoldProvenance(undefined, { folded: true }), false);
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

    assert.ok(Object.hasOwn(engine, name), `${name} must be stored as a key`);
    assert.deepEqual(
      Object.getOwnPropertyDescriptor(engine, name)?.value,
      { v: 1 },
      `${name} must survive as data`
    );
    assert.equal(meta["[object Object]"], undefined);
    // The value must not have become anyone's prototype.
    assert.equal(Object.getPrototypeOf(engine), Object.prototype);
  }

  // And nothing leaked onto every object in the process.
  assert.equal(({} as Record<string, unknown>).v, undefined);
});

test("redaction keeps a __proto__ key instead of dropping it", async () => {
  // The unwrap fix alone was not enough: redact runs over every output and
  // output_meta on the way to the transcript, and its accumulator had the same
  // problem, so the value was still lost before anything was stored.
  const { redact } = await import("../src/observability/redaction.js");

  const redacted = redact(
    JSON.parse('{"engine_details": {"__proto__": {"x": 1}, "api_key": "sk-live-abc"}}')
  ) as Record<string, Record<string, unknown>>;

  // Own property, not the prototype: reading `.__proto__` returns the
  // prototype, so it answers {x:1} whether the key was stored or used to
  // reassign the prototype — which is how the first version of this test
  // passed against the bug it was written for.
  const engine = redacted.engine_details;
  assert.ok(Object.hasOwn(engine, "__proto__"), "__proto__ must be stored as a key");
  assert.deepEqual(Object.getOwnPropertyDescriptor(engine, "__proto__")?.value, { x: 1 });
  assert.equal(Object.getPrototypeOf(engine), Object.prototype);
  // Redaction still does its job on the way through.
  assert.notEqual(redacted.engine_details.api_key, "sk-live-abc");
  assert.equal(({} as Record<string, unknown>).x, undefined);
});

test("a __proto__ detail survives the whole normalizer path", async () => {
  // End to end through the event the transcript actually stores, because the
  // last fix passed its own unit test and was undone one call later.
  const { EventNormalizer } = await import("../src/kernel/event-normalizer.js");
  const { RunStateMachine } = await import("../src/kernel/run-state-machine.js");

  const normalizer = new EventNormalizer(new RunStateMachine("r", "t", "r"));
  const events = normalizer.normalize({
    type: "tool_execution_end",
    toolCallId: "tc",
    toolName: "mcp_tool",
    isError: false,
    result: JSON.parse('{"content": [], "details": {"__proto__": {"x": 1}, "count": 2}}'),
  } as never);

  const payload = events.find((e) => e.type === "tool.completed")!.payload as Record<string, unknown>;
  const meta = payload.output_meta as Record<string, unknown>;
  assert.equal(meta.count, 2);
  const engine = meta.engine_details as Record<string, unknown>;
  assert.ok(Object.hasOwn(engine, "__proto__"));
  assert.deepEqual(Object.getOwnPropertyDescriptor(engine, "__proto__")?.value, { x: 1 });
  assert.equal(Object.getPrototypeOf(engine), Object.prototype);
  assert.equal(meta["[object Object]"], undefined);
});

test("fold provenance nests, so it cannot collide with what the tool wrote", async () => {
  // fetch_tool_result reports the source it read in details.result_ref. When the
  // fold claimed that name too, one of the two had to lose; nesting means
  // neither does, with no field list or prefix rule to keep in sync.
  const { withFoldProvenance } = await import("../src/kernel/cell.js");
  const { unwrapToolResult } = await import("../src/kernel/event-normalizer.js");

  const details = withFoldProvenance(
    { result_ref: "res_source", is_tabular: true },
    { result_ref: "res_fold", original_bytes: 900 }
  );
  const meta = unwrapToolResult({ content: [], details }).output_meta!;

  assert.equal(meta.result_ref, "res_source", "the tool keeps its own field");
  assert.deepEqual(meta.fold, { result_ref: "res_fold", original_bytes: 900 });
});
