# Agent Data Scope Implementation Plan

**Goal:** Enforce per-agent datasource/schema authorization across DataAgent local tools, Portal MCP, Java Agent API metadata, and read-only query paths.

## Tasks

1. Add DataAgent `data_scope` validation, persistence, snapshotting, and Alembic migration for `da_agent_profile.data_scope_json`.
2. Add `GET /api/v1/dataagent/data-scope/options` to return distinct platform datasource/schema options.
3. Propagate scope into runtime env, system prompt, and Portal MCP `X-Agent-Data-Scope` header.
4. Make local SQL execution reject databases outside `DATAAGENT_DATA_SCOPE_JSON`.
5. Add Portal MCP request scope context and forward `X-Agent-Data-Scope` to backend Agent API.
6. Add Java Agent API request scope context and enforce it in metadata and query services.
7. Add Agent detail/list UI for selectable data scopes and persist `data_scope`.
8. Require explicit agent binding for widget and online evaluation entrypoints so they cannot accidentally run through an empty-scope default agent.
9. Run focused backend, portal-mcp, frontend, Java, widget, and evaluation tests; report any unavailable end-to-end smoke coverage.

## Verification Commands

```bash
cd dataagent/dataagent-backend
./.venv-py313/bin/python -m pytest tests/test_agent_profile_service.py tests/test_agent_runtime.py tests/test_sql_executor.py tests/test_admin_routes.py -q

cd dataagent/portal-mcp
python -m pytest tests/test_backend_client.py tests/test_app.py -q

export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
npm --prefix frontend run test -- AgentDetailView AgentStudio
npm --prefix frontend run test -- widget

python -m pytest tests/test_run_dataagent_evals.py tests/test_dataagent_deepeval_evals.py -q

mvn -f backend/pom.xml -Dtest=BackendAgentQueryServiceTest,BackendAgentMetadataServiceTest test
```
