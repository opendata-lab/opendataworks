# Agent events v1

The wire contract every engine adapter produces before anything is persisted.

## Why this exists

The frontend used to face one event model per engine, and a third engine would
have meant a third. Worse, the vocabulary lived in three places at once —
`runtime-pi/src/protocol/frames.ts`, the frontend reducer, and the Python
projection — kept in sync by hand. A typo produced a record that was stored
successfully and then silently ignored by every consumer.

These schemas are the single definition. Producers validate against them, so a
drift fails a test rather than a production run.

## Design constraints

**Superset of the Claude model.** `output` keeps the shape the Anthropic SDK
path already stored — a string or an array of content blocks. Engine metadata
rides in the sibling `output_meta`, never inside `output`. That is what lets the
existing renderers work unchanged: they look for a platform `kind` in a string,
in a block array, or on an object that carries it, and wrapping the payload
breaks all three.

**Model context is not part of this contract.** What is sent to the LLM comes
from `da_agent_message` via `_build_history`, and no translation here may touch
it. Rewriting it would change the cached prompt prefix.

**Liveness is not an event.** A slow tool keeps the control plane's idle timer
alive with a `run.heartbeat` protocol frame. `tool.progress` was removed: no
consumer ever rendered it, so it only grew the record table.

## Files

| File | Contents |
|---|---|
| `neutral-event.schema.json` | Event envelope and the closed type vocabulary |
| `tool-output.schema.json` | `output` / `output_meta` for `tool.completed` |
| `task-status.schema.json` | Engine outcomes, platform statuses, downstream mapping |

## Compatibility

Records written before this contract carry `record_type: "pi_event"` and, for
tool results, an unsplit `{content, details}` wrapper. They are normalized on
read in `list_sdk_records`, which every reader goes through, rather than being
migrated: the stored evidence stays intact and a mistake is a code fix.
