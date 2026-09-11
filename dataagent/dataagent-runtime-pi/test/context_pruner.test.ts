import test from "node:test";
import assert from "node:assert/strict";
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { pruneContext } from "../src/context/context-pruner.js";

function mockAssistant(content: any[]): AgentMessage {
  return {
    role: "assistant",
    content,
    api: "faux" as any,
    provider: "faux",
    model: "faux-1",
    usage: { input: 1, output: 1, totalTokens: 2 } as any,
    stopReason: "stop",
    timestamp: Date.now(),
  };
}

function mockUser(text: string): AgentMessage {
  return {
    role: "user",
    content: [{ type: "text", text }],
    timestamp: Date.now(),
  };
}

test("pruneContext leaves short conversations unchanged", () => {
  const msgs: AgentMessage[] = [
    mockUser("query 1"),
    mockAssistant([{ type: "text", text: "answer 1" }]),
  ];

  const result = pruneContext(msgs, { protectTailCount: 6 });
  assert.equal(result.length, msgs.length);
  assert.deepEqual(result, msgs);
});

test("pruneContext preserves first user prompt and protected recent tail", () => {
  const msgs: AgentMessage[] = [
    mockUser("ORIGINAL_QUESTION"),
    mockAssistant([{ type: "text", text: "a1" }]),
    mockUser("q2"),
    mockAssistant([{ type: "text", text: "a2" }]),
    mockUser("q3"),
    mockAssistant([{ type: "text", text: "a3" }]),
    mockUser("q4"),
    mockAssistant([{ type: "text", text: "a4" }]),
    mockUser("q5"),
    mockAssistant([{ type: "text", text: "a5" }]),
  ];

  const result = pruneContext(msgs, { protectTailCount: 4 });
  assert.equal(result.length, msgs.length);

  // First user prompt preserved
  const firstUser = result[0] as any;
  assert.equal(firstUser.content[0].text, "ORIGINAL_QUESTION");

  // Tail (last 4) preserved exactly
  const tail = result.slice(-4);
  assert.deepEqual(tail, msgs.slice(-4));
});

test("pruneContext deduplicates repeated historical tool calls", () => {
  const msgs: AgentMessage[] = [
    mockUser("start"),
    // Turn 1: tool call for DESCRIBE orders
    mockAssistant([
      {
        type: "toolCall",
        id: "call_1",
        name: "run_sql",
        arguments: { sql: "DESCRIBE orders" },
      },
    ]),
    {
      role: "toolResult" as any,
      toolCallId: "call_1",
      toolName: "run_sql",
      content: [{ type: "text", text: "columns for orders... large text" }],
      isError: false,
      timestamp: 3,
    } as any,
    mockUser("next"),
    mockAssistant([{ type: "text", text: "thinking..." }]),
    mockUser("repeat"),
    // Turn 3: identical tool call repeated
    mockAssistant([
      {
        type: "toolCall",
        id: "call_2",
        name: "run_sql",
        arguments: { sql: "DESCRIBE orders" },
      },
    ]),
    {
      role: "toolResult" as any,
      toolCallId: "call_2",
      toolName: "run_sql",
      content: [{ type: "text", text: "columns for orders... fresh" }],
      isError: false,
      timestamp: 8,
    } as any,
    mockAssistant([{ type: "text", text: "final" }]),
  ];

  const result = pruneContext(msgs, { protectTailCount: 3 });

  // Earlier tool call_1 result should be superseded
  const oldResult = result.find((m: any) => m.toolCallId === "call_1");
  assert.ok(oldResult);
  assert.match((oldResult as any).content[0].text, /superseded in a later turn/);

  // Later tool call_2 result in protected tail remains intact
  const newResult = result.find((m: any) => m.toolCallId === "call_2");
  assert.ok(newResult);
  assert.match((newResult as any).content[0].text, /fresh/);
});

test("pruneContext condenses historical error stack traces", () => {
  const verboseStack = [
    "MySQL OperationalError 1064: You have an error in your SQL syntax",
    "  at Connection.query (/app/mysql.js:123:45)",
    "  at Client.execute (/app/client.js:67:89)",
    "  at async runSql (/app/run.js:10:11)",
    "  at async Object.execute (/app/tool.js:5:6)",
  ].join("\n");

  const msgs: AgentMessage[] = [
    mockUser("do query"),
    mockAssistant([
      { type: "toolCall", id: "call_err", name: "run_sql", arguments: { sql: "SELCT * FROM bad" } },
    ]),
    {
      role: "toolResult" as any,
      toolCallId: "call_err",
      toolName: "run_sql",
      content: [{ type: "text", text: verboseStack }],
      isError: true,
      timestamp: 3,
    } as any,
    mockAssistant([{ type: "text", text: "fixing query..." }]),
    mockUser("retry"),
    mockAssistant([{ type: "text", text: "fixed" }]),
    mockUser("more"),
    mockAssistant([{ type: "text", text: "tail 1" }]),
    mockUser("tail 2"),
    mockAssistant([{ type: "text", text: "tail 3" }]),
  ];

  const result = pruneContext(msgs, { protectTailCount: 4 });
  const errorMsg = result.find((m: any) => m.toolCallId === "call_err");
  assert.ok(errorMsg);
  const text = (errorMsg as any).content[0].text;
  assert.match(text, /Historical Error/);
  assert.match(text, /verbose stack trace pruned/);
  assert.equal(text.includes("/app/client.js"), false);
  // Nothing here checks for a later successful recovery, so the digest must not
  // assert one — telling the model a still-broken step was resolved is worse
  // than telling it nothing.
  assert.equal(/resolved/i.test(text), false);
});

