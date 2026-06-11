# NL2SQL Message Attachments Plan

Design: `docs/design/2026-06-11-nl2sql-message-attachments-design.md`

## Tasks

### 1. Backend schema + store

- `dataagent/dataagent-backend/alembic/versions/20260611_000016_add_message_attachments.py`:
  add nullable `attachments_json` TEXT to `da_agent_message` (+ downgrade).
- `core/topic_task_store.py`:
  - include `attachments_json` in the message SELECTs
    (`list_topic_messages`, `list_topic_messages_page`,
    `get_assistant_message`, `get_message`);
  - `update_assistant_message(..., attachments: list | None = None)` — set the
    column only when a list is passed;
  - `_normalize_message_row` exposes `attachments` (empty list fallback for
    assistant messages).
- `models/schemas.py`: `TopicMessage.attachments: Optional[List[WorkspaceFile]]`
  (move `WorkspaceFile` above `TopicMessage`).

### 2. Backend detection + routes

- `core/topic_files.py`: `snapshot_workspace_state(topic_id)` and
  `diff_generated_files(topic_id, before)` (kind `output` only, new/changed,
  newest first, capped).
- `core/task_coordinator.py::_run_task`: snapshot before
  `execute_task_stream`, diff after, pass `attachments` into
  `update_assistant_message`; wrap detection in try/except + log.
- `api/routes.py`: `GET /tasks/{task_id}/message` → assistant `TopicMessage`
  (404 on missing task/message, honors request context).
- `prompts/data_agent_system_prompt.md`: add the markdown-link reference rule
  to the report section.

### 3. Frontend shared helpers

- `src/views/intelligence/chatMessage.js`:
  - `renderMarkdown(text, { resolveFileHref })` rewrites rendered anchor hrefs
    with `output/` / `uploads/` (optionally `./`-prefixed) prefixes;
  - `hydrateMessageFromApi` carries `attachments` for assistant messages.
- `src/api/nl2sql.js`: `taskApi.getTaskMessage(taskId)`.
- `src/views/intelligence/useNl2SqlChat.js`: after the stream reaches a
  terminal state, fetch the task message (bounded retry) and set
  `assistant.attachments`.

### 4. Frontend surfaces

- `NL2SqlChatV2.vue`: attachment card row under the assistant answer (icon,
  name, size, download link via `topicApi.fileUrl(..., { download: true })`);
  bind `renderMarkdown` to a topic-scoped `resolveFileHref`; styles.
- `widget/WidgetChat.vue`: same card row + markdown binding with its own api
  client; styles.

## Verification

- Backend: `pytest` for `topic_files` diff helpers and the new route contract
  (`tests/test_topic_files.py`, `tests/test_routes_contract.py`).
- Frontend: `nvm use` + targeted `vitest` run for `chatMessage.spec.js` /
  `useNl2SqlChat.spec.js`.
- Full intelligent-query smoke (MySQL/Redis + real provider) is environment
  dependent; if not run, state exactly which layers were verified.

## Rollout / Backout

- Rollout: `alembic upgrade head`, deploy backend then frontend. Old messages
  simply have no attachments.
- Backout: revert code; the extra nullable column is harmless, or
  `alembic downgrade -1`.
