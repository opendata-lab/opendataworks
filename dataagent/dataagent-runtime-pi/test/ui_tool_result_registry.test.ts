import test from "node:test";
import assert from "node:assert/strict";
import { UiToolResultRegistry } from "../src/kernel/ui-tool-result-registry.js";

test("a registered result is returned once and then gone", () => {
  // Consuming on read keeps the map bounded, so a run cannot accumulate tool
  // results in memory.
  const r = new UiToolResultRegistry();
  r.set("call-1", { output: [{ type: "text", text: "x" }], meta: null });

  assert.deepEqual(r.take("call-1")?.output, [{ type: "text", text: "x" }]);
  assert.equal(r.take("call-1"), null);
  assert.equal(r.unconsumed, 0);
});

test("a lookup that finds nothing returns null rather than throwing", () => {
  // The normalizer falls back to the event's own result; losing the event would
  // be worse than losing the full copy.
  const r = new UiToolResultRegistry();
  assert.equal(r.take("nope"), null);
});

test("an unclaimed lookup is not treated as a fault", () => {
  // Only folded results are registered, so every small result looks up nothing
  // by design. Counting those as faults is what drowned the signal.
  const r = new UiToolResultRegistry();
  r.take("small-1");
  r.take("small-2");
  assert.equal(r.unconsumed, 0);
});

test("a registered result nobody claimed is reported", () => {
  // The asymmetry that does mean something: afterToolCall registered a copy and
  // no tool_execution_end ever came for it, so the pairing is broken.
  const r = new UiToolResultRegistry();
  r.set("orphan", { output: "1", meta: null });
  assert.equal(r.unconsumed, 1);
});

test("an entry without a tool call id is refused", () => {
  // Nothing could ever look it up again, so keeping it would only leak.
  const r = new UiToolResultRegistry();
  r.set("", { output: "x", meta: null });
  r.set("   ", { output: "x", meta: null });
  assert.equal(r.unconsumed, 0);
});

test("clear drops everything so a run cannot leak results", () => {
  const r = new UiToolResultRegistry();
  r.set("a", { output: "1", meta: null });
  r.set("b", { output: "2", meta: null });
  r.clear();
  assert.equal(r.unconsumed, 0);
});
