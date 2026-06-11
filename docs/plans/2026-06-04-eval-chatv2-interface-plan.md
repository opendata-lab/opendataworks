# DataAgent Eval — Chat V2 Interface Plan

**Date:** 2026-06-04
**Topic slug:** `eval-chatv2-interface`
**Related design:** `docs/design/2026-06-04-eval-chatv2-interface-design.md`

## Objective

Switch both DataAgent eval runners off the V1 magic-event task stream
(`/tasks/{id}/events`) by sourcing run evidence from the Chat V2 **server-projected
assistant message blocks** in `GET /topics/{id}/messages`, rather than re-deriving
blocks from the raw SDK-event stream. This removes the eval's own copy of the block
projection (three copies → two) and locks the remaining backend/frontend projection
pair with shared contract fixtures. Final-answer sourcing, judge call, scoring, and
report shape are unchanged.

## Affected Stacks

- Evaluation tooling (stdlib Python): `tools/dataagent-evals/builtin/run.py`,
  `tools/dataagent-evals/deepeval/run.py`.
- Backend tests (DataAgent): `dataagent/dataagent-backend/tests/`.
- Frontend tests (DataAgent): `dataagent/dataagent-frontend/src/views/intelligence/__tests__/`.
- Shared fixtures: `dataagent/contracts/sdk-block-projection/`.
- Eval tests: `tests/test_run_dataagent_evals.py`,
  `tests/test_dataagent_deepeval_evals.py`.

## Tasks & Touched Files

1. **builtin runner** — `tools/dataagent-evals/builtin/run.py`
   - Remove `_project_sdk_blocks` and `_fetch_sdk_records`.
   - Reduce `_poll_task` to a status-only poll over `GET /tasks/{id}`, keeping
     recovered-task handling and the deadline; return `(task, errors)`.
   - Replace `_final_assistant_answer` with `_final_assistant_message` returning the
     selected assistant message dict.
   - `run_case`: read `/messages`, take `message.blocks` / `message.content` /
     `message.usage`; feed blocks into the existing evidence helpers.
   - `_summarize_tool_events` orders by tool ordinal; `_collect_usage(task, message)`.
2. **deepeval runner** — `tools/dataagent-evals/deepeval/run.py`
   - Same removals and `_poll_task` / `_final_assistant_message` / `run_case` changes.
3. **Projection contract** (new)
   - `dataagent/contracts/sdk-block-projection/cases.json` — shared golden cases
     (`records → expected canonical blocks`).
   - `dataagent/dataagent-backend/tests/test_sdk_block_projection_contract.py` —
     normalize `_project_sdk_records` output, assert against fixtures.
   - `dataagent/dataagent-frontend/src/views/intelligence/__tests__/sdkBlockProjection.contract.spec.js`
     — normalize `processV2Record` output, assert against the same fixtures.
4. **Eval tests** — `tests/test_run_dataagent_evals.py`,
   `tests/test_dataagent_deepeval_evals.py`
   - Fakes serve assistant `message.blocks` from `/messages`; drop `/sdk-events`
     handlers. Update `_poll_task`, `_summarize_tool_events`, recovered-task, and
     full HTTP scenario tests.

## Verification

- `python -m pytest tests/test_run_dataagent_evals.py
  tests/test_dataagent_deepeval_evals.py` — passing (42 tests).
- `python -m pytest dataagent/dataagent-backend/tests/test_sdk_block_projection_contract.py`
  (runs in DataAgent CI; locally blocked only by an unrelated `cryptography` rust
  panic in the sandbox — the projection logic was verified by exec-ing the real
  function against the fixtures).
- `vitest run src/views/intelligence/__tests__/sdkBlockProjection.contract.spec.js`
  and `v2StreamParser.spec.js` — passing.
- `tests/test_runtime_excludes_eval_api.py`, `tests/test_deepeval_packaging_hooks.py`
  still pass (no API surface change).
- Dry-run still writes `summary.json` / `report.md` with no service calls.

## Rollout & Backout

- **Rollout:** runners poll task status, then read projected `message.blocks`;
  topic/task/messages calls otherwise unchanged.
- **Backout:** revert the two runners; the magic-event and `/sdk-events` endpoints
  both remain.

## Follow-ups

- Keep the backend `_project_sdk_records` and FE `v2StreamParser.js` projections in
  sync; the shared contract fixtures fail loudly if they drift.
- If a chart-spec-in-text case becomes important to score, extend
  `_extract_chart_outputs` to parse `main_text` fenced specs (currently keyed off
  `tool_use` input/output only, matching prior behavior).
</content>
