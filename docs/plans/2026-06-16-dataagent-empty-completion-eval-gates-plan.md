# DataAgent Empty Completion And Eval Gates Plan

## Tasks

1. Update DataAgent empty result handling in `dataagent/dataagent-backend/core/task_executor.py`.
2. Update unit tests in `dataagent/dataagent-backend/tests/test_task_executor.py`.
3. Normalize provider-error terminal codes when a provider error is paired with a `success` SDK subtype.
4. Update DeepEval and builtin eval runner auto-rule pass/fail semantics.
5. Update eval runner tests in `tests/test_dataagent_deepeval_evals.py` and `tests/test_run_dataagent_evals.py`.
6. Run targeted pytest suites for task executor and eval runners.

## Touched Stacks

- DataAgent backend runtime execution.
- DataAgent eval tooling.

## Verification Commands

```bash
cd dataagent/dataagent-backend
.venv-py313/bin/python -m pytest tests/test_task_executor.py -q

cd <workspace>/opendataworks
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest tests/test_dataagent_deepeval_evals.py tests/test_run_dataagent_evals.py -q
```

If the virtualenv is unavailable, use the repository Python baseline after verifying required imports.

## Rollout

This is a behavior-tightening change. Existing successful tasks with real final text are unaffected. Empty or incomplete model turns become explicit task errors and should be monitored in eval reports. Provider-denied model calls remain task errors, but their stored error code should no longer be `success`.

## Backout

Revert the task result classification, provider-error code normalization, and eval runner pass/fail checks if deployment reveals a caller that intentionally depends on empty `已完成。` task content or `success` error-code compatibility.
