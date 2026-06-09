# DataAgent Config Consolidation Plan

## Goal

Consolidate DataAgent deployment configuration so `.env` exposes only the
host-owned runtime root, skills source, runner image, and shared Portal MCP
token settings. Keep service-internal values in Compose instead of requiring
operators to configure equivalent variables twice.

## Tasks

1. Backend settings and path code.
   - Replace the old externally named sandbox root settings with
     `DATAAGENT_HOST_ROOT` for host-side runner binds.
   - Use fixed container runtime root `/dataagent_runtime` for topic
     `workspace/`, `home/`, and `logs/` layout.
   - Remove explicit sandbox host skills setting; have the runner use the
     discovered `/app/.claude/skills` mount source for child binds.

2. Images and runtime defaults.
   - Set backend and runner image `HOME` to `/dataagent_runtime`.
   - Create `/dataagent_runtime` and `/app/.claude/skills` at image build time.
   - Do not publish a container runtime-root environment knob.

3. Compose and `.env`.
   - Add `DATAAGENT_HOST_ROOT=/dataagent_runtime` to `.env.example`.
   - Mount `${DATAAGENT_HOST_ROOT:-/dataagent_runtime}:/dataagent_runtime`.
   - Derive internal sandbox image from `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`.
   - Keep sandbox runner URL and sandbox network as fixed internal Compose
     values.
   - Replace duplicated Portal MCP token variables with
     `PORTAL_MCP_TOKEN` and `PORTAL_MCP_TOKEN_HEADER_NAME`.

4. Startup and offline package scripts.
   - Remove startup script write-back for the old sandbox host skills variable.
   - Keep only `DATAAGENT_SKILLS_DIR` resolution for the mounted skills source.
   - Make offline packaging emit only
     `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE` and `DATAAGENT_SKILLS_DIR`.

5. Tests and docs.
   - Update focused DataAgent tests for `/dataagent_runtime` and the
     auto-discovered skills mount source.
   - Add package/deploy regression checks for the consolidated variable set.
   - Update `deploy/README.md` and the paired design document.

## Verification

Run:

```bash
dataagent/dataagent-backend/.venv-py313/bin/python -m pytest \
  dataagent/dataagent-backend/tests/test_topic_workspace.py \
  dataagent/dataagent-backend/tests/test_topic_files.py \
  dataagent/dataagent-backend/tests/test_sandbox_runner_main.py \
  dataagent/dataagent-backend/tests/test_task_executor.py \
  dataagent/dataagent-backend/tests/test_runner_dockerfile.py \
  dataagent/dataagent-backend/tests/test_agent_runtime.py \
  tests/test_deepeval_packaging_hooks.py -q
docker compose -f deploy/docker-compose.dev.yml config
docker compose -f deploy/docker-compose.prod.yml config
rg "DATAAGENT_HOME_HOST_DIR|DATAAGENT_SANDBOX_ROOT|DATAAGENT_SANDBOX_HOST_ROOT|DATAAGENT_SANDBOX_HOST_SKILLS_DIR|DATAAGENT_SANDBOX_IMAGE=|/workspaces" deploy dataagent/dataagent-backend scripts
```

When local Docker/MySQL/Redis and model credentials are available, follow with a
DataAgent smoke run through the real HTTP entrypoint and verify topic files land
under `/dataagent_runtime/<topic_id>/workspace`, `home`, and `logs`.

## Backout

Revert the settings, Compose, image, startup script, package script, tests, and
docs in this change. Deployments that already moved data to
`DATAAGENT_HOST_ROOT` would need the previous bind path restored before rollback.
