# DataAgent Agent Profiles Implementation Plan

> **For agentic workers:** Use an isolated worktree on branch `codex/dataagent-agent-profiles`. Apply TDD for backend and frontend behavior. Keep commits/checkpoints small if committing.

**Goal:** Add scenario-specific DataAgent profiles and bind intelligent-query topics to profile snapshots.

**Architecture:** Store agents in a new DataAgent table, snapshot profile config onto topics/tasks, and pass the snapshot into the Claude Agent SDK runtime. The frontend adds an intelligent-query-local “智能体” tab plus a chat selector that filters topics and creates new topics under the selected agent.

**Tech Stack:** FastAPI, Pydantic, PyMySQL, Alembic, Claude Agent SDK, Vue 3, Element Plus, Vitest, pytest.

---

## Tasks

1. Add Alembic migration for `da_agent_profile`, topic/task `agent_id`, and snapshot columns.
2. Add Pydantic schemas for agent profiles, capabilities, agent summaries on topics/tasks, and request payloads.
3. Add backend profile store/service with default bootstrap, validation, snapshot creation, delete guards, and capability discovery.
4. Add agent profile API routes under `/api/v1/dataagent/agents`.
5. Extend topic/task store and submission flow so topics and tasks carry immutable agent snapshots.
6. Extend runtime helpers and task execution to apply agent prompt, tools, MCP services, skills, max turns, env vars, and managed cwd.
7. Add frontend API helpers for agents.
8. Add `AgentStudio.vue` and `AgentDetailView.vue`; register intelligent-query routes and menu item.
9. Update `NL2SqlChat.vue` to load/select agents, filter topics by `agent_id`, create topics with active agent, and submit `agent_id`.
10. Add/adjust backend pytest and frontend Vitest coverage.
11. Run focused verification; run local HTTP smoke if the environment is available.

## Built-in Agent Increment

This follow-up keeps the profile architecture from the original plan and adjusts the built-in seed data:

1. Add `is_builtin` to `da_agent_profile` with a new Alembic migration.
2. Treat `agent_default` as `通用智能体`; it is `is_default=true`, `is_builtin=true`, has no skills/MCP services, and uses the minimal read-only tool set.
3. Add `agent_opendataworks` as `OpenDataWorks助手智能体`; it is `is_default=false`, `is_builtin=true`, and carries the OpenDataWorks skills, safe tools, and Portal MCP.
4. Backfill only missing topic/task agent bindings to `agent_default` so old records without an agent map to the general agent.
5. Expose `is_builtin` in agent summaries and profiles; hide delete actions for built-in profiles in the frontend.
6. Update backend pytest and frontend Vitest coverage before implementation, then rerun the focused verification commands.

## File Map

- `dataagent/dataagent-backend/alembic/versions/20260521_000007_add_agent_profiles.py`: database migration.
- `dataagent/dataagent-backend/models/schemas.py`: API contracts.
- `dataagent/dataagent-backend/core/agent_profile_service.py`: profile validation, persistence helpers, snapshots, runtime conversion.
- `dataagent/dataagent-backend/api/admin_routes.py`: agent profile admin routes.
- `dataagent/dataagent-backend/core/topic_task_store.py`: topic/task columns, filtering, snapshot persistence.
- `dataagent/dataagent-backend/core/task_submission_service.py`: bind submissions to topic agent snapshot.
- `dataagent/dataagent-backend/core/task_coordinator.py`: pass task agent snapshot to execution.
- `dataagent/dataagent-backend/core/agent_runtime.py` and `core/task_executor.py`: apply runtime overrides.
- `frontend/src/api/dataagent.js`: agent profile API.
- `frontend/src/views/intelligence/IntelligentQueryView.vue`: menu/router host.
- `frontend/src/views/intelligence/AgentStudio.vue`: agent grid.
- `frontend/src/views/intelligence/AgentDetailView.vue`: agent edit form.
- `frontend/src/views/intelligence/NL2SqlChat.vue`: agent selector and topic filtering.

## Verification Commands

Backend:

```bash
cd dataagent/dataagent-backend
./.venv-py313/bin/python -m pytest tests/test_agent_profile_service.py tests/test_admin_routes.py tests/test_routes_contract.py tests/test_task_executor.py tests/test_topic_task_store.py -q
```

Frontend:

```bash
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix frontend run test -- AgentStudio AgentDetailView NL2SqlChat
```

Smoke when available:

```bash
cd dataagent/dataagent-backend
./.venv-py313/bin/python -m alembic upgrade head
./.venv-py313/bin/uvicorn main:app --host 127.0.0.1 --port 8900
```

Then call the real HTTP flow: create an agent, create a topic with that `agent_id`, submit `你好，请直接回复 smoke-ok。`, consume events, and confirm final assistant message persistence.