test("maxContextTokens evicts old tool output instead of being ignored", async () => {
  // The option used to be accepted and never read, so the whole budget layer
  // was a no-op. Pairing must survive eviction: content is emptied, messages
  // are not removed.
  const bulk = "数".repeat(20_000);
  const messages: any[] = [{ role: "user", content: "起始问题" }];
  for (let i = 0; i < 6; i += 1) {
    messages.push({
      role: "assistant",
      content: [{ type: "toolCall", id: `call_${i}`, name: "run_sql", arguments: { i } }],
    });
    messages.push({
      role: "toolResult",
      toolCallId: `call_${i}`,
      content: [{ type: "text", text: bulk }],
    });
  }

  const unbounded = pruneContext(messages, { protectTailCount: 2 });
  const bounded = pruneContext(messages, { protectTailCount: 2, maxContextTokens: 2_000 });

  const size = (msgs: any[]) => Buffer.byteLength(JSON.stringify(msgs), "utf8");
  assert.ok(size(bounded) < size(unbounded), "budget must actually shrink the context");
  assert.equal(bounded.length, messages.length, "eviction must not drop messages");

  const callIds = (msgs: any[]) =>
    msgs.flatMap((m) =>
      m.role === "assistant" && Array.isArray(m.content)
        ? m.content.filter((b: any) => b.type === "toolCall").map((b: any) => b.id)
        : []
    );
  assert.deepEqual(callIds(bounded), callIds(messages), "tool calls must be preserved");
});

test("budget eviction only suggests fetch_tool_result when that result has a ref", () => {
  const bulk = "x".repeat(12_000);
  const messages: any[] = [
    { role: "user", content: "start" },
    {
      role: "assistant",
      content: [{ type: "toolCall", id: "without-ref", name: "small_tool", arguments: {} }],
    },
    {
      role: "toolResult",
      toolCallId: "without-ref",
      content: [{ type: "text", text: bulk }],
    },
    {
      role: "assistant",
      content: [{ type: "toolCall", id: "with-ref", name: "large_tool", arguments: {} }],
    },
    {
      role: "toolResult",
      toolCallId: "with-ref",
      content: [{ type: "text", text: bulk }],
      details: { dataagent_fold: { result_ref: "res_saved_123" } },
    },
    { role: "user", content: "tail" },
    { role: "assistant", content: "tail answer" },
  ];

  const pruned = pruneContext(messages, { protectTailCount: 2, maxContextTokens: 1 });
  const withoutRef = pruned.find((message: any) => message.toolCallId === "without-ref") as any;
  const withRef = pruned.find((message: any) => message.toolCallId === "with-ref") as any;
  const withoutRefText = withoutRef.content[0].text;
  const withRefText = withRef.content[0].text;

  assert.match(withoutRefText, /not saved and is no longer recoverable/);
  assert.doesNotMatch(withoutRefText, /fetch_tool_result/);
  assert.match(withRefText, /fetch_tool_result\(result_ref="res_saved_123"\)/);
});

test("a failed retry does not supersede an earlier successful result", async () => {
  const messages: any[] = [
    { role: "user", content: "问题" },
    { role: "assistant", content: [{ type: "toolCall", id: "c1", name: "run_sql", arguments: { q: 1 } }] },
    { role: "toolResult", toolCallId: "c1", content: [{ type: "text", text: "GOOD_ROWS" }] },
    { role: "assistant", content: [{ type: "toolCall", id: "c2", name: "run_sql", arguments: { q: 1 } }] },
    { role: "toolResult", toolCallId: "c2", isError: true, content: [{ type: "text", text: "timeout" }] },
    { role: "user", content: "继续" },
    { role: "assistant", content: "好" },
    { role: "user", content: "再继续" },
    { role: "assistant", content: "好的" },
  ];

  const pruned = pruneContext(messages, { protectTailCount: 2 });
  const first = pruned.find((m: any) => m.toolCallId === "c1") as any;
  assert.match(first.content[0].text, /GOOD_ROWS/);
});
