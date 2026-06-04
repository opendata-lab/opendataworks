# DataAgent Eval — Chat V2 Interface Design

**Date:** 2026-06-04
**Goal:** Switch the DataAgent online-evaluation runners from the V1 "magic event"
task stream to the Chat V2 SDK-event interface, so the eval tool observes each run
through the same protocol the shipped Chat V2 chat surface uses.
**Tech Stack:** Evaluation tooling (`tools/dataagent-evals/*`, stdlib-only Python).
DataAgent backend is the system under test through `/api/v1/nl2sql/*`; no backend
change is owned by this design.

## Scope

In scope:

- `tools/dataagent-evals/builtin/run.py` — stdlib evaluation runner.
- `tools/dataagent-evals/deepeval/run.py` — DeepEval-driven runner.
- The per-case run evidence (tool names, SQL outputs, chart outputs, tool-event
  summary, token usage) now derived from Chat V2 SDK records instead of magic events.
- The associated regression tests (`tests/test_run_dataagent_evals.py`,
  `tests/test_dataagent_deepeval_evals.py`).

Out of scope:

- Topic creation (`POST /topics`) and task submission
  (`POST /tasks/deliver-message`) — already shared with Chat V2, unchanged.
- Final assistant answer sourcing from `GET /topics/{id}/messages` `content` —
  the persisted assistant message, unchanged.
- Backend SDK-record persistence/projection (covered by
  `2026-05-31-chat-v2-design.md`).
- Judge model call, scoring gates, report shape — unchanged.

## Current State

Both runners drive a run, then read evidence from the V1 magic-event stream:

- `GET /api/v1/nl2sql/tasks/{task_id}/events?after_seq=&limit=` returns
  `{ events: [{ seq_id, event_type, data: { tool_name, input, output, ... } }], next_after_seq }`.
- `_poll_task` accumulates those magic events; `_collect_tool_names`,
  `_extract_sql_outputs`, `_extract_chart_outputs`, `_summarize_tool_events`,
  `_collect_usage` read structured `tool_name` / `input` / `output` fields off them.

Chat V2 (`docs/design/2026-05-31-chat-v2-design.md`) instead consumes the native
SDK record stream and reconstructs blocks through one model:

- `GET /api/v1/nl2sql/tasks/{task_id}/sdk-events?after_id=&limit=` returns
  `{ records: [{ seq_id, turn_index, record_type, event_type, data }], next_after_id, has_more, task_status }`.
- `record_type`: `stream` (raw Anthropic event in `data`), `tool_result`
  (`data.tool_use_id` / `data.content` / `data.is_error`), `done`, `error`.
- Backend `core/topic_task_store._project_sdk_records` and frontend
  `v2StreamParser.js` replay those records into blocks
  (`thinking` / `main_text` / `tool_use`).

## Problem

The eval tool observes runs through a different interface than the product chat
surface. The magic-event path is V1's transient adapter; Chat V2 is the path the
product renders and persists. Evaluating through magic events can diverge from what
users actually see (tool blocks, inputs/outputs, ordering), and keeps the eval tool
coupled to the legacy adapter the repo is consolidating away from.

## Design

### Interface switch

Replace the magic-event polling in `_poll_task` with SDK-event paging:

`GET /api/v1/nl2sql/tasks/{task_id}/sdk-events?after_id=<cursor>&limit=500`

`_poll_task` keeps the same outer contract — poll task status, accumulate the
run's records, follow recovered/replacement tasks (`task_recovered`), honour the
deadline, and return `(task, records, errors)` — but `records` are now SDK records
keyed by `seq_id` with a `next_after_id` cursor and `has_more` paging drained per
poll. The recovered-task detection is status/error based and is unchanged.

### Block projection (shared vocabulary)

A new local `_project_sdk_blocks(records)` mirrors backend `_project_sdk_records`
and frontend `v2StreamParser.js`, returning the ordered blocks plus accumulated
usage:

```
block = {
  type: 'thinking' | 'main_text' | 'tool_use',
  text,                       # thinking / main_text
  tool_id, tool_name,         # tool_use
  input,  output, is_error,   # tool_use (input parsed from input_json_delta)
  seq_id,                     # originating record seq for ordering
}
usage = { input_tokens?, output_tokens?, ... }   # from message_start / message_delta
```

### Evidence derivation (from blocks, not magic events)

- `_collect_tool_names(blocks)` — `tool_name` of each `tool_use` block, de-duped.
- `_extract_sql_outputs(blocks, final_answer)` — SQL from `tool_use` block `input`
  (`sql` / `query` keys) and structured `output`, plus fenced ```sql blocks in the
  final answer. Reuses the existing `_looks_like_sql` / `_normalise_sql` helpers.
- `_extract_chart_outputs(blocks)` — chart specs embedded in `tool_use`
  input/output.
- `_summarize_tool_events(blocks)` — `[{ seq_id, tool_name, input, output }]`,
  bounded/truncated for the judge payload (builtin runner).
- `_collect_usage(task, messages, stream_usage)` — merges projected stream usage
  with `task.usage` and message usage (single primary source: the SDK stream).

The final assistant answer still comes from `GET /topics/{id}/messages` `content`
(unchanged); only the run-evidence source moves to the Chat V2 interface. There is
no fallback to the magic-event endpoint — one primary path per repo working rules.

## Interfaces / Data Model

Eval tool → DataAgent (changed call only):

| Before (V1 magic events) | After (Chat V2 SDK events) |
| --- | --- |
| `GET /tasks/{id}/events?after_seq=&limit=` | `GET /tasks/{id}/sdk-events?after_id=&limit=` |
| response `events[]`, `next_after_seq` | response `records[]`, `next_after_id`, `has_more` |
| event `{ seq_id, event_type, data:{tool_name,input,output} }` | record `{ seq_id, record_type, event_type, data }` |

No schema, deployment, or report-format change. Exit codes and gate semantics are
unchanged.

## Risks / Alternatives

- **Projection duplicated in three places.** FE `v2StreamParser.js`, backend
  `_project_sdk_records`, and now the eval `_project_sdk_blocks` must stay in
  sync. Mitigated by keeping the eval projection minimal (only the fields the eval
  needs) and covering it with regression tests.
- **SQL now from tool input, not magic `output.sql`.** SDK `tool_use` input carries
  the executed SQL; the magic event surfaced it under `output.sql`. The extractor
  reads both block `input` and `output`, so structured SQL is still captured.
- **No magic-event fallback.** Intentional, per "one verified primary path". Backout
  is to revert the runners (the magic-event endpoint still exists for V1).

## Verification

- `pytest tests/test_run_dataagent_evals.py tests/test_dataagent_deepeval_evals.py`
  with fakes updated to serve `/sdk-events` records.
- Dry-run path unchanged (no service calls).
- End-to-end smoke (per AGENTS.md intelligent-query smoke method) remains the
  recommended full validation: run one real case and confirm tool/SQL evidence is
  populated from SDK records.
</content>
</invoke>
