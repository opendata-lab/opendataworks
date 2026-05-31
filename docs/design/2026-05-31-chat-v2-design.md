# Chat V2 Design

**Date:** 2026-05-31
**Goal:** Document the shipped "Chat V2" intelligent-query chat surface — an async-task + native-SDK-event-stream chat that renders the live Anthropic streaming protocol and reloaded history through one shared block model, reused by the floating widget.
**Tech Stack:** Frontend (Vue 3 / Pinia / Element Plus / Sass), DataAgent backend (FastAPI, MySQL, Alembic). No new backend work is owned by this design; it consumes the existing async task / `da_agent_sdk_record` persistence.

> Status: Implemented. This design is backfilled from the shipped code so the
> already-merged Chat V2 work has a single source of truth, per the repo
> Design & Plan Workflow.

## Scope

In scope:

- The Chat V2 full-page view `frontend/src/views/intelligence/NL2SqlChatV2.vue`,
  mounted by `IntelligentQueryView.vue` under the `chat-v2` tab alongside the
  retained V1 (`NL2SqlChat.vue`).
- The shared SDK-stream parser
  `frontend/src/views/intelligence/v2StreamParser.js`
  (`createChatState`, `processV2Record`, `blockToToolProp`).
- The Chat V2 API surface in `frontend/src/api/nl2sql.js` (`taskApi`/`topicApi`).
- The backend SDK-record persistence and server-side block projection consumed
  by Chat V2 (`da_agent_sdk_record`, `sdk_block_writer.py`,
  `topic_task_store.py`, `api/routes.py`).
- Widget alignment: `frontend/src/widget/WidgetChat.vue` imports the same parser
  and uses the same deliver-message → SDK-event-stream flow.
- Claude.ai-style layout (centered column, `clamp()` width, composer aligned to
  message width, typing indicator, new-chat landing with preset questions).

Out of scope:

- Backend task/coordinator internals (covered by
  `2026-03-12-nl2sql-async-background-design.md` and
  `2026-03-23-dataagent-magic-task-model-design.md`).
- Widget floating/drag/resize/allowlist behavior (separate widget designs).
- Removal of V1 — retained as a fallback tab (see Risks).

## Current State

V1 (`NL2SqlChat.vue`) consumes the "magic event" stream
(`da_agent_message`/`da_agent_chunk`) via the ~800-line `messageStream.js`
adapter, and reconstructs both the live turn and reloaded history from that
transient path.

The backend now persists each agent turn natively. Alongside the magic-event
tables, `core/sdk_block_writer.py` ingests the raw Anthropic SDK messages and
writes them to the `da_agent_sdk_record` table (Alembic
`20260529_000012_sdk_block_records.py`). `core/topic_task_store.py` stores
(`append_sdk_record`), lists (`list_sdk_records`), and projects
(`_project_sdk_records`) those rows back into rendered blocks.

Chat V2 was added as a second tab (`chat-v2`) in `IntelligentQueryView.vue`. It
streams the native SDK protocol live and reconstructs history from the persisted
SDK records, both through one block model. It shipped across ~20 commits with no
design doc.

## Problem

- V1 history hydration depended on the transient magic-event path and could
  diverge from what was streamed (e.g. pre-tool text mis-promoted to "thinking",
  missing/duplicated tool blocks after reload).
- Live rendering and history rendering used different code paths.
- The full-page chat and the floating widget had separate render logic, so fixes
  were applied twice and drifted.
- There was no documented block model or API contract for the chat surface.

## Design

### Components

| Layer | File | Responsibility |
| --- | --- | --- |
| Host | `frontend/src/views/intelligence/IntelligentQueryView.vue` | Tab nav; renders `<NL2SqlChatV2>` when `activeTab === 'chat-v2'`, else `<NL2SqlChat>` (V1) |
| Full-page view | `frontend/src/views/intelligence/NL2SqlChatV2.vue` | Block rendering, composer, new-chat landing, Claude.ai layout, stream + history wiring |
| Parser | `frontend/src/views/intelligence/v2StreamParser.js` | Canonical block model; `createChatState`, `processV2Record` (live), `blockToToolProp` |
| API client | `frontend/src/api/nl2sql.js` | `topicApi` / `taskApi` over `runtimeRequest` (prefix `/api/v1/nl2sql`) |
| SDK persistence | `core/sdk_block_writer.py`, `core/topic_task_store.py`, `api/routes.py`, Alembic `20260529_000012_sdk_block_records.py` | Write/list/project `da_agent_sdk_record`; serve SDK-event stream and projected message blocks |
| Widget | `frontend/src/widget/WidgetChat.vue` | Imports `v2StreamParser`; same deliver-message → SDK-event-stream flow |
| V1 (retained) | `frontend/src/views/intelligence/NL2SqlChat.vue` | Legacy magic-event chat, fallback tab |

