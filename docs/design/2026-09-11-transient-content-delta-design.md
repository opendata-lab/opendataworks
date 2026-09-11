# Transient `content.delta` Design

Implementation plan: [2026-09-11-transient-content-delta-plan.md](../plans/2026-09-11-transient-content-delta-plan.md)

## Current state

`da_agent_sdk_record` persists every neutral agent event. Pi emits one
`content.delta` row for each streaming text fragment and later emits a
`content.completed` row containing the assembled `text`. History loads all rows
for every task in a topic, ordered by `(task_id, id)`.

The local production-shaped data set has 315,500 SDK records, including 282,861
`content.delta` rows. A single task has 24,934 deltas. At that cardinality MySQL
chooses a full table scan and filesort for the history query, and sorting the
large JSON rows can exhaust the sort buffer.

## Problem

Delta rows are required while a task is running because an SSE client reconnects
with `after_id` and must replay fragments already emitted. They are redundant once a task reaches `finished`, because every block closed
and `content.completed.data.text` then holds the assembled text history replay
uses.

They are **not** redundant for `error` or `suspended`. A run cut short never
emits `content.completed` for the block it was in the middle of, so that block's
text survives only in its deltas — measured on a real timed-out task, the closed
blocks accounted for 48 of the 138 characters the user had seen.

## Scope

- DataAgent backend task persistence.
- A one-way Alembic data migration for existing SDK records.
- Backend and frontend regression coverage for cleanup, reconnect, and replay.

No frontend replay behavior, event schema, table, TTL service, or
`content.completed` payload changes are in scope.

## Solution

`TopicTaskStore.finish_task` remains the single terminal persistence path. It
first commits the task/topic/downstream terminal state. After that commit it
performs a separate, task-scoped delete of rows whose
`event_type = 'content.delta'` — **only when the terminal status is
`finished`**. Interrupted runs keep their deltas.

The delete is naturally idempotent. It uses a separate connection and is wrapped
in a best-effort boundary: failures are logged with the task id and never alter
or roll back the already-committed terminal state.

The data migration deletes existing delta rows in bounded batches ordered by
primary key, joined to `da_agent_task` so it applies the same `finished`
predicate. Without it a rolling deploy would strip deltas from conversations
still streaming, which are exactly the rows a reconnecting client replays. Each batch autocommits, limiting transaction duration and lock
retention. Its downgrade is intentionally empty because deleted stream fragments
cannot be reconstructed.

## Interfaces and invariants

- The SDK event APIs and `after_id` contract do not change.
- Writers continue to persist `content.delta` during active execution.
- Terminal task state is durable before delta cleanup begins.
- Historical replay receives `content.completed` and reconstructs the same
  rendered blocks and text without delta rows.
- Cleanup failure may leave redundant rows, but cannot turn a successful terminal
  transition into a failed one.

## Tradeoffs

There is a short post-commit window in which a terminal task may still retain its
deltas. This favors terminal-state correctness over immediate space reclamation;
a later repeated terminal write can retry the idempotent cleanup. Deleting after
commit also means a reconnect that races terminal completion may see either the
remaining deltas or the complete `content.completed` event, both of which produce
the same final text.
