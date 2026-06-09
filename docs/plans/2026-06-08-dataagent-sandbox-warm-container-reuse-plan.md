# DataAgent Sandbox Warm Container Reuse Plan

Design: `docs/design/2026-06-08-dataagent-sandbox-warm-container-reuse-design.md`

## Affected stacks

- DataAgent backend (`dataagent/dataagent-backend`): sandbox runner, child task
  entrypoint, settings.
- No frontend, main-backend, or schema changes.

## Tasks

### 1. Settings

File: `config.py`

- Add sandbox reuse settings with defaults:
  - `dataagent_sandbox_reuse_enabled: bool = True`
  - `dataagent_sandbox_idle_ttl_seconds: int = 600`
  - `dataagent_sandbox_max_warm_containers: int = 32`
  - `dataagent_sandbox_reaper_interval_seconds: int = 30`

### 2. Child serve loop

File: `sandbox_task_main.py`

- Factor the run-and-print step into a shared `_execute_and_emit(params)` used by
  both single-shot and loop modes.
- Keep `_main()` single-shot behavior unchanged (backward compatible, existing
  test relies on it).
- Add `_serve_loop()`: async stdin reader, newline-delimited payloads, idle
  timeout from `DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT`, one `result` line per
  payload, clean exit on EOF or idle.
- Entrypoint dispatches to `_serve_loop()` when the idle-timeout env is set,
  otherwise `_main()`.

### 3. Runner warm pool

File: `sandbox_runner_main.py`

- Config helpers: `_should_reuse_containers`, `_warm_idle_ttl`,
  `_warm_max_containers`, `_warm_reaper_interval`.
- `_container_spec_signature(params)`: stable hash over backend, image, topic
  workspace path, sorted enabled skill folders, isolation knobs, uid/gid.
- Extend `_build_container_command` with optional `container_name`,
  `task_id_label`, and `extra_env` so warm children get a stable name and the
  child idle-timeout env without changing the default (3-tuple) contract.
- `WarmChild` dataclass + `WARM_POOL` + `WARM_POOL_LOCK` + signature reuse.
- `_acquire_warm_child` / `_run_on_warm_child` / `_release_warm_child`,
  `_start_warm_child`, `_close_warm_child`, `_drain_warm_stderr`,
  `_watch_cancel_warm`, `_evict_idle_over_cap_locked`.
- `_execute_task_stream_warm` orchestrates acquire/run/release.
- Dispatch in `run_sandbox_task.execute()`: warm path when
  `_should_reuse_containers()`, else existing `_execute_task_stream_container`.
- Lifespan: start the reaper and kill all warm children on shutdown when reuse
  is enabled.

### 4. Session persistence (topic dir split)

Persist Claude HOME per topic so resume survives child container recreation
(TTL eviction, restart, reuse disabled). Split the topic root into two separately
mounted sibling subdirs.

File: `core/topic_workspace.py`
- Add `resolve_topic_root(topic_id)` -> `<sandbox_root>/<topic>`.
- `resolve_topic_workspace(topic_id)` -> `resolve_topic_root(...) / "workspace"`.
- `delete_topic_workspace` removes the topic root (workspace + home together).
- `cleanup_orphan_topic_workspaces` unchanged (still scans topic roots).

File: `sandbox_runner_main.py`
- `_topic_host_workspace` -> `<sandbox_root>/<topic>/workspace`; `_topic_host_home`
  -> `<sandbox_root>/<topic>/home` (sibling, not inside workspace); both
  bind-mounted (workspace -> /mnt/workspace, home -> /mnt/home).
- `_host_sandbox_root` fallback uses `resolve_topic_root(...).parent`.

File: `core/topic_files.py`
- Docstring path update only (resolution follows `resolve_topic_workspace`).

Migration: none; split applies to new topics only.

### 5. Tests

File: `tests/test_sandbox_runner_main.py`

- signature is stable for same topic/skills/isolation and differs for a
  different topic or enabled-skill set.
- warm reuse: two sequential tasks with a stub loop child create one container
  and reuse it (pool holds a single child, reused on the second run).
- idle reaper kills and removes an idle warm child past the TTL.
- cancel kills the warm child and the run returns suspended.
- `_build_container_command` honors `container_name`, `task_id_label`, and
  `extra_env`.

- session persistence: HOME is a per-topic bind-mount at `<topic>/home` (sibling
  of `<topic>/workspace`), mounted at `/mnt/home`; workspace bind source is
  `<topic>/workspace`.

File: `tests/test_sandbox_task_main.py`

- `_serve_loop` processes multiple newline-delimited payloads, emitting one
  result line per payload, and exits on stdin EOF.

File: `tests/test_topic_workspace.py`, `tests/test_topic_files.py`

- `resolve_topic_workspace` returns `<topic>/workspace`; `delete_topic_workspace`
  removes the whole topic root; topic file uploads/output live under
  `<topic>/workspace`.

## Verification

- `pytest` for the two touched test modules in `dataagent/dataagent-backend`.
- Local end-to-end container smoke is environment-dependent (requires a Docker
  daemon and the runner image). If it cannot be run in this environment, report
  that the container reuse smoke was not executed and which layers were covered
  by tests.

## Rollout / Backout

- Rollout: defaults to enabled in container mode; no action needed beyond deploy.
- Backout: set `DATAAGENT_SANDBOX_REUSE_ENABLED=false` to restore the exact
  current one-shot-per-task container behavior (single-layer fallback).
