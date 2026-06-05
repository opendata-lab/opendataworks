# DataAgent Conversation Files Design

## Current State

Each conversation (topic) already owns an isolated workspace dir at
`/workspaces/<topic_id>/` (`core/topic_workspace.py`). The agent runs with
`cwd`/`HOME`/`PWD` = that dir, and a `PreToolUse` boundary hook
(`core/agent_runtime.py`) restricts file tools to the workspace + enabled skill
roots. In sandbox-runner mode the same host dir is bind-mounted into the child
container as `/workspace`, so files written by either execution mode are visible
to the other and to the backend (shared `/workspaces` mount).

The chat frontend is `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`
(2-column workbench: `260px` topic sidebar + `1fr` main = messages + composer),
driven by `useNl2SqlChat.js` and the `/api/v1/nl2sql/*` client in `api/nl2sql.js`.
There is no file upload in the composer and no artifact surface today. The backend
(`api/routes.py`) has no workspace file upload/list/download endpoints; the only
multipart endpoint is admin skill ZIP import.

## Problem

Users want to (1) attach files in the input box for the agent to analyze, and
(2) see/download files the conversation produced (HTML reports, exports, charts)
from a collapsible right-side artifact panel.

## Scope

In scope (phase 1):

- Backend: per-topic file upload, list, and download endpoints over the topic
  workspace.
- Frontend `NL2SqlChatV2.vue` only: composer upload control; collapsible
  right-side artifact panel with preview + download.

Out of scope (later phases):

- The embedded `WidgetChat.vue` / portal widget surface.
- Image/most binary inline preview beyond basic types.
- Versioning, multi-file ZIP download, server-side virus scanning.

## Solution

### Workspace layout

```text
/workspaces/<topic_id>/
  .claude/            # reserved (skills, SDK sessions) — never listed/served
  uploads/            # user-attached inputs
  <agent-generated files...>   # HTML/CSV/PNG/... wherever the agent writes
```

Artifacts = every file under the workspace **except `.claude/`**. Uploaded files
(under `uploads/`) are included too, tagged `input` vs `output` by whether they
sit under `uploads/`.

### Backend API (`/api/v1/nl2sql/topics/{topic_id}/files`)

- `POST .../files` (multipart `file`) — sanitize the filename, ensure the topic
  workspace + `uploads/` exist, write to `uploads/<safe_name>` (de-dupe on
  collision), return `{name, rel_path, size, modified_at, kind: "input"}`.
- `GET .../files` — walk the workspace, skipping `.claude/`, return a list of
  `{name, rel_path, size, modified_at, content_type, kind}` sorted by mtime desc.
- `GET .../files/{rel_path}` — stream the file via `FileResponse`. Hard
  path-traversal guard: resolve under the workspace, reject `..`, symlink escape,
  and any path under `.claude/`. `?download=1` forces attachment disposition;
  otherwise inline with a sniffed content-type.

All three reuse `resolve_topic_workspace(topic_id)` for the root and a shared
`_safe_workspace_file(topic_id, rel_path)` resolver for the traversal guard. A
configurable upload size cap (`DATAAGENT_UPLOAD_MAX_BYTES`, default 20 MiB) and an
allowed-extension denylist for executables.

### How the agent sees uploads

The backend uploads files **before** the task runs (frontend uploads on select,
after `ensureTopic`). When the user sends, the message content gets an appended
attachment note listing the workspace-relative paths, e.g.

```text
[附件] 用户上传了以下文件（位于当前工作区，可直接读取）：
- uploads/sales_q3.csv
```

Because the agent cwd is the workspace and the boundary hook already allows
workspace paths, `Read uploads/sales_q3.csv` just works in both execution modes.
No change to the boundary hook or skill contract.

### Frontend `NL2SqlChatV2.vue`

- Composer: a paperclip button + hidden `<input type=file multiple>`; on select,
  upload each file (`topicApi.uploadFile`) and show removable chips above the
  textarea with progress. Track uploaded `rel_path`s; on send, pass them so
  `useNl2SqlChat.send()` appends the attachment note to the content.
- Layout: add a third grid column. `.v2-workbench` becomes
  `260px 1fr [auto]`; when the panel is open, `grid-template-columns:
  260px 1fr 340px`. A toggle button in the main top bar opens/collapses it; state
  persists per session in `localStorage`.
- Artifact panel (`<aside class="v2-artifacts-panel">`): lists
  `topicApi.listFiles(topic_id)`, refreshed when a task reaches a terminal state
  and on manual refresh. Each row: icon by kind, name, size, `input`/`output`
  tag, download button. Clicking a row opens a preview:
  - HTML → **sandboxed `<iframe sandbox srcdoc=...>`** (no `allow-same-origin`,
    no `allow-scripts` unless we later opt in) fetched via the download endpoint
    as text; plus a download button.
  - image → `<img>` from the inline URL.
  - text/csv/json/md → `<pre>`/lightweight render.
  - other → download-only.
- Reset/refetch on conversation switch and new conversation.

### Security

- Path traversal: every file path resolved and confirmed under the workspace
  root, `.claude/` always excluded, symlinks not followed out of the workspace.
- HTML artifacts rendered only inside a sandboxed iframe so agent/result-derived
  HTML cannot touch the portal session or call APIs as the user (XSS containment).
- Upload size cap + executable-extension denylist; filenames sanitized.
- Endpoints sit under `/api/v1/nl2sql/*`, inheriting the existing portal proxy
  auth; topic ownership is enforced the same way as existing topic routes.

## Tradeoffs

Pros: reuses the per-topic workspace and existing boundary model; no agent/skill
contract change; works in both in-process and sandbox execution via the shared
mount.

Cons: listing "all non-`.claude` files" can surface incidental scratch files the
agent wrote; acceptable for phase 1 and avoids an output-dir contract. Sandboxed
iframe without `allow-scripts` means HTML reports with JS (e.g. ECharts) render
statically unless we later opt into a stricter scripted-sandbox review.

## Affected Stacks

- DataAgent backend: `api/routes.py` (new topic file router), a new
  `core/topic_files.py` helper, `config.py` (size cap), `models/schemas.py`
  (file metadata model).
- Frontend: `NL2SqlChatV2.vue`, `useNl2SqlChat.js`, `api/nl2sql.js`.
- Tests: backend file endpoint/traversal tests; frontend composer + panel specs.