### Block model (canonical contract)

`createChatState()` returns one state object per assistant message:

```
{
  turns: [ { turnIndex, blocks: [...], status } ],  // one turn per message_start…message_stop
  blocks: [...],                                     // flat lookup across turns
  status: 'idle' | 'streaming' | 'done' | 'error',
  usage: { input_tokens?, output_tokens? } | null,
  errorText: string | null
}
```

Each block:

```
{
  turnIndex, blockIndex,
  type: 'thinking' | 'text' | 'tool_use',
  content: string,            // thinking / text
  status: 'streaming' | 'done',
  id, name,                   // tool_use: tool_use_id and tool name
  inputJson: string,          // accumulates input_json_delta
  input: object | null,       // parsed at content_block_stop
  output: any | null,         // filled by a later tool_result record
  is_error: boolean
}
```

Charts/tables are not parser block types: text blocks may embed chart specs that
the view extracts (`chartSpec.js`) and renders through `ToolOutputRenderer`;
`tool_use` blocks render through `ToolOutputRenderer` via `blockToToolProp`.

### Send + live-stream lifecycle (`NL2SqlChatV2.vue`)

1. If no active topic: `topicApi.createTopic(title, { agent_id })`.
2. Append the local user message and an assistant placeholder whose `_v2state`
   is a fresh `createChatState()`.
3. `taskApi.deliverMessage({ topic_id, content, provider_id?, model?, agent_id? })`
   → `POST /api/v1/nl2sql/tasks/deliver-message`, returns `taskResp.task_id`.
4. `taskApi.streamSdkEvents(taskId, { onRecord, signal, afterId: 0 })` — a
   `fetch`-based SSE reader over
   `GET /api/v1/nl2sql/tasks/{taskId}/sdk-events/stream?after_id=`.
5. Each record → `processV2Record(_v2state, record)`, which mutates the state in
   place for Vue reactivity.
6. Cancellation aborts the `signal`; `taskApi.cancelTask(taskId)` →
   `POST /api/v1/nl2sql/tasks/{taskId}/cancel`.

### History hydration

1. `topicApi.getTopicMessages(topicId, { page: 1, page_size: 500, order: 'asc' })`
   → `GET /api/v1/nl2sql/topics/{topicId}/messages`, reading the response
   `items` field.
2. Each assistant message carries a `blocks` array that the backend already
   projected from `da_agent_sdk_record` via `topic_task_store._project_sdk_records`
   (replaying the same SDK protocol the live parser uses).
3. `buildV2StateFromStoredBlocks(item)` in the view rebuilds the same
   `createChatState`-shaped state from those stored blocks (handling the flat
   `thinking` / `main_text` / `tool_use` schema, with a legacy nested fallback).

This is the central correctness decision: **history is reconstructed from the
durable `da_agent_sdk_record` (server-projected into message `blocks`) using the
same block vocabulary as the live SDK stream**, so a reloaded conversation
matches what was streamed. This replaced the earlier magic-event reconstruction.

### Layout

Centered single content column (`max-width: 1280px`, fluid
`padding-inline: clamp(40px, 5%, 64px)`); sidebar grid `260px/300px 1fr`;
composer aligned to the messages width (`860px` on the landing page); typing
indicator and streaming cursor during a run; new-chat landing with greeting and
preset/agent suggested questions in a pill-style input bar.

## Interfaces / Data Model

API client (`frontend/src/api/nl2sql.js`, `runtimeRequest`, prefix `/api/v1/nl2sql`):

