# Chat V2 Only Cleanup Plan

**Date:** 2026-06-08
**Related design:** `docs/design/2026-06-08-chat-v2-only-cleanup-design.md`

## Objective

Delete the legacy Chat V1 and magic-record application logic while keeping the Chat V2 SDK-event chain as the only intelligent-query chat path.

## Affected Stacks

- Frontend: `dataagent/dataagent-frontend`, Vue 3 / Vite / Vitest.
- DataAgent backend: `dataagent/dataagent-backend`, FastAPI / MySQL persistence / task coordinator.

## Tasks

1. **Frontend V1 removal**
   - Delete `src/views/intelligence/NL2SqlChat.vue`.
   - Delete `src/views/intelligence/messageStream.js`.
   - Delete `src/views/intelligence/__tests__/NL2SqlChat.spec.js`.
   - Delete `src/views/intelligence/__tests__/messageStream.spec.js`.
   - Update `IntelligentQueryView.vue` to render `NL2SqlChatV2` as the only chat component and remove the legacy `chat` tab value.
   - Update `IntelligentQueryView.spec.js` to assert unknown or legacy `tab=chat` resolves to V2.

2. **Frontend client cleanup**
   - Remove `taskApi.getTaskEvents`.
   - Remove `taskApi.streamTaskEvents`.
   - Remove `createDemoTaskEvents`.
   - Update `nl2sqlClient.spec.js` for SDK-event streaming only.
   - Remove the legacy nested magic-tool fallback from `chatMessage.js` and update `chatMessage.spec.js`.

3. **Backend API cleanup**
   - Remove `TaskEventRecord` and `TaskEventPageResponse` schemas.
   - Remove `/tasks/{task_id}/events` and `/tasks/{task_id}/events/stream`.
   - Replace the `core.magic_events` route import with local SDK SSE helpers.
   - Delete `core/magic_events.py`.
   - Update `test_routes_contract.py` and widget route context tests so they no longer require `list_task_events`.

4. **Backend execution cleanup**
   - Delete `core/task_persistence.py`.
   - Remove `ClaudeToMagicAdapter` and magic-record emission from `task_executor.py`.
   - Add a small SDK-result accumulator in `task_executor.py`.
   - Keep `SdkBlockWriter` and extend it only if a terminal SDK `error` record is needed for non-SDK failures.
   - Update `task_coordinator.py` to finalize assistant messages directly from `TaskExecutionResult` and persist only SDK-style records emitted by sandbox runners.
   - Update task executor and sandbox tests to assert SDK-first behavior instead of magic records.

5. **Store history cleanup**
   - Remove magic event/chunk projection helpers from `topic_task_store.py`.
   - Remove `append_lifecycle_event`, `append_chunk`, and `list_task_events`.
   - Make `_load_task_history_views()` project only SDK records.
   - Update `test_topic_task_store.py` to remove magic projection cases and keep SDK/fallback message-content coverage.

6. **Verification**
   - Backend: run focused pytest for route contracts, task executor, topic task store, SDK projection, and sandbox runner.
   - Frontend: run `nvm use`, then focused Vitest suites for API client, `IntelligentQueryView`, Chat V2/shared engine/widget/parser.
   - Run the smallest relevant builds if the focused suites pass.
   - If full local smoke cannot be run, report that only targeted tests were completed.
