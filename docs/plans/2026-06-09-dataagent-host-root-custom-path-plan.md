# DataAgent Host Root Custom Path Plan

Paired design: `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`

## Tasks

1. Runner root separation (`dataagent/dataagent-backend/sandbox_runner_main.py`).
   - Import `CONTAINER_RUNTIME_ROOT`; add `_container_runtime_root()` and
     `_topic_container_workspace/home/logs` helpers.
   - In `_build_container_command`, perform `mkdir`/skill-target prep/`chown` on
     the container-path workspace and home; use the host-path workspace and home
     only for the child `--mount source=` strings.
   - Point `_sandbox_task_log_path` at the container logs path so the runner's own
     log writes land on the mounted volume.

2. Launcher resolution (`scripts/start.sh`).
   - Add `resolve_dataagent_host_root()` mirroring `resolve_dataagent_skills_dir()`:
     read `DATAAGENT_HOST_ROOT` from `.env` (default `/dataagent_runtime`),
     normalize absolute values, resolve relative values against `deploy/`.
   - Before the compose `up`, set and `export DATAAGENT_HOST_ROOT` to the
     resolved absolute path and echo it so the chosen host directory is visible.

3. Documentation.
   - `deploy/.env.example`: document that `DATAAGENT_HOST_ROOT` accepts a custom
     absolute path or a `deploy/`-relative path (expanded by `start.sh`), and
     that raw `docker compose` needs an absolute value.
   - `deploy/README.md`: same note on both DataAgent runtime-root bullets.

4. Tests.
   - `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`: add an
     autouse fixture redirecting `CONTAINER_RUNTIME_ROOT` to a temp dir; assert
     the runner creates topic workspace/home/logs under the container root while
     child bind sources use the (distinct) host root, and that the container path
     never leaks in as a bind source.
   - `tests/test_deepeval_packaging_hooks.py`: assert `start.sh` defines
     `resolve_dataagent_host_root` and exports the resolved value before compose.

## Touched files

- `dataagent/dataagent-backend/sandbox_runner_main.py`
- `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`
- `scripts/start.sh`
- `deploy/.env.example`
- `deploy/README.md`
- `tests/test_deepeval_packaging_hooks.py`
- `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`
- `docs/plans/2026-06-09-dataagent-host-root-custom-path-plan.md`

## Verification

- `pytest dataagent/dataagent-backend/tests/test_sandbox_runner_main.py` (host vs
  container root separation).
- `pytest tests/test_deepeval_packaging_hooks.py` for the packaging/launcher
  assertions.
- `bash -n scripts/start.sh` and a shell unit check that
  `resolve_dataagent_host_root` returns an absolute path for both an absolute and
  a relative input.
- Local full-flow smoke (when Docker/MySQL/Redis available): set a custom
  `DATAAGENT_HOST_ROOT`, run one NL2SQL request, and confirm
  `<custom>/<topic>/{workspace,home,logs}` are populated on the host and the
  child completes with a result. Not run in this environment (no Docker daemon).

## Rollout

- Runner code + launcher + docs change. The runner image must be rebuilt to pick
  up the `sandbox_runner_main.py` fix. Deployments using the default
  `/dataagent_runtime` are behavior-unchanged.

## Backout

- Revert the `sandbox_runner_main.py`, `scripts/start.sh`, `.env.example`, and
  `README.md` edits. No data migration is involved.