| Function | Method & path |
| --- | --- |
| `taskApi.deliverMessage(data)` | `POST /tasks/deliver-message` → `task_id` |
| `taskApi.cancelTask(taskId)` | `POST /tasks/{taskId}/cancel` |
| `taskApi.streamSdkEvents(taskId, {onRecord, signal, afterId})` | SSE `GET /tasks/{taskId}/sdk-events/stream?after_id=` (**used by V2**) |
| `taskApi.streamTaskEvents(taskId, {onEvent, afterSeq})` | SSE `GET /tasks/{taskId}/events/stream?after_seq=` (magic events, V1) |
| `taskApi.getTaskEvents(taskId, params)` | `GET /tasks/{taskId}/events` |
| `topicApi.getTopicMessages(topicId, params)` | `GET /topics/{topicId}/messages` (returns `items`, each assistant item has `blocks`) |
| `topicApi.listTopics(params)` | `GET /topics` |
| `topicApi.createTopic(title, data)` | `POST /topics` |

Parser entry points (`v2StreamParser.js`):

- `createChatState() -> state`.
- `processV2Record(state, record)` — mutates `state`. `record.record_type`:
  `stream` (native Anthropic event in `record.data`: `message_start`,
  `content_block_start|delta|stop`, `message_delta`, `message_stop`),
  `tool_result` (populates the matching `tool_use` block's `output`/`is_error`),
  `done` (terminal status), `error` (sets `errorText`).
- `blockToToolProp(block) -> { name, input, output, status, id, ... }` for
  `ToolOutputRenderer`.

Backend SDK-record interfaces:

| Endpoint / function | Purpose |
| --- | --- |
| `GET /tasks/{task_id}/sdk-events/stream?after_id=` | SSE of SDK records (live) |
| `GET /tasks/{task_id}/sdk-events?after_id=&limit=` | JSON page of SDK records |
| `sdk_block_writer.ingest(...)` | Convert SDK messages → `append_sdk_record` |
| `topic_task_store.list_sdk_records(task_id, after_id, limit)` | Read records, `id` ASC |
| `topic_task_store._project_sdk_records(...)` | Replay records → rendered `blocks` for message history |

Data model — `da_agent_sdk_record` (Alembic `20260529_000012`):
`id` (PK, AUTO_INCREMENT), `topic_id`, `task_id`, `turn_index`, `record_type`
(`stream`/`tool_result`/`done`/`error`), `event_type` (`message_start`, …, nullable),
`data` (JSON, raw SDK event/metadata), `created_at`; index `(task_id, id)`.

## Risks / Alternatives

- **Native SDK-event stream vs magic-event stream.** V2 streams the native SDK
  protocol (`/sdk-events/stream`) so live render and persisted records share one
  block model; the magic-event path (`/events/stream`) remains for V1. Cost:
  reliance on the SDK-record write/projection being faithful.
- **`page_size=500` history.** A single large page avoids client pagination for
  typical topics; very long topics could exceed it and would need real
  pagination.
- **Server-side block projection (`_project_sdk_records`).** Keeps the FE parser
  thin and shared, but duplicates the SDK-protocol replay logic on the backend;
  the two must stay in sync.
- **Keeping V1 alongside V2.** V1 remains a fallback tab to de-risk migration;
  the duplicate surface is the cost. Consolidation onto V2 is a follow-up.
- **Backout:** hide/disable the `chat-v2` tab so the surface falls back to V1;
  the parser, `nl2sql.js`, and SDK-record additions are additive and can remain.

## Verification

- Frontend unit (run after `nvm use`): `IntelligentQueryView.spec.js`,
  `NL2SqlChat.spec.js`, and the widget `WidgetChat.spec.js` (which mocks
  `deliverMessage`/`streamSdkEvents` and exercises the shared render path). A
  dedicated `NL2SqlChatV2.spec.js` / `v2StreamParser.spec.js` does not yet exist
  and is a recommended follow-up.
- DataAgent: focused coverage for `sdk_block_writer` ingest and
  `topic_task_store._project_sdk_records` projection.
- End-to-end smoke (per AGENTS.md intelligent-query smoke method): on the
  `chat-v2` tab, submit a real NL2SQL question; verify `deliver-message` returns
  a `task_id`, SDK records stream and render incrementally, the run reaches a
  terminal state, the turn persists to `da_agent_sdk_record`, and history rebuilds
  correctly via `getTopicMessages` (projected `blocks`) after reload.
