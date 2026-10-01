# Pi 运行时模型输出上限设计

日期：2026-10-01

## Current state

模型配置页为每个模型提供“最大输出 tokens”（`models[].max_output_tokens`），但 Pi runtime 不读取它：
`stream-fn-resolver.ts` 的 `resolveModel()` 把每次回复的输出上限写死为 8192。

一轮回复因达到输出上限被截断时，pi-ai 返回 `stopReason: "length"`。这样的回复不带工具调用，
Agent 循环随之结束，Cell 看不到 `errorMessage`，把运行记为 `success`。

## Problem

会先长篇思考的模型（例如 DeepSeek `deepseek-v4-pro`）可能在一轮里用完 8192 tokens，还没调用工具就被截断。
运行被记为成功，但没有产出任何结果。在 OntoFoundry 的建模接入中，这表现为任务“完成”却缺少结果文件，
而 DataAgent 侧没有任何失败信号。

## Scope

- Pi runtime 使用所选模型配置的 `max_output_tokens`；未配置时仍沿用 8192。
- 回复因输出上限被截断的运行判为失败（`PI_OUTPUT_TRUNCATED`），并在提示中指向该配置项。
- 不提高未配置模型的默认值：平台无法知道每个模型的真实上限，默认值过高会让上限更低的模型直接拒绝请求。
- 不改 Claude Agent SDK 引擎路径，不改数据库结构。

## Interfaces

`cell.init` 帧的 `model` 增加可选字段：

```json
{
  "provider_id": "deepseek",
  "api_format": "/v1/messages",
  "model_id": "deepseek-v4-pro",
  "max_output_tokens": 32000
}
```

仅在模型配置了正整数时下发；字段缺失时 Cell 使用 `DEFAULT_MAX_OUTPUT_TOKENS`（8192）。

- `resolve_runtime_provider_selection()` 返回值增加 `max_output_tokens`（`int | None`），
  取自 provider 的 `models[]` 中与所选模型同 id 的条目。
- 截断的终止事件为 `run.failed`，`error_code` 是 `PI_OUTPUT_TRUNCATED`，
  `message` 写明本次上限并提示调大“最大输出 tokens”。

## Tradeoffs

- 判断依据是最后一条 assistant 消息的 `stopReason === "length"`。中途某轮被截断、之后又继续的情况，
  沿用原有的循环行为，不额外干预。
- 后端与 runtime 需要同步发布。旧后端不发送该字段时，新 runtime 退回 8192，行为与现在一致；
  新后端搭配旧 runtime 时，未知字段被忽略。
