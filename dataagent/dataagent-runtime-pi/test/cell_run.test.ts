/**
 * Cell run lifecycle: event shape, terminal guarantees, tool gating.
 *
 * Uses a stub StreamFn rather than a real provider so the assertions are about
 * this Cell's behaviour, not a model's.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { Cell } from "../src/kernel/cell.js";
import type { CellInitPayload, NeutralAgentEvent } from "../src/protocol/frames.js";

function workspace(): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "odw-cell-"));
  return dir;
}

function initPayload(root: string, overrides: Partial<CellInitPayload> = {}): CellInitPayload {
  return {
    run_id: "task-1",
    task_id: "task-1",
    topic_id: "topic-1",
    system_prompt: "sys",
    messages: [{ role: "user", content: "hi" }],
    model: { provider_id: "faux", model_id: "faux-1" },
    workspace: { project_cwd: root },
    boundary_policy: {
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
    },
    skills: [],
    runtime_env: {},
    limits: {
      total_timeout_seconds: 30,
      idle_timeout_seconds: 10,
      max_turns: 30,
      max_tool_calls: 50,
    },
    ...overrides,
  };
}

/** A StreamFn that emits one text answer and stops. */
function textStreamFactory(text: string) {
  return () => ({
    model: {} as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content: [{ type: "text" as const, text }],
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "text_start", contentIndex: 0, partial: message as never });
        for (const chunk of text.split(" ")) {
          stream.push({ type: "text_delta", contentIndex: 0, delta: chunk, partial: message as never });
        }
        stream.push({ type: "text_end", contentIndex: 0, content: text, partial: message as never });
        stream.push({ type: "done", reason: "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });
}

/** A StreamFn that emits thinking followed by text answer and stops. */
function thinkingAndTextStreamFactory(thought: string, answer: string) {
  return () => ({
    model: {} as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content: [
            { type: "thinking" as const, thinking: thought },
            { type: "text" as const, text: answer },
          ],
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 2, totalTokens: 3 },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        // Block 0: Thinking
        stream.push({ type: "thinking_start", contentIndex: 0, partial: message as never });
        stream.push({ type: "thinking_delta", contentIndex: 0, delta: thought, partial: message as never });
        stream.push({ type: "thinking_end", contentIndex: 0, content: thought, partial: message as never });
        // Block 1: Answer text
        stream.push({ type: "text_start", contentIndex: 1, partial: message as never });
        stream.push({ type: "text_delta", contentIndex: 1, delta: answer, partial: message as never });
        stream.push({ type: "text_end", contentIndex: 1, content: answer, partial: message as never });
        stream.push({ type: "done", reason: "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });
}

async function runCell(
  init: CellInitPayload,
  factory: ReturnType<typeof textStreamFactory>
): Promise<{ events: NeutralAgentEvent[]; result: Awaited<ReturnType<Cell["run"]>> }> {
  const events: NeutralAgentEvent[] = [];
  const cell = new Cell(factory as never);
  const result = await cell.run(init, (event) => events.push(event));
  return { events, result };
}

test("emits run.started first and exactly one terminal event", async () => {
  const root = workspace();
  const { events, result } = await runCell(initPayload(root), textStreamFactory("hello"));

  assert.equal(result.terminal_status, "success");
  assert.equal(events[0].type, "run.started");

  const terminals = events.filter((e) =>
    ["run.completed", "run.failed", "run.cancelled", "run.suspended"].includes(e.type)
  );
  assert.equal(terminals.length, 1, "exactly one terminal event");
  assert.equal(terminals[0].type, "run.completed");
  assert.equal(terminals[0], events[events.length - 1], "terminal event must be last");
});

test("sequences are contiguous and start at 1", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello"));

  // The Python writer drops any event whose sequence is not strictly greater
  // than the last, so a gap or a repeat here loses events downstream.
  assert.deepEqual(
    events.map((e) => e.sequence),
    events.map((_, index) => index + 1)
  );
});

test("uses dotted event type names from the closed contract enum", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello"));

  // "turn_start" (underscore) is not a member of the neutral contract; the
  // Python side rejects it outright and the turn silently disappears.
  for (const event of events) {
    assert.ok(event.type.includes("."), `event type '${event.type}' must be dotted`);
    assert.ok(!event.type.includes("_"), `event type '${event.type}' must not be snake_case`);
  }
});

test("content deltas carry kind and content_id", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello world"));

  const deltas = events.filter((e) => e.type === "content.delta");
  assert.ok(deltas.length > 0, "expected at least one content delta");
  for (const delta of deltas) {
    assert.ok(["answer", "reasoning"].includes(String(delta.payload.kind)));
    assert.ok(String(delta.payload.content_id).startsWith("c-"));
  }
});

test("content lifecycle produces content.started, content.delta, and content.completed", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hello world"));

  const starts = events.filter((e) => e.type === "content.started");
  const deltas = events.filter((e) => e.type === "content.delta");
  const completes = events.filter((e) => e.type === "content.completed");

  assert.equal(starts.length, 1, "expected 1 content.started event");
  assert.equal(starts[0].payload.kind, "answer");
  assert.equal(starts[0].payload.content_id, "c-0");

  assert.ok(deltas.length > 0, "expected at least one content.delta");

  assert.equal(completes.length, 1, "expected 1 content.completed event");
  assert.equal(completes[0].payload.kind, "answer");
  assert.equal(completes[0].payload.content_id, "c-0");
  assert.equal(completes[0].payload.text, "hello world");
});

test("thinking block completes before answer text starts", async () => {
  const root = workspace();
  const { events } = await runCell(
    initPayload(root),
    thinkingAndTextStreamFactory("let me think", "here is the answer")
  );

  const contentEvents = events.filter((e) =>
    ["content.started", "content.delta", "content.completed"].includes(e.type)
  );

  // First content.started must be reasoning (c-0)
  assert.equal(contentEvents[0].type, "content.started");
  assert.equal(contentEvents[0].payload.kind, "reasoning");
  assert.equal(contentEvents[0].payload.content_id, "c-0");

  // Find thinking completed event
  const thinkingEndIdx = contentEvents.findIndex(
    (e) => e.type === "content.completed" && e.payload.content_id === "c-0"
  );
  assert.ok(thinkingEndIdx > 0, "thinking block must complete");
  assert.equal(contentEvents[thinkingEndIdx].payload.kind, "reasoning");

  // Find text started event
  const textStartIdx = contentEvents.findIndex(
    (e) => e.type === "content.started" && e.payload.content_id === "c-1"
  );
  assert.ok(textStartIdx > thinkingEndIdx, "answer text must start AFTER thinking completes");
  assert.equal(contentEvents[textStartIdx].payload.kind, "answer");

  // Find text completed event
  const textEndIdx = contentEvents.findIndex(
    (e) => e.type === "content.completed" && e.payload.content_id === "c-1"
  );
  assert.ok(textEndIdx > textStartIdx, "answer text must complete");
  assert.equal(contentEvents[textEndIdx].payload.kind, "answer");
});

test("a model stream failure is reported as failed, not success", async () => {
  // Regression guard. agent.prompt() *resolves* when the model stream fails --
  // the failure lands on agent.state.errorMessage instead of rejecting -- so a
  // Cell that only watches for a thrown error reports this run as a success
  // carrying no answer, and a broken turn is persisted as a good one.
  const root = workspace();
  const failing = () => ({
    model: {} as never,
    streamFn: (() => {
      throw new Error("provider exploded");
    }) as never,
  });

  const { events, result } = await runCell(initPayload(root), failing as never);

  assert.equal(result.terminal_status, "failed");
  const last = events[events.length - 1];
  assert.equal(last.type, "run.failed");
  assert.equal(last.payload.error_code, "PI_MODEL_ERROR");
  assert.match(String(last.payload.message), /provider exploded/);
});

test("a thrown setup error is reported as an execution failure", async () => {
  // The other failure path: the model factory itself throws, before the agent
  // loop exists. It must still terminate the run rather than escape.
  const root = workspace();
  const { events, result } = await runCell(
    initPayload(root),
    (() => {
      throw new Error("factory exploded");
    }) as never
  );

  assert.equal(result.terminal_status, "failed");
  const last = events[events.length - 1];
  assert.equal(last.type, "run.failed");
  assert.equal(last.payload.error_code, "PI_EXECUTION_ERROR");
  assert.match(String(last.payload.message), /factory exploded/);
});

test("an unsupported provider fails the run rather than hanging it", async () => {
  const root = workspace();
  const { events, result } = await runCell(
    initPayload(root, { model: { provider_id: "nope", model_id: "x" } }),
    (() => {
      throw new Error("Pi 运行时不支持 provider 'nope'");
    }) as never
  );

  assert.equal(result.terminal_status, "failed");
  assert.equal(events[events.length - 1].type, "run.failed");
});

test("replays assistant history without malforming the transcript", async () => {
  // Pi has no engine-level session, so every turn replays the transcript, and
  // an assistant message in it needs the full AgentMessage shape
  // (api/provider/model/usage/stopReason), not just role and content.
  //
  // This documents the multi-turn path but is NOT the guard for that shape: a
  // stub streamFn never serializes the transcript for a provider, so it passes
  // either way (verified). The actual guard is the removal of the `as never`
  // cast on the prompt mapping, which makes the compiler reject the partial
  // object outright.
  const root = workspace();
  const init = initPayload(root, {
    messages: [
      { role: "user", content: "上一轮问题" },
      { role: "assistant", content: "上一轮回答" },
      { role: "user", content: "这一轮问题" },
    ],
  });

  const { events, result } = await runCell(init, textStreamFactory("second turn answer"));

  assert.equal(result.terminal_status, "success", `multi-turn run failed: ${result.error ?? ""}`);
  assert.equal(events[events.length - 1].type, "run.completed");
});

test("the transcript handed to the agent keeps assistant turns as assistant", async () => {
  // Collapsing history to user messages would lose the turn structure the
  // model relies on, without failing anything visibly.
  const root = workspace();
  let seenRoles: string[] = [];

  const factory = () => ({
    model: { api: "faux", provider: "faux", id: "faux-1" } as never,
    streamFn: ((_model: unknown, context: { messages?: Array<{ role: string }> }) => {
      seenRoles = (context?.messages ?? []).map((m) => m.role);
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content: [{ type: "text" as const, text: "ok" }],
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "done", reason: "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });

  await runCell(
    initPayload(root, {
      messages: [
        { role: "user", content: "q1" },
        { role: "assistant", content: "a1" },
        { role: "user", content: "q2" },
      ],
    }),
    factory as never
  );

  assert.deepEqual(seenRoles, ["user", "assistant", "user"]);
});

test("a failing tool call surfaces as is_error in the event stream", async () => {
  // The only test that drives a tool call through the *real* agent loop.
  //
  // AgentToolResult has no isError field: the loop sets isError solely from a
  // thrown execute. A tool that *returned* { isError: true } therefore produced
  // tool.completed with is_error:false (the flag ended up buried inside
  // output.isError, which nothing reads), and the chat rendered a failed
  // command as a successful one.
  //
  // Boundary denials were never affected -- beforeToolCall blocks those before
  // execute runs -- so this has to exercise a failure only execute can see: a
  // non-zero shell exit.
  const root = workspace();
  let turn = 0;

  const factory = () => ({
    model: { api: "faux", provider: "faux", id: "faux-1" } as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      const wantsTool = turn++ === 0;
      const content = wantsTool
        ? [
            {
              type: "toolCall" as const,
              id: "tc-1",
              name: "Bash",
              arguments: { command: "echo before-failure; exit 7" },
            },
          ]
        : [{ type: "text" as const, text: "done" }];
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content,
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: wantsTool ? ("toolUse" as const) : ("stop" as const),
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "done", reason: wantsTool ? "toolUse" : "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });

  const { events } = await runCell(initPayload(root), factory as never);

  const completed = events.find((e) => e.type === "tool.completed");
  assert.ok(completed, "expected a tool.completed event");
  assert.equal(completed.payload.is_error, true, "a failed command must be reported as an error");
  assert.equal(completed.payload.tool_name, "Bash");
  // The output has to survive the failure, or the model cannot diagnose it.
  assert.match(JSON.stringify(completed.payload.output), /before-failure/);
});

test("records token usage in the shape the rest of the stack reads", async () => {
  // usage.updated is declared in the contract and handled by both the Python
  // adapter and the frontend reducer, but nothing emitted it, so a Pi turn
  // recorded no token usage at all while an SDK turn did.
  //
  // The shape matters as much as the event: the frontend's normalizeUsage reads
  // Anthropic-style input_tokens / output_tokens / cache_*, so emitting pi-ai's
  // camelCase Usage verbatim would leave the display blank anyway.
  const root = workspace();
  const factory = () => ({
    model: { api: "faux", provider: "faux", id: "faux-1" } as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content: [{ type: "text" as const, text: "ok" }],
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: {
            input: 120,
            output: 45,
            cacheRead: 7,
            cacheWrite: 3,
            totalTokens: 165,
            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
          },
          stopReason: "stop" as const,
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "done", reason: "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });

  const { events } = await runCell(initPayload(root), factory as never);

  const usageEvent = events.find((e) => e.type === "usage.updated");
  assert.ok(usageEvent, "expected a usage.updated event");
  assert.deepEqual(usageEvent.payload.usage, {
    input_tokens: 120,
    output_tokens: 45,
    cache_read_input_tokens: 7,
    cache_creation_input_tokens: 3,
  });
});

test("a turn without usage emits no usage event rather than an empty one", async () => {
  const root = workspace();
  const { events } = await runCell(initPayload(root), textStreamFactory("hi"));
  const usageEvents = events.filter((e) => e.type === "usage.updated");
  // The stub's usage uses inputTokens/outputTokens, which are not pi-ai's Usage
  // fields, so nothing usable is present and no event should be fabricated.
  assert.equal(usageEvents.length, 0);
});

test("cell properly initializes with history and executes single prompt", async () => {
  const root = workspace();
  const { events, result } = await runCell(
    initPayload(root, {
      history: [
        { role: "user", content: "previous question" },
        { role: "assistant", content: "previous answer" },
      ],
      prompt: "current question",
      skills: [],
      mcp_servers: [],
    }),
    textStreamFactory("current answer")
  );

  assert.equal(result.terminal_status, "success");
  assert.equal(events[0].type, "run.started");
  assert.equal(events[events.length - 1].type, "run.completed");
  const answerDeltas = events.filter((e) => e.type === "content.delta");
  assert.ok(answerDeltas.length > 0);
});



test("the event stream carries no liveness-only events", async () => {
  // tool.progress was removed from AgentEventType: nothing on either side
  // rendered it, so it only filled the record table. The type system now
  // rejects emitting it at all; this guards the runtime shape as well.
  const init = initPayload(workspace());
  const { events } = await runCell(init, textStreamFactory("hello"));
  const types = events.map((e) => String(e.type));
  assert.equal(types.includes("tool.progress"), false);
  assert.ok(types.includes("run.completed"), "a real terminal event still arrives");
});

test("a heartbeat sink is optional and does not change the run outcome", async () => {
  // Liveness moved to a protocol frame. The sink must be wired without becoming
  // required, or every existing caller breaks.
  const init = initPayload(workspace());
  const events: NeutralAgentEvent[] = [];
  const beats: Array<Record<string, unknown>> = [];
  const cell = new Cell(textStreamFactory("hello") as never);

  const result = await cell.run(init, (e) => events.push(e), (d) => beats.push(d));

  assert.equal(result.terminal_status, "success");
  assert.equal(beats.length, 0, "no slow tool in this run, so no beat is due");
});

test("folding keeps the tool's own details alongside the fold provenance", async () => {
  // Registering only the content array dropped `details` for exactly the large
  // results the UI registry exists to protect, so the transcript kept the full
  // payload but lost what the tool said about it — and the fold provenance that
  // replaced it looked like the whole story.
  //
  // Drives a real Read over a file past the fold threshold, so the assertion is
  // about what afterToolCall actually registers, not about a copy hand-placed
  // into the registry.
  const root = workspace();
  const big = "x".repeat(64 * 1024);
  fs.writeFileSync(path.join(root, "big.txt"), big);
  let turn = 0;

  const factory = () => ({
    model: { api: "faux", provider: "faux", id: "faux-1" } as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      const wantsTool = turn++ === 0;
      const content = wantsTool
        ? [
            {
              type: "toolCall" as const,
              id: "tc-fold",
              name: "Read",
              arguments: { file_path: path.join(root, "big.txt") },
            },
          ]
        : [{ type: "text" as const, text: "done" }];
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content,
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: wantsTool ? ("toolUse" as const) : ("stop" as const),
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "done", reason: wantsTool ? "toolUse" : "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });

  const { events } = await runCell(initPayload(root), factory as never);

  const completed = events.find((e) => e.type === "tool.completed");
  assert.ok(completed, "expected a tool.completed event");
  const meta = completed.payload.output_meta as Record<string, unknown>;

  // The tool's own report survives.
  assert.equal(typeof meta.byte_count, "number", "Read's byte count must survive folding");
  assert.equal(meta.truncated, false);
  // And the fold provenance sits beside it rather than on top of it.
  const fold = meta.fold as Record<string, unknown>;
  assert.equal(typeof fold.result_ref, "string");
  assert.equal(fold.original_bytes, 64 * 1024);
  // The transcript still holds the whole payload, not the digest.
  assert.match(JSON.stringify(completed.payload.output), /x{2000}/);
});
