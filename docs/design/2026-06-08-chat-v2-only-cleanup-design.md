# Chat V2 Only Cleanup Design

**Date:** 2026-06-08
**Status:** Active
**Scope:** DataAgent backend and DataAgent frontend

## Current State

The intelligent-query chat still carries two runtime paths:

- Chat V1: `NL2SqlChat.vue`, `messageStream.js`, `taskApi.streamTaskEvents`, backend `/tasks/{task_id}/events`, and the "magic event" projection path built from `da_agent_message` hidden lifecycle rows plus `da_agent_chunk`.
- Chat V2: `NL2SqlChatV2.vue`, `useNl2SqlChat.js`, `v2StreamParser.js`, `taskApi.streamSdkEvents`, backend `/tasks/{task_id}/sdk-events`, and `da_agent_sdk_record`.

The UI already defaults to Chat V2 and labels the menu simply as `Chat`, but the V1 fallback and magic-record reducer remain in frontend tests, backend routes, task execution, and store history fallback logic.

## Problem

Keeping both paths makes intelligent-query changes harder to reason about:

- the frontend has two stream parsers and two chat views;
- the backend still transforms SDK messages into magic lifecycle/chunk records even though V2 consumes native SDK records;
- persisted history can come from either SDK records or magic records, which creates divergent render semantics;
- route/API tests still require legacy `/events` endpoints even though the product path is V2.

## Goals

- Keep only the Chat V2 full chain: `deliver-message -> task -> sdk-events -> v2StreamParser -> projected blocks`.
- Remove frontend V1 view, V1 stream adapter, V1 client methods, and V1 unit tests.
- Remove backend magic-event API endpoints and execution-time magic-record emission.
- Keep task/topic/message persistence, queue/schedule submission, widget tracking, file upload, feedback, follow-up suggestions, and SDK-record history projection.
- Keep old assistant message `content` as the fallback display when a historical task has no SDK records.

## Non-Goals

- Do not drop database tables or columns in this change. Existing deployments may still contain `da_agent_chunk`, hidden lifecycle rows in `da_agent_message`, and `last_event_seq`.
- Do not redesign Chat V2 UI, widget UI, scheduling, or task coordination.
- Do not remove the SDK-record projection contract or `da_agent_sdk_record`.

## Design

### Frontend

`IntelligentQueryView.vue` renders `NL2SqlChatV2.vue` directly for the chat tab. The legacy `chat` tab value and `<NL2SqlChat>` fallback are removed. `NL2SqlChat.vue`, `messageStream.js`, and their tests are deleted.

`createNl2SqlApiClient()` keeps `deliverMessage`, `createTask`, `getTask`, `cancelTask`, `streamSdkEvents`, and topic/message APIs. It removes `getTaskEvents`, `streamTaskEvents`, and the demo magic-event generator. The demo/mock path remains owned by the demo adapter and widget/chat component tests.

`chatMessage.js` no longer handles nested legacy magic-event tool blocks when hydrating stored blocks. Stored assistant history is either SDK-projected blocks or message `content` fallback.

### Backend API

`api/routes.py` removes:

- `GET /api/v1/nl2sql/tasks/{task_id}/events`
- `GET /api/v1/nl2sql/tasks/{task_id}/events/stream`

It keeps:

- `POST /api/v1/nl2sql/tasks/deliver-message`
- `POST /api/v1/nl2sql/tasks`
- `GET /api/v1/nl2sql/tasks/{task_id}`
- `POST /api/v1/nl2sql/tasks/{task_id}/cancel`
- `GET /api/v1/nl2sql/tasks/{task_id}/sdk-events`
- `GET /api/v1/nl2sql/tasks/{task_id}/sdk-events/stream`

SSE encoding and terminal task statuses become local route helpers or SDK-event utilities instead of depending on `magic_events.py`.

### Backend Execution

`ClaudeToMagicAdapter` and `TaskPersistenceWriter` are removed. A small SDK-result accumulator remains in `task_executor.py` to derive final task status, user-visible assistant content, usage, error, and SDK session id from native SDK messages. Local execution writes native SDK records through `SdkBlockWriter`; the coordinator accepts only SDK-style records (`stream`, `tool_result`, `done`, `error`) from sandbox runner emission and persists them to `da_agent_sdk_record`.

On normal SDK messages:

- raw stream/tool/done records are persisted to `da_agent_sdk_record`;
- final assistant message content is written once through `update_assistant_message`;
- task status is written through `finish_task`.

On import/runtime/timeout/cancel errors:

- task status and assistant error are still persisted;
- when possible an SDK `error` record is appended so V2 streams can render a terminal error without waiting for task-status reconciliation.

### Store History

`TopicTaskStore._load_task_history_views()` projects only `da_agent_sdk_record` for assistant message blocks. If a historical task has no SDK records, blocks are empty and the frontend falls back to `da_agent_message.content`. Magic-event fallback projection from hidden lifecycle rows and chunks is removed from application code.

## Tradeoffs

- Old conversations created before SDK-record persistence may lose reconstructed thinking/tool detail, but their final assistant `content` remains visible.
- Leaving obsolete DB tables in place avoids a risky destructive migration and keeps rollback simple.
- Removing `/events` is a public runtime API break for V1 clients; this is intentional because the product keeps only Chat V2.

## Verification

- Backend targeted tests:
  - task executor SDK accumulation and error handling;
  - routes contract no longer exposing `/events`;
  - SDK projection contract stays green.
- Frontend targeted tests:
  - `IntelligentQueryView` renders Chat V2 only;
  - `nl2sqlClient` exposes SDK stream only;
  - Chat V2, shared engine, widget, and parser tests stay green.
- Cross-layer smoke is required before claiming full validation when local MySQL, Redis, backend, and frontend can be started.
