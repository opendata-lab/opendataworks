# Host BFF protocol

The element never talks to DataAgent. It calls **your** backend, which proxies.
That is what keeps the DataAgent address and its credentials out of the browser.

Point the element at a base path and implement these six endpoints under it:

```
<dataagent-conversation endpoint="/api/v1/workspaces/w-1/sessions/s-1/agent-conversation">
```

## Endpoints

| Method | Path | Request | Response |
| --- | --- | --- | --- |
| GET | `{endpoint}` | — | `ConversationSnapshot` |
| POST | `{endpoint}/messages` | `{ content, metadata, attachments?, settings? }` | `RunRef` |
| GET | `{endpoint}/events?after_id={n}` | — | SSE, see below |
| POST | `{endpoint}/cancel` | `{ task_id }` | `RunRef` |
| POST | `{endpoint}/interactions` | `{ task_id, kind, request_id, payload }` | `{ "ok": true }` |
| GET | `{endpoint}/files/{rel_path}` | — | binary |

```jsonc
// ConversationSnapshot
{
  "messages": [ /* ConversationMessage[] */ ],
  // The active run, or the most recent terminal one so metadata survives a reload.
  "run": { "task_id": "t-1", "status": "running", "detail": "...", "metadata": { "mode": "model" } }
}
```

`metadata` is opaque to the SDK: whatever you pass to `sendMessage` comes back
on the completion event. It arrives from the browser, so **treat it as
untrusted input** and validate it server-side.

## Errors

Answer failures with `{ "message": "...", "hint": "..." }` and a meaningful
status code. `hint` is where you say *how to fix it* — "放行 website_id=x in the
admin console" rather than "403". The element shows both.

## The event stream

Emit named frames. Do not proxy an upstream byte stream through unchanged.

```
event: agent-event
data: {"seq_id":12, ...}

: ping

event: done
data: {"task_id":"t-1","status":"finished","detail":"","metadata":{"mode":"model"}}
```

- `agent-event` — one runtime event. `seq_id` drives resumption via `after_id`.
- `done` — **required**. The client only treats a run as finished when it sees
  this frame. An EOF without one is reported as an interruption and triggers
  reconnection, because at the byte level a dropped connection and a completed
  run are indistinguishable. Getting this wrong makes hosts refresh business
  data while a run is still in flight.
- `: ping` — keep-alive, ignored.

Send `done` **after** any server-side work that the host will observe. If
finishing a run also produces data the page will reload, commit that first;
otherwise the client refreshes before your write lands.

## Run status

Report SDK statuses, not your runtime's internal vocabulary:

`idle` · `queued` · `running` · `waiting_input` · `waiting_permission` ·
`finished` · `cancelled` · `failed`

The first five are **active**. A host gating edits on "is a run in progress"
checks all of them — `waiting_input` is parked, not done.

If you proxy OpenDataWorks DataAgent, its `task_status` maps as:
`waiting→queued`, `running→running`, `waiting_input→waiting_input`,
`waiting_permission→waiting_permission`, `finished→finished`, `error→failed`,
`suspended→cancelled`, and a missing task as `failed`.

## Mapping to DataAgent

For a BFF proxying OpenDataWorks DataAgent, the runtime prefix is
`/api/v1/nl2sql`:

| Purpose | Endpoint |
| --- | --- |
| Create conversation | `POST /api/v1/nl2sql/topics` |
| History (paginate to exhaustion) | `GET /api/v1/nl2sql/topics/{topic_id}/messages` |
| Upload / download files | `POST` / `GET /api/v1/nl2sql/topics/{topic_id}/files[/{rel_path}]` |
| Submit a run | `POST /api/v1/nl2sql/tasks/deliver-message` |
| Poll a run | `GET /api/v1/nl2sql/tasks/{task_id}` |
| Event stream | `GET /api/v1/nl2sql/tasks/{task_id}/sdk-events/stream?after_id=` |
| Cancel | `POST /api/v1/nl2sql/tasks/{task_id}/cancel` |
| Permission / question replies | `POST /api/v1/nl2sql/tasks/{task_id}/permission-decision` · `/question-answer` |
| **Does this agent exist** | `GET /api/v1/dataagent/agents/{agent_id}` |

Two traps in that table. The agent lookup sits on a **different prefix** and
does not follow a configurable runtime prefix. And `GET /topics?agent_id=` is
not an existence check — it filters existing topics and returns `200` with an
empty list for an agent that was never created.

History defaults to 200 rows per page and caps at 500, so a single request
silently truncates long conversations.

## Optional capabilities

Omit what your backend cannot do; the UI degrades instead of erroring.

| Transport method | Omitted → |
| --- | --- |
| `executeSql` | SQL panel becomes read-only (highlighting and copy) |
| `uploadFiles` | no attach button |
| `readFile` | attachments cannot be previewed or saved in place |
| `setPermissionMode` | the picker still works, the choice just is not saved |
| `submitFeedback` | no feedback controls |

An ontology or reporting host that cannot run arbitrary SQL should simply omit
`executeSql` — that is the intended use of this table, not a limitation.

The slash-command list and the permission-mode options are **not** transport
capabilities — they are host configuration, passed as `composerConfig`, because
the host already knows both and should not fetch them through a second round
trip. Persisting the chosen mode is a capability, because only the host knows
where a conversation's settings live.

The current selection also travels with each message as `settings`: that is
what the run uses, while the persisted value is what the conversation restores
to. A host that cannot persist simply omits `setPermissionMode`.

`readFile` also backs saving, not just previewing: a bare `<a href>` cannot
carry the headers a runtime requires, so on an authenticated host a plain link
downloads an error page.
