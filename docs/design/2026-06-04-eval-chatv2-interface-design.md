# DataAgent Eval — Chat V2 Interface Design

**Date:** 2026-06-04
**Goal:** Switch the DataAgent online-evaluation runners off the V1 "magic event"
task stream so the eval tool observes each run through Chat V2. Rather than
re-deriving blocks from the raw SDK-event stream, the runners consume the
**server-projected history blocks** that Chat V2 already persists, so the eval sees
exactly what the product renders/persists — and the projection logic stays in one
backend implementation instead of being copied into the eval tool.
**Tech Stack:** Evaluation tooling (`tools/dataagent-evals/*`, stdlib-only Python).
DataAgent backend is the system under test through `/api/v1/nl2sql/*`; no backend
change is owned by this design.

## Scope

In scope:

- `tools/dataagent-evals/builtin/run.py` — stdlib evaluation runner.
- `tools/dataagent-evals/deepeval/run.py` — DeepEval-driven runner.
- The per-case run evidence (tool names, SQL outputs, chart outputs, tool-event
  summary, token usage) now derived from the Chat V2 server-projected assistant
  message blocks instead of magic events.
- Shared projection contract fixtures + tests locking the two remaining projection
  implementations (`dataagent/contracts/sdk-block-projection/cases.json`,
  `dataagent/dataagent-backend/tests/test_sdk_block_projection_contract.py`,
  `dataagent/dataagent-frontend/.../sdkBlockProjection.contract.spec.js`).
- The associated regression tests (`tests/test_run_dataagent_evals.py`,
  `tests/test_dataagent_deepeval_evals.py`).

Out of scope:

- Topic creation (`POST /topics`) and task submission
  (`POST /tasks/deliver-message`) — already shared with Chat V2, unchanged.
- Backend SDK-record persistence/projection itself (covered by
  `2026-05-31-chat-v2-design.md`); this design consumes its output, it does not
  change the projection.
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

### Interface switch — consume projected history, don't re-project

The eval runs a case, polls to a terminal state, then reads the run's evidence from
the **already-projected** assistant message in `GET /topics/{id}/messages`. Each
assistant message Chat V2 persists carries:

- `content` — the final assistant answer (already used).
- `blocks` — the server-projected `thinking` / `main_text` / `tool_use` blocks,
  produced by `topic_task_store._project_sdk_records` (the same projection the Chat
  V2 history surface renders).
- `usage` — per-message token usage.

So the eval no longer needs the raw SDK-event stream, and no longer carries its own
copy of the projection. `_poll_task` is reduced to a status-only poll: poll
`GET /tasks/{id}`, follow recovered/replacement tasks (`task_recovered`), honour the
deadline, and return `(task, errors)`. The recovered-task detection is unchanged.
After the terminal status the runner reads `/messages` once and selects the last
assistant message for the final task id (`_final_assistant_message`).

This removes the per-poll event paging entirely (fewer round-trips) and is strictly
more Chat-V2-aligned: the eval evidence is now byte-for-byte the projection the
product persists, not a re-derivation of it.

### Evidence derivation (from the projected message blocks)

`blocks = message["blocks"]` (projected block shape:
`{type, text?, tool_id?, tool_name?, input?, output?, is_error?}`).

- `_collect_tool_names(blocks)` — `tool_name` of each `tool_use` block, de-duped.
- `_extract_sql_outputs(blocks, final_answer)` — SQL from `tool_use` block `input`
  (`sql` / `query` keys) and structured `output`, plus fenced ```sql blocks in the
  final answer. Reuses the existing `_looks_like_sql` / `_normalise_sql` helpers.
- `_extract_chart_outputs(blocks)` — chart specs embedded in `tool_use`
  input/output.
- `_summarize_tool_events(blocks)` — `[{ seq_id, tool_name, input, output }]` where
  `seq_id` is the tool's ordinal among `tool_use` blocks (the projected blocks are
  already in render order; no raw record seq is needed). Bounded/truncated for the
  judge payload (builtin runner).
- `_collect_usage(task, message)` — merges `task.usage` with the assistant
  `message.usage`.

There is no fallback to the magic-event endpoint or the raw `/sdk-events` stream —
one primary path per repo working rules.

### Projection contract (collapsing three copies to two)

Removing the eval's `_project_sdk_blocks` leaves exactly two projection
implementations that must agree, and they are inherently coupled (live stream vs.
persisted history rendering the same answer):

- backend `topic_task_store._project_sdk_records` (Python)
- frontend `v2StreamParser.processV2Record` (JS)

A shared golden-fixture file, `dataagent/contracts/sdk-block-projection/cases.json`,
encodes `records → expected canonical blocks` for representative cases (text,
thinking, tool_use success/error, dropped-empty, multi-turn flatten, malformed input
JSON). Canonical block shape:
`{kind: 'thinking'|'text'|'tool_use', text?, tool_name?, input?, output?, is_error?}`.
Two thin contract tests normalize each implementation's output to that canonical
shape and assert equality against the same fixtures:

- `dataagent/dataagent-backend/tests/test_sdk_block_projection_contract.py`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/sdkBlockProjection.contract.spec.js`

## Interfaces / Data Model

Eval tool → DataAgent (run-evidence sourcing only):

| Before (V1 magic events) | After (Chat V2 projected history) |
| --- | --- |
| `GET /tasks/{id}/events?after_seq=&limit=` (per-poll) | `GET /tasks/{id}` status poll only |
| evidence from `events[].data.{tool_name,input,output}` | evidence from assistant `message.blocks` in `GET /topics/{id}/messages` |
| `_poll_task → (task, records, errors)` | `_poll_task → (task, errors)` |

No schema, deployment, or report-format change. Exit codes and gate semantics are
unchanged.

## Risks / Alternatives

- **Projection now in two places, not three.** The eval no longer projects; it
  consumes the backend projection. The remaining FE/backend pair is locked by the
  shared contract fixtures so live streaming and persisted history cannot silently
  diverge.
- **Charts embedded in answer text are not extracted.** `_extract_chart_outputs`
  reads `tool_use` input/output only; charts emitted as fenced specs inside
  `main_text` are not surfaced as `chart_outputs`. This matches the prior
  magic-event behavior (it also keyed off event fields), so it is not a regression.
- **Evidence requires the assistant message to be projected.** Blocks are read after
  the task is terminal, when `da_agent_sdk_record` rows are complete, so the
  projection is whole. Alternative considered and rejected: keep polling
  `/sdk-events` and re-project in the eval — that is the third copy this change
  removes.
- **Backout** is to revert the runners (the magic-event and `/sdk-events` endpoints
  both still exist).

## Verification

- `pytest tests/test_run_dataagent_evals.py tests/test_dataagent_deepeval_evals.py`
  with fakes updated to serve assistant `message.blocks` from `/messages`.
- Projection contract: `pytest dataagent/dataagent-backend/tests/test_sdk_block_projection_contract.py`
  and `vitest run .../sdkBlockProjection.contract.spec.js` against the shared
  fixtures.
- Dry-run path unchanged (no service calls).
- End-to-end smoke (per AGENTS.md intelligent-query smoke method) remains the
  recommended full validation: run one real case and confirm tool/SQL evidence is
  populated from the projected message blocks.
