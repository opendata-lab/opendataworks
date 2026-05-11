# Agent Query Default Limit Plan

## Touched Areas

- Backend agent query service default limit and tests.
- DataAgent runtime config, runtime environment fallback, and script tests.
- Portal MCP query input default and tests.
- Existing design documentation for the backend query contract.

## Tasks

1. Update tests first:
   - Backend `BackendAgentQueryServiceTest` should expect default `limit=1000`.
   - DataAgent `test_metadata_cli_bridge.py` should expect `run_sql.py` to pass `limit=1000` when no override is provided.
   - DataAgent runtime tests should cover the config fallback default of `1000`.
   - Portal MCP tests should cover omitted `limit` forwarding as `1000` and explicit limits above `1000`.
2. Run targeted tests and confirm they fail on the current defaults.
3. Update implementation defaults:
   - `BackendAgentQueryService.DEFAULT_LIMIT`.
   - `config.Settings.query_result_limit`.
   - `agent_runtime._build_runtime_env()` fallback.
   - `run_sql.py` parser default.
   - `QueryReadonlyInput.limit`.
   - Backend and portal MCP maximum limit from `1000` to `10000`.
4. Update contract documentation to default `1000`, maximum `10000`.
5. Run targeted verification:
   - `mvn -Dtest=BackendAgentQueryServiceTest test`
   - Relevant DataAgent pytest tests for runtime env and `run_sql.py`
   - Relevant portal MCP pytest tests for query input forwarding

## Rollout

This changes the default row cap and also allows explicit agent read-query limits up to `10000`. Existing callers with explicit limits at or below `1000` keep their current behavior.

## Backout

Revert the default values and tests to the previous split defaults if larger default responses cause operational issues.
