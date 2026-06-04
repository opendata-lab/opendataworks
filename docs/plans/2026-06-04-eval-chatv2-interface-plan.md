# DataAgent Eval — Chat V2 Interface Plan

**Date:** 2026-06-04
**Topic slug:** `eval-chatv2-interface`
**Related design:** `docs/design/2026-06-04-eval-chatv2-interface-design.md`

## Objective

Switch both DataAgent eval runners from the V1 magic-event task stream
(`/tasks/{id}/events`) to the Chat V2 SDK-event interface
(`/tasks/{id}/sdk-events`), deriving run evidence from SDK records projected into
the Chat V2 block model. Final-answer sourcing, judge call, scoring, and report
shape are unchanged.

## Affected Stacks

- Evaluation tooling (stdlib Python): `tools/dataagent-evals/builtin/run.py`,
  `tools/dataagent-evals/deepeval/run.py`.
- Tests: `tests/test_run_dataagent_evals.py`,
  `tests/test_dataagent_deepeval_evals.py`.

## Tasks & Touched Files

1. **builtin runner** — `tools/dataagent-evals/builtin/run.py`
   - Add `_fetch_sdk_records(base_url, task_id, after_id)` paging helper over
     `GET /tasks/{id}/sdk-events?after_id=&limit=500` (drain `has_more`).
   - Rework `_poll_task` to accumulate SDK records (cursor `next_after_id`),
     keeping recovered-task handling and the deadline; return `(task, records, errors)`.
   - Add `_project_sdk_blocks(records) -> (blocks, usage)` mirroring
     `_project_sdk_records` / `v2StreamParser.js`.
   - Rewrite `_collect_tool_names`, `_extract_sql_outputs`,
     `_extract_chart_outputs`, `_summarize_tool_events`, `_collect_usage`,
     `auto_rule_check` to consume blocks.
   - `run_case`: project records once; feed blocks into the evidence helpers and
     judge payload.
2. **deepeval runner** — `tools/dataagent-evals/deepeval/run.py`
   - Same `_fetch_sdk_records` / `_poll_task` / `_project_sdk_blocks` changes.
   - Rewrite the evidence helpers to consume blocks; populate `tool_events` in the
     case result so the metric payload carries SDK-derived tool evidence.
3. **Tests** — `tests/test_run_dataagent_evals.py`,
   `tests/test_dataagent_deepeval_evals.py`
   - Update fakes to serve `/sdk-events` records (`record_type` `stream` /
     `tool_result` / `done`) instead of `/events`.
   - Update `_poll_task`, `_extract_sql_outputs`, `_summarize_tool_events`,
     `auto_rule_check`, recovered-task, and full HTTP scenario tests to the SDK
     record shape.

## Verification

- `python -m pytest tests/test_run_dataagent_evals.py
  tests/test_dataagent_deepeval_evals.py`.
- `tests/test_runtime_excludes_eval_api.py` and
  `tests/test_deepeval_packaging_hooks.py` still pass (no API surface change).
- Dry-run still writes `summary.json` / `report.md` with no service calls.

## Rollout & Backout

- **Rollout:** runners consume `/sdk-events`; topic/task/messages calls unchanged.
- **Backout:** revert the two runners; the magic-event endpoint remains for V1.

## Follow-ups

- Keep `_project_sdk_blocks` in sync with backend `_project_sdk_records` and FE
  `v2StreamParser.js` when the SDK block schema changes.
- Optionally consolidate the duplicated projection into a shared eval helper if a
  third consumer appears.
</content>
