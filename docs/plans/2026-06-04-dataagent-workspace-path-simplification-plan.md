# DataAgent Workspace Path Simplification Plan

## Goal

Replace the nested `/tmp/dataagent-home/.dataagent/runtime/{topics,enabled-skills}`
layout with a single top-level `/workspaces` root, so topic workspaces are
`/workspaces/<topic_id>`.

## Tasks

1. Backend code.
   - `core/topic_workspace.py`: non-env sandbox-root default `HOME/workspaces`.
   - `prompts/data_agent_system_prompt.md`: forbid the shared root `/workspaces`
     instead of `/tmp/dataagent-home`.

2. Images.
   - `Dockerfile`: `HOME=/workspaces`, `DATAAGENT_SANDBOX_ROOT=/workspaces`,
     `DATAAGENT_RUNTIME_PROJECT_CWD=/workspaces/enabled-skills`,
     `mkdir -p /workspaces && chmod 1777 /workspaces`.
   - `Dockerfile.runner`: `HOME=/workspaces`, `DATAAGENT_SANDBOX_ROOT=/workspaces`,
     `mkdir -p /workspaces && chmod 1777 /workspaces`.

3. Compose + env.
   - dev/prod: `HOME`, `DATAAGENT_SANDBOX_ROOT`, `DATAAGENT_SANDBOX_HOST_ROOT`,
     `DATAAGENT_RUNTIME_PROJECT_CWD`, the `dataagent-home-init` command, and all
     `/tmp/dataagent-home` bind mounts move to `/workspaces`.
   - `.env.example`: `DATAAGENT_HOME_HOST_DIR=/workspaces` and the sandbox/runtime
     path defaults.

4. Docs.
   - `deploy/README.md` path notes.
   - This design + plan; cross-link from the `dataagent-home-permission` docs.

## Verification

- `yaml.safe_load` both Compose files (passed).
- `pytest tests/test_topic_workspace.py tests/test_skill_discovery.py
  tests/test_agent_profile_service.py tests/test_agent_runtime.py
  tests/test_sandbox_runner_main.py tests/test_task_executor.py` — 63 passed.
- `py_compile` of the touched backend modules (passed).
- Live `docker compose up` smoke (verify `/workspaces/<topic_id>` is created and
  writable, one NL2SQL request succeeds) remains unrun: Docker unavailable in the
  change environment.

## Backout

- Revert the `/workspaces` references back to
  `/tmp/dataagent-home/.dataagent/runtime/...` across code, images, Compose, env,
  and docs. No schema changes.
- Existing deployments keep their old bind dir; the rename takes effect on redeploy.
