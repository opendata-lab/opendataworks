import test from "node:test";
import assert from "node:assert/strict";
import { UiToolResultRegistry } from "../src/kernel/ui-tool-result-registry.js";

test("a registered result is returned once and then gone", () => {
  // Consuming on read keeps the map bounded and makes a double-persist show up
  // as a miss instead of silently succeeding twice.
  const r = new UiToolResultRegistry();
  r.set("call-1", { content: [{ type: "text", text: "x" }], meta: null });

  assert.deepEqual(r.take("call-1")?.content, [{ type: "text", text: "x" }]);
  assert.equal(r.take("call-1"), null);
  assert.equal(r.misses, 1);
  assert.equal(r.size, 0);
});

test("a miss is counted rather than throwing", () => {
  // The normalizer falls back to the event's own result; losing the event would
  // be worse than losing the full copy.
  const r = new UiToolResultRegistry();
  assert.equal(r.take("nope"), null);
  assert.equal(r.misses, 1);
});

test("an entry without a tool call id is refused", () => {
  // Nothing could ever look it up again, so keeping it would only leak.
  const r = new UiToolResultRegistry();
  r.set("", { content: "x", meta: null });
  r.set("   ", { content: "x", meta: null });
  assert.equal(r.size, 0);
});

test("clear drops everything so a run cannot leak results", () => {
  const r = new UiToolResultRegistry();
  r.set("a", { content: "1", meta: null });
  r.set("b", { content: "2", meta: null });
  r.clear();
  assert.equal(r.size, 0);
});
