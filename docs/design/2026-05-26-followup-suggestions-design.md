# Follow-up Suggestions Design

## Current State

The intelligent-query chat UI renders the "猜你想问" area from frontend-only keyword rules in `NL2SqlChat.vue`. The rules inspect the latest successful assistant answer for SQL or chart-related keywords and then show fixed suggestions.

This produces stable UI behavior but does not actually infer the next question from the previous answer. It also puts answer-understanding logic in the frontend, where it cannot use the configured DataAgent provider or the conversation/task context.

## Problem

Follow-up suggestions should be derived from the previous user question and the latest assistant answer, while preserving the existing chat contracts:

- Do not change topic message response fields.
- Do not change task status responses.
- Do not add SSE events.
- Do not add database tables or columns in v1.

## Solution

Add an independent follow-up suggestion endpoint:

`POST /api/v1/nl2sql/topics/{topic_id}/messages/{message_id}/followup-suggestions`

The endpoint validates that the target message belongs to the topic, is visible, is an assistant message, is finished, and has non-empty visible answer content. It then locates the previous user message in the same topic and asks a lightweight generator for 2-3 Chinese follow-up questions.

The generator uses the same provider/model recorded on the original task, disables tools, uses a bounded 2-turn SDK budget, and applies a short timeout. Model output is parsed as JSON and normalized by trimming bullets, removing duplicates, limiting length, and filtering the original question. If generation fails, the endpoint returns backend fallback suggestions or an empty list with a non-error source.

## Interfaces

Response shape:

```json
{
  "topic_id": "topic_x",
  "message_id": "msg_x",
  "suggestions": ["查看异常波动对应的明细", "按业务维度拆解这个趋势"],
  "source": "generated"
}
```

`source` values:

- `generated`: model-generated and normalized suggestions.
- `fallback`: backend fallback suggestions after generation failure or unusable model output.
- `empty`: no suggestions available.

Existing topic, message, task, and SSE interfaces remain unchanged.

## Frontend Behavior

`NL2SqlChat.vue` requests suggestions only for the latest successful assistant message. Suggestions are cached in component state by message id for the current page lifetime. Empty or failed responses render no "猜你想问" block. Clicking a suggestion continues to use the existing `handleSuggestion -> deliverMessage` flow.

## Tradeoffs

This v1 intentionally avoids server-side persistence. Refreshing the page can regenerate suggestions for the same message, and suggestions may vary slightly between calls. That is acceptable for the first version because it keeps the change additive and avoids schema or migration risk.
