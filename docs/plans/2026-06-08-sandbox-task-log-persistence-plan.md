# Sandbox Task Log Persistence Plan

**Date:** 2026-06-08
**Related design:** `docs/design/2026-06-08-sandbox-task-log-persistence-design.md`

## Objective

Persist child sandbox task logs after `docker run --rm` removes the task container.

## Affected Stacks

- DataAgent backend: `dataagent/dataagent-backend/sandbox_runner_main.py`, `config.py`, pytest.
- Deployment: `deploy/docker-compose.dev.yml`, `deploy/docker-compose.prod.yml`.

## Tasks

1. **Failing regression test**
   - Add a sandbox runner test that runs a fake child process.
   - Configure `DATAAGENT_SANDBOX_LOG_DIR` to a temporary directory.
   - Assert the expected per-task log file is created and includes stderr, return code, and no-result fallback error.

2. **Runner log writer**
   - Add a setting `dataagent_sandbox_log_dir` with default `/workspaces/.sandbox-logs`.
   - Add best-effort helpers in `sandbox_runner_main.py` to resolve, create, and append to per-task log files.
   - Append stdout/stderr/result/return-code details from `_execute_task_stream_container`.

3. **Deployment defaults**
   - Set `DATAAGENT_SANDBOX_LOG_DIR` in both dev and prod compose files to `${DATAAGENT_SANDBOX_LOG_DIR:-/workspaces/.sandbox-logs}`.
   - Rely on the existing `/workspaces` host bind mount for persistence.

4. **Verification**
   - Run the new regression test red before implementation.
   - Run focused sandbox runner tests after implementation.
   - Run backend tests or the smallest affected backend suite before completion.
