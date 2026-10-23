import test from "node:test";
import assert from "node:assert/strict";
import { CompactionSession, estimateBytes, measuredBytesPerToken } from "../src/context/compaction-session.js";

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

test("the leading system message survives compaction unchanged", () => {
  // pi 1.x puts the system prompt at index 0 of the transformed context; a
  // compaction that dropped or rewrote it would silently strip the agent's
  // instructions and invalidate the cached prefix.
  const s = new CompactionSession({ maxContextTokens: 900, protectTailCount: 2, targetRatio: 0.5 });
  // Shaped like what pi 1.x actually sends: the prompt plus the tool set.
  const system = {
    role: "system",
    content: "系统提示".repeat(20),
    toolsAdded: [{ name: "Bash", description: "run a shell command", parameters: { type: "object" } }],
  };
  const msgs = [system, ...convo(12)];
  const out = s.transform(msgs);
  assert.equal(s.generations, 1, "the fixture must actually compact");
  assert.deepEqual(out[0], system);
  // The first user message stays the task anchor right after the system prompt.
  assert.deepEqual(out[1], msgs[1]);
  const next = s.transform([...msgs, { role: "user", content: "追问" }]);
  // Appending must reuse the committed prefix byte for byte, not recompact it.
  assert.deepEqual(next.slice(0, out.length), out);
});

function withUsage(msgs: any[], tokens: number): any[] {
  // The provider's report for the latest call: prompt plus its own output.
  const last = msgs.map((m) => m.role).lastIndexOf("assistant");
  return msgs.map((m, i) =>
    i === last ? { ...m, usage: { input: tokens - 100, cacheRead: 0, cacheWrite: 0, output: 100 } } : m
  );
}

test("without usage the fixed estimate is kept", () => {
  assert.equal(measuredBytesPerToken(convo(3)), 3);
});

test("measured usage replaces the fixed estimate, never below it and capped", () => {
  const msgs = convo(6);
  const bytes = estimateBytes(msgs.slice(0, msgs.length - 1));
  assert.ok(Math.abs(measuredBytesPerToken(withUsage(msgs, Math.round(bytes / 6))) - 6) < 0.1);
  assert.equal(measuredBytesPerToken(withUsage(msgs, bytes)), 3);
  assert.equal(measuredBytesPerToken(withUsage(msgs, 1)), 12);
});

test("a context that is far below the window by real usage is not compacted", () => {
  // Over the ceiling at 3 bytes per token, but the provider reports far fewer
  // tokens: compacting here would only throw the cache away.
  const msgs = convo(12);
  const ceilingAtThree = Math.ceil(estimateBytes(msgs) / 3 / 0.9);
  const s = new CompactionSession({ maxContextTokens: Math.floor(ceilingAtThree * 0.8), protectTailCount: 2 });
  const real = withUsage(msgs, Math.round(estimateBytes(msgs) / 8));
  assert.deepEqual(s.transform(real), real);
  assert.equal(s.generations, 0);
  const fixed = new CompactionSession({ maxContextTokens: Math.floor(ceilingAtThree * 0.8), protectTailCount: 2 });
  fixed.transform(msgs);
  assert.equal(fixed.generations, 1, "the same context still compacts when no usage is known");
});

test("usage before the search start is ignored in favour of the fallback", () => {
  // After a compaction the older report describes the uncompacted input.
  const msgs = withUsage(convo(6), 10);
  assert.equal(measuredBytesPerToken(msgs, msgs.length, 7), 7);
  assert.equal(measuredBytesPerToken(msgs, 0, 7), 12);
});

test("a lower ratio reported after compaction lowers the trigger again", () => {
  // Built while usage said 8 bytes per token; later usage says 3, so the same
  // bytes are many more tokens and must compact rather than wait for the old mark.
  const base = convo(12);
  const maxTokens = Math.floor(estimateBytes(base) / 8 / 0.95);
  const s = new CompactionSession({ maxContextTokens: maxTokens, protectTailCount: 2, targetRatio: 0.5 });
  s.transform(withUsage(base, Math.round(estimateBytes(base) / 8)));
  assert.equal(s.generations, 1);
  const grown = [...base, ...convo(4).slice(1)];
  const reply = { role: "assistant", content: [{ type: "text", text: "继续" }] };
  const latest = withUsage([...grown, reply], Math.round(estimateBytes([...grown, reply]) / 3));
  s.transform(latest);
  assert.equal(s.generations, 2);
});
