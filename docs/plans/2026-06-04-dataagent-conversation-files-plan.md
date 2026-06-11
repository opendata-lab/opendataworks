# DataAgent Conversation Files Plan

## Goal

Add per-conversation file upload (for agent analysis) and a collapsible
right-side artifact panel (preview + download of workspace files) to the
standalone chat `NL2SqlChatV2.vue`, built on the existing per-topic workspace.

## Phasing

### Phase A — Backend topic file endpoints

1. `core/topic_files.py`:
   - `safe_workspace_file(topic_id, rel_path) -> Path` (resolve under workspace,
     reject `..`/symlink escape/`.claude/`).
   - `save_upload(topic_id, filename, data) -> dict` (sanitize name, ensure
     `uploads/`, de-dupe, enforce size cap + extension denylist).
   - `list_files(topic_id) -> list[dict]` (walk, skip `.claude/`, tag
     input/output, sort by mtime desc).
2. `models/schemas.py`: `WorkspaceFile` metadata model + list response.
3. `config.py`: `dataagent_upload_max_bytes` (default 20 MiB).
4. `api/routes.py`: topic-scoped file router — `POST/GET .../files`,
   `GET .../files/{rel_path}` (FileResponse, `?download=1`).
5. Tests `tests/test_topic_files.py`: upload roundtrip, list excludes `.claude/`,
   traversal/`..`/symlink/`.claude` rejection, size-cap rejection, download
   content-type + attachment disposition.

### Phase B — Frontend upload in composer

6. `api/nl2sql.js` `topicApi`: `uploadFile(topicId, file, {onProgress})`,
   `listFiles(topicId)`, `fileUrl(topicId, relPath, {download})`,
   `fetchFileText(topicId, relPath)`.
7. `NL2SqlChatV2.vue` composer: paperclip button + hidden input + file chips
   (progress, remove). Upload on select (after `ensureTopic`); keep uploaded
   `rel_path`s.
8. `useNl2SqlChat.js` `send()`: accept attached file refs, append the `[附件]`
   note to content; clear refs after send.
9. Spec: composer uploads a file and includes the attachment note on send.

### Phase C — Artifact panel

10. `NL2SqlChatV2.vue`: third grid column + top-bar toggle (persist open state in
    `localStorage`); `<aside class="v2-artifacts-panel">` listing
    `listFiles(topic_id)`, refreshed on terminal task status + manual refresh +
    conversation switch.
11. Preview: HTML in **sandboxed `<iframe sandbox srcdoc>`** (fetched text),
    image `<img>`, text/csv/json/md `<pre>`, else download-only. Download button
    per row + in preview.
12. Spec: panel lists files (excludes `.claude`), toggles, renders an HTML
    artifact inside a sandboxed iframe, triggers download.

## Verification

- Backend: `pytest tests/test_topic_files.py` plus existing topic/route tests.
- Frontend: `nvm use` then `vitest run` for the new/updated specs.
- Local e2e smoke (when Docker available): start MySQL/Redis/backend/frontend,
  create a topic, upload a CSV, ask the agent to read it, have it write an HTML
  report, confirm the panel lists + previews + downloads it; confirm a second
  topic cannot see the first topic's files; confirm `.claude/` is never served.
  Note explicitly if the e2e smoke is not run.

## Backout

- Remove the file router + `core/topic_files.py` + schema/config additions
  (backend), and the composer upload + artifact panel (frontend). No schema or
  DB migration is involved; workspaces are unaffected.

## Notes / open items

- Sandboxed iframe renders HTML without scripts by default; revisit a stricter
  scripted-sandbox review if interactive (JS/ECharts) reports are needed.
- Phase 2 (separate change): bring upload + artifacts to the embedded
  `WidgetChat.vue` with a drawer layout.
