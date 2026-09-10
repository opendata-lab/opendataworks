import test from "node:test";
import assert from "node:assert/strict";
import { CompactionSession, estimateBytes } from "../src/context/compaction-session.js";

function convo(turns: number, bulkChars = 400): any[] {
  const msgs: any[] = [{ role: "user", content: "起始问题" }];
  for (let i = 0; i < turns; i += 1) {
    msgs.push({
      role: "assistant",
      content: [{ type: "toolCall", id: `c${i}`, name: "run_sql", arguments: { i } }],
    });
    msgs.push({
      role: "toolResult",
      toolCallId: `c${i}`,
      content: [{ type: "text", text: "数".repeat(bulkChars) }],
    });
  }
  return msgs;
}

test("below the high watermark the input is returned untouched", () => {
  // This is the whole point: an unchanged prefix is what scores a cache hit.
  const s = new CompactionSession({ maxContextTokens: 100_000, protectTailCount: 2 });
  const msgs = convo(3);
  const out = s.transform(msgs);
  assert.deepEqual(out, msgs);
  assert.equal(s.generations, 0);
});

test("a stable conversation keeps a byte-identical prefix across turns", () => {
  const s = new CompactionSession({ maxContextTokens: 100_000, protectTailCount: 2 });
  const first = s.transform(convo(3));
  const grown = convo(4);
  const second = s.transform(grown);
  // The messages that existed on the first turn must come back unchanged.
  assert.deepEqual(second.slice(0, first.length), first);
});

test("crossing the watermark compacts exactly once", () => {
  const s = new CompactionSession({ maxContextTokens: 900, protectTailCount: 2, targetRatio: 0.5 });
  const msgs = convo(12);
  const out = s.transform(msgs);
  assert.equal(s.generations, 1);
  assert.ok(estimateBytes(out) < estimateBytes(msgs), "compaction must shrink the context");
});

test("staying below the watermark afterwards does not start a new generation", () => {
  const s = new CompactionSession({ maxContextTokens: 900, protectTailCount: 2, targetRatio: 0.5 });
  const base = convo(12);
  s.transform(base);
  assert.equal(s.generations, 1);
  // A short follow-up keeps the total under the ceiling; re-compacting here
  // would throw away a cache hit for nothing.
  s.transform([...base, { role: "user", content: "追问" }]);
  assert.equal(s.generations, 1, "a small append must not re-compact");
});

test("crossing again starts the next generation", () => {
  const s = new CompactionSession({ maxContextTokens: 900, protectTailCount: 2, targetRatio: 0.5 });
  s.transform(convo(12));
  assert.equal(s.generations, 1);
  s.transform(convo(60));
  assert.equal(s.generations, 2);
});

test("the committed prefix is reused, not recomputed, while under the watermark", () => {
  const s = new CompactionSession({ maxContextTokens: 900, protectTailCount: 2, targetRatio: 0.5 });
  const base = convo(12);
  const compacted = s.transform(base);

  const grown = [...base, { role: "user", content: "追问" }];
  const next = s.transform(grown);

  // Same prefix object contents, plus exactly the appended message: the tail
  // boundary did not drift as the array grew.
  assert.deepEqual(next.slice(0, compacted.length), compacted);
  assert.deepEqual(next[next.length - 1], { role: "user", content: "追问" });
});

test("maxContextTokens of zero disables compaction entirely", () => {
  const s = new CompactionSession({ maxContextTokens: 0 });
  const msgs = convo(40);
  assert.deepEqual(s.transform(msgs), msgs);
  assert.equal(s.generations, 0);
});

test("a protected tail larger than the target does not cause per-turn recompaction", () => {
  // Compaction cannot shrink the protected tail. Without a floor measured from
  // what compaction actually achieved, every later turn would compact again,
  // achieve the same size, and throw away the cache each time.
  const s = new CompactionSession({
    maxContextTokens: 900,
    protectTailCount: 6,
    targetRatio: 0.1, // deliberately unreachable
  });
  const base = convo(12);
  s.transform(base);
  assert.equal(s.generations, 1);

  let msgs = base;
  for (let i = 0; i < 5; i += 1) {
    msgs = [...msgs, { role: "user", content: `追问 ${i}` }];
    s.transform(msgs);
  }
  assert.equal(s.generations, 1, "small appends must not each trigger a compaction");
});
