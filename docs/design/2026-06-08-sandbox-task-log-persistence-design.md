# Sandbox Task Log Persistence Design

**Date:** 2026-06-08
**Status:** Active
**Scope:** DataAgent sandbox runner and deployment defaults

## Current State

The sandbox runner starts each agent task in a child container using `docker run --rm` or `podman run --rm`. The runner reads the child stdout/stderr streams and forwards stderr plus non-JSON stdout lines into the runner process log.

Because the child container is removed after exit, operators cannot inspect the child container logs after failure. The runner service also does not write per-task logs to a mounted directory, so `docker compose logs dataagent-sandbox-runner` is the only practical source.

## Problem

When a child task fails and the container is destroyed, debugging depends on whether the relevant lines are still available in the runner container log. This is fragile for long-running deployments, log rotation, and user-side incident triage.

## Goals

- Persist per-task sandbox child logs to a mounted workspace path.
- Include enough context to diagnose failures after child container removal: task id, topic id, container name, stdout non-JSON lines, stderr lines, return code, and final result or fallback error.
- Keep the existing runner log forwarding behavior.
- Avoid keeping failed child containers around by default.

## Design

The runner writes a per-task log file under:

`DATAAGENT_SANDBOX_LOG_DIR` or `/workspaces/.sandbox-logs`

The final path is:

`<log-root>/<sanitized-topic-id>/<sanitized-task-id>.log`

The compose files expose the setting with a default under `/workspaces`, which is already mounted from `DATAAGENT_HOME_HOST_DIR`.

During container execution, the runner appends structured text lines for:

- task start and child container name;
- child stderr lines;
- child stdout lines that are not JSON protocol messages;
- malformed protocol lines;
- result payload summary;
- return code and fallback error when no result is emitted.

The log writer is best-effort. A log write failure is reported to the runner log but does not fail the task.

## Non-Goals

- Do not expose a new HTTP API for downloading logs in this change.
- Do not change the child container retention policy.
- Do not write full JSON SDK event records to the task log, because those are already persisted in `da_agent_sdk_record`.

## Verification

- Unit test the sandbox container execution path with a fake child process that writes stderr and exits without a result.
- Assert the per-task log file contains stderr, return code, and fallback error text.
- Run the focused sandbox runner tests and the DataAgent backend test suite.
