/**
 * Asking the user mid-run: the broker round trip, the two tools, and plan mode
 * holding write tools until a plan is approved.
 */

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";
import { Cell } from "../src/kernel/cell.js";
import { InteractionBroker } from "../src/interaction/interaction-broker.js";
import { formatAnswers } from "../src/interaction/interaction-tools.js";
import type { CellInitPayload, NeutralAgentEvent } from "../src/protocol/frames.js";

function initPayload(root: string, overrides: Partial<CellInitPayload> = {}): CellInitPayload {
  return {
    run_id: "task-1",
    task_id: "task-1",
    topic_id: "topic-1",
    system_prompt: "sys",
    messages: [{ role: "user", content: "hi" }],
    model: { provider_id: "faux", api_format: "/v1/messages", model_id: "faux-1" },
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
    limits: { total_timeout_seconds: 30, idle_timeout_seconds: 10, max_turns: 30, max_tool_calls: 50, bash_timeout_seconds: 30 },
    ...overrides,
  };
}

/** One scripted tool call per turn, then a final text answer. */
function scriptedFactory(calls: Array<{ name: string; arguments: Record<string, unknown> }>) {
  let turn = 0;
  return () => ({
    model: { api: "faux", provider: "faux", id: "faux-1" } as never,
    streamFn: (() => {
      const stream = createAssistantMessageEventStream();
      const call = calls[turn];
      const index = turn++;
      const content = call
        ? [{ type: "toolCall" as const, id: `tc-${index}`, name: call.name, arguments: call.arguments }]
        : [{ type: "text" as const, text: "done" }];
      queueMicrotask(() => {
        const message = {
          role: "assistant" as const,
          content,
          api: "faux" as const,
          provider: "faux",
          model: "faux-1",
          usage: { inputTokens: 1, outputTokens: 1, totalTokens: 2 },
          stopReason: call ? ("toolUse" as const) : ("stop" as const),
          timestamp: Date.now(),
        };
        stream.push({ type: "start", partial: message as never });
        stream.push({ type: "done", reason: call ? "toolUse" : "stop", message: message as never });
        stream.end();
      });
      return stream;
    }) as never,
  });
}

/** A broker that answers every request with `reply(kind, payload)`. */
function answeringBroker(reply: (kind: string, payload: Record<string, unknown>) => Record<string, unknown>) {
  const requests: Array<Record<string, unknown>> = [];
  const broker: InteractionBroker = new InteractionBroker((frame) => {
    requests.push(frame);
    queueMicrotask(() =>
      broker.resolve(String(frame.request_id), reply(String(frame.kind), frame.payload as Record<string, unknown>))
    );
  });
  return { broker, requests };
}

async function run(init: CellInitPayload, factory: unknown, broker: InteractionBroker) {
  const events: NeutralAgentEvent[] = [];
  const result = await new Cell(factory as never).run(init, (event) => events.push(event), () => {}, broker);
  return { events, result };
}

function workspace(t: { after: (fn: () => void) => void }): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "odw-ask-"));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  return dir;
}

const QUESTIONS = [
  {
    question: "区域如何组织？",
    header: "区域",
    options: [
      { label: "区域层级树", description: "厂区-车间-产线", recommended: true },
      { label: "单层区域清单" },
    ],
  },
];

test("AskUserQuestion parks on the broker and hands the model the user's answer", async (t) => {
  const { broker, requests } = answeringBroker(() => ({
    answers: [{ question: "区域如何组织？", selected: ["区域层级树"], other: "先只建两级" }],
  }));
  const { events, result } = await run(
    initPayload(workspace(t)),
    scriptedFactory([{ name: "AskUserQuestion", arguments: { questions: QUESTIONS } }]),
    broker
  );
  assert.equal(result.terminal_status, "success");
  assert.deepEqual(requests.map((r) => [r.kind, r.request_id]), [["question", "tc-0"]]);
  assert.deepEqual((requests[0].payload as { questions: unknown }).questions, QUESTIONS);
  const completed = events.find((e) => e.type === "tool.completed");
  assert.match(JSON.stringify(completed?.payload.output), /区域层级树；补充：先只建两级/);
});

test("plan mode refuses listed tools until ExitPlanMode is approved", async (t) => {
  const { broker, requests } = answeringBroker((kind) => (kind === "plan" ? { decision: "allow" } : {}));
  const write = { name: "Bash", arguments: { command: "echo wrote" } };
  const { events } = await run(
    initPayload(workspace(t), { permission_mode: "plan", plan_denied_tools: ["Bash"] }),
    scriptedFactory([write, { name: "ExitPlanMode", arguments: { plan: "# 计划\n- 建任务" } }, write]),
    broker
  );
  const denied = events.filter((e) => e.type === "tool.denied");
  assert.equal(denied.length, 1, "only the call before approval is refused by plan mode");
  assert.match(String(denied[0].payload.reason), /计划模式/);
  assert.deepEqual(requests.map((r) => r.kind), ["plan"]);
});

test("a rejected plan returns the user's revision to the model and keeps the hold", async (t) => {
  const { broker } = answeringBroker(() => ({ decision: "deny", message: "用户未批准该计划，要求先调整：去掉巡检计划" }));
  const write = { name: "Bash", arguments: { command: "echo wrote" } };
  const { events } = await run(
    initPayload(workspace(t), { permission_mode: "plan", plan_denied_tools: ["Bash"] }),
    scriptedFactory([{ name: "ExitPlanMode", arguments: { plan: "# 计划" } }, write]),
    broker
  );
  const plan = events.find((e) => e.type === "tool.completed" && e.payload.tool_name === "ExitPlanMode");
  assert.match(JSON.stringify(plan?.payload.output), /去掉巡检计划/);
  assert.equal(events.filter((e) => e.type === "tool.denied").length, 1);
});

test("ExitPlanMode is only offered in plan mode", async (t) => {
  const { broker } = answeringBroker(() => ({ decision: "allow" }));
  const { events } = await run(
    initPayload(workspace(t)),
    scriptedFactory([{ name: "ExitPlanMode", arguments: { plan: "# 计划" } }]),
    broker
  );
  const completed = events.find((e) => e.type === "tool.completed");
  assert.equal(completed?.payload.is_error, true);
});

test("a pending request rejects when the run is aborted", async () => {
  const broker = new InteractionBroker(() => {});
  const controller = new AbortController();
  const pending = broker.request("question", "r1", {}, controller.signal);
  controller.abort();
  await assert.rejects(pending, /aborted/);
  assert.equal(broker.resolve("r1", {}), false);
});

test("answers read naturally, including skips and the timeout fallback", () => {
  assert.match(formatAnswers([]), /跳过了全部问题/);
  const text = formatAnswers([
    { question: "区域如何组织？", selected: ["区域层级树"], auto: true },
    { question: "要审批吗？", selected: [], skipped: true, auto: true },
  ]);
  assert.match(text, /已按推荐选项继续/);
  assert.match(text, /1\. 区域如何组织？ → 区域层级树/);
  assert.match(text, /2\. 要审批吗？ → 跳过（未作答）/);
});
