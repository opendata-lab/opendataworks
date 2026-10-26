/**
 * AskUserQuestion and ExitPlanMode for the Pi engine.
 *
 * Same names and inputs as the Claude Code built-ins, so both engines produce
 * the same cards and the same records. The tool result is what the model reads
 * back: the user's picks, or the revision the user asked for.
 */

import { Type } from "@earendil-works/pi-ai";
import type { InteractionBroker, InteractionResult } from "./interaction-broker.js";

const OPTION = Type.Object({
  label: Type.String({ description: "选项标题，1～5 个词。" }),
  description: Type.Optional(Type.String({ description: "一句话说明选这个会怎样。" })),
  recommended: Type.Optional(Type.Boolean({ description: "你推荐的选项；每题最多一个。用户长时间不回复时按它继续。" })),
});

const QUESTION = Type.Object({
  question: Type.String({ description: "完整的问题，以问号结尾。" }),
  header: Type.Optional(Type.String({ description: "很短的标签，最多 12 个字。" })),
  options: Type.Array(OPTION, { minItems: 2, maxItems: 4 }),
  multiSelect: Type.Optional(Type.Boolean()),
});

const ASK_SCHEMA = Type.Object({
  questions: Type.Array(QUESTION, { minItems: 1, maxItems: 4 }),
});

const PLAN_SCHEMA = Type.Object({
  plan: Type.String({ description: "Markdown 格式的计划，首行为 `# 标题`。" }),
});

interface Answer {
  question?: string;
  header?: string;
  selected?: string[];
  other?: string;
  skipped?: boolean;
  auto?: boolean;
}

function errorResult(text: string) {
  return { content: [{ type: "text" as const, text }], details: { error: text }, isError: true };
}

/** The user's answers as the model should read them. */
export function formatAnswers(answers: Answer[]): string {
  if (!answers.length) {
    return "用户跳过了全部问题，未作答。请按你的判断继续，并在回答中说明所做的假设。";
  }
  const auto = answers.some((answer) => answer.auto);
  const lines = answers.map((answer, index) => {
    const title = answer.question || answer.header || `问题 ${index + 1}`;
    const picks = [...(answer.selected ?? [])];
    const other = String(answer.other ?? "").trim();
    const parts = [picks.length ? picks.join("、") : answer.skipped || !other ? "跳过（未作答）" : ""];
    if (other) {
      parts.push(`补充：${other}`);
    }
    return `${index + 1}. ${title} → ${parts.filter(Boolean).join("；")}`;
  });
  const header = auto ? "用户长时间未回复，已按推荐选项继续：" : "用户的回答：";
  return `${header}\n${lines.join("\n")}`;
}

function failure(result: InteractionResult): string | null {
  return typeof result.error === "string" && result.error ? result.error : null;
}

export function createAskUserQuestionTool(broker: InteractionBroker) {
  return {
    name: "AskUserQuestion",
    label: "AskUserQuestion",
    description:
      "向用户提 1～4 个选择题并等待回答。只在需要用户做取舍、且无法从材料推断时使用；" +
      "每题 2～4 个选项，把你推荐的选项标 recommended。用户可选择、补充文字或跳过。",
    parameters: ASK_SCHEMA,
    execute: async (toolCallId: string, params: { questions: unknown[] }, signal?: AbortSignal) => {
      const result = await broker.request("question", toolCallId, { questions: params.questions }, signal);
      const error = failure(result);
      if (error) {
        return errorResult(`错误 [ASK_USER_FAILED]：${error}`);
      }
      const answers = Array.isArray(result.answers) ? (result.answers as Answer[]) : [];
      return { content: [{ type: "text" as const, text: formatAnswers(answers) }], details: { answers } };
    },
  };
}

/** ExitPlanMode; `onApproved` lifts the plan-mode tool restrictions. */
export function createExitPlanModeTool(broker: InteractionBroker, onApproved: () => void) {
  return {
    name: "ExitPlanMode",
    label: "ExitPlanMode",
    description:
      "计划模式下提交计划请用户批准。批准后才能写结果或调用写入类工具；" +
      "用户要求调整时，按意见修改后再次调用。",
    parameters: PLAN_SCHEMA,
    execute: async (toolCallId: string, params: { plan: string }, signal?: AbortSignal) => {
      const result = await broker.request("plan", toolCallId, { plan: params.plan }, signal);
      const error = failure(result);
      if (error) {
        return errorResult(`错误 [PLAN_REQUEST_FAILED]：${error}`);
      }
      if (result.decision === "allow") {
        onApproved();
        return {
          content: [{ type: "text" as const, text: "用户已批准计划。现在按计划执行。" }],
          details: { decision: "allow" },
        };
      }
      const message = String(result.message ?? "") || "用户未批准该计划。请继续完善后再调用 ExitPlanMode 提交。";
      return { content: [{ type: "text" as const, text: message }], details: { decision: "deny" } };
    },
  };
}
