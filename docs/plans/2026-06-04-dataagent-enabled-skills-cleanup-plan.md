# DataAgent Legacy Enabled-Skills Cleanup Plan

## Goal

Delete the dead shared `enabled-skills` cwd builder and the phantom per-agent
`resolved_workdir`, now that execution is per-topic.

## Tasks

1. Remove the dead symlink builder.
   - `skill_discovery.py`: drop `prepare_enabled_skills_project_cwd` and
     `_resolve_runtime_project_cwd` (+ now-unused `os`/`shutil` imports).
   - `agent_runtime.py`: drop the stale import.
   - `test_skill_discovery.py`: drop the 3 builder tests and the runtime-cwd
     fixture handling.

2. Remove `resolved_workdir` end to end.
   - Backend: `schemas.AgentProfile.resolved_workdir`,
     `agent_profile_service.resolved_agent_workdir`/`_runtime_root` and the
     `_normalize_row` assignment, `config.dataagent_runtime_project_cwd`,
     `sandbox_runner_main` forwarded-env entry.
   - Deployment: `DATAAGENT_RUNTIME_PROJECT_CWD` from `Dockerfile`, dev/prod
     Compose, `.env.example`.
   - Frontend: displays + CSS in `AgentDetailView.vue` and `AgentStudio.vue`;
     form default and mapping in `AgentDetailView.vue`; spec mocks/assertions;
     demo `mockServer.js` (dataagent + main frontend).

## Verification

- Backend: `pytest tests/test_skill_discovery.py tests/test_agent_profile_service.py
  tests/test_admin_routes.py tests/test_routes_contract.py tests/test_agent_runtime.py
  tests/test_sandbox_runner_main.py tests/test_topic_workspace.py` — 57 passed.
- Frontend: `vitest run AgentStudio.spec.js AgentDetailView.spec.js` — 4 passed.
- `node --check` on both `mockServer.js`; `yaml.safe_load` on both Compose files.
- Repo grep confirms no remaining `resolved_workdir` /
  `DATAAGENT_RUNTIME_PROJECT_CWD` references in code/deploy.

## Backout

- Restore the removed functions, schema field, config/env knob, and frontend
  displays. No schema/database changes were involved.
