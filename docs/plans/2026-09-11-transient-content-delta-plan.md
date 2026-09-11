# Transient `content.delta` Implementation Plan

Design: [2026-09-11-transient-content-delta-design.md](../design/2026-09-11-transient-content-delta-design.md)

## Affected stack

- DataAgent backend: task terminal persistence and SDK record storage.
- DataAgent migration: one-way cleanup of existing rows.
- DataAgent frontend tests: history replay through the production reducer.

## Tasks

1. Add a task-scoped, idempotent `content.delta` delete method to
   `TopicTaskStore`.
2. Invoke cleanup only after `finish_task` commits; isolate and log cleanup
   failures.
3. Add an Alembic revision after `20260910_000023` that deletes existing deltas
   in bounded, autocommitted batches and has an intentionally empty downgrade.
4. Replace the temporary probe with a committed real-record replay fixture and a
   formal frontend regression test comparing complete block structures and text
   with and without delta rows.
5. Add backend tests for terminal cleanup, idempotence, failure isolation, and
   running-task `after_id` replay.
6. Add a migration contract test that rejects an unbounded delete.

## Verification

- Run focused backend tests and the formal frontend replay test.
- Perform mutation checks for each fix so the corresponding regression test is
  observed failing when the fix is removed.
- Capture the history query `EXPLAIN` before migration.
- Run `alembic upgrade head` against MySQL `127.0.0.1:3306/dataagent`.
- Re-run the identical `EXPLAIN` and confirm `idx_sdk_record_task_id` is selected
  without filesort.
- Run `pytest tests/ -q` with `.venv-py313/bin/python`.

## Rollout and backout

Deploy the backend and run the migration during the normal migration phase. Code
rollback is safe but does not restore deleted deltas. The migration downgrade is
therefore a documented no-op; retained `content.completed` records continue to
support history replay.
