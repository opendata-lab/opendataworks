# Agent Query Default Limit Design

## Current State

Agent read-only SQL has multiple default row limits:

- `dataagent/.claude/skills/dataagent-nl2sql/scripts/run_sql.py` defaults `--limit` to `DATAAGENT_QUERY_LIMIT` or `100`.
- `dataagent/dataagent-backend/config.py` defaults `query_result_limit` to `100`, and `core/agent_runtime.py` falls back to `100`.
- `backend/src/main/java/com/onedata/portal/agentapi/service/BackendAgentQueryService.java` defaults omitted API `limit` to `200`.
- `dataagent/portal-mcp/portal_mcp/app.py` defaults MCP `portal_query_readonly.limit` to `200`.
- The Java backend clamps query limits to a maximum of `10000`.

This causes different default row counts depending on whether the same read-only query is executed through `run_sql.py`, portal MCP, or the backend API directly.

## Problem

The default result size should be predictable for DataAgent read-only SQL execution. The current `100` vs `200` split makes troubleshooting and result interpretation harder, especially when SQL already includes an explicit `LIMIT 500` but the outer tool default truncates the displayed rows earlier.

## Scope

In scope:

- Set the default DataAgent read-only query row limit to `1000`.
- Keep explicit `--limit`, `DATAAGENT_QUERY_LIMIT`, and request `limit` overrides.
- Raise the Java backend and portal MCP maximum limit to `10000`.
- Update the portal MCP query schema default to match the backend and script path.
- Update existing design documentation that describes the backend query contract.

Out of scope:

- Changing SQL text generation rules or injecting SQL `LIMIT`.
- Changing task event pagination defaults.
- Changing query timeout behavior.

## Solution

Use `1000` as the single default and `10000` as the explicit maximum for the agent read-only SQL result cap:

- `run_sql.py`: default `--limit` to `DATAAGENT_QUERY_LIMIT` or `1000`.
- DataAgent runtime settings: default `query_result_limit` and runtime fallback to `1000`.
- Backend agent query service: default omitted `limit` to `1000`, clamp explicit requests above `10000`.
- Portal MCP query input: default omitted `limit` to `1000`, allow explicit requests up to `10000`.

The returned `has_more` behavior remains unchanged: the JDBC executor reads up to the effective cap and reports whether additional rows were available.

## Tradeoffs

Defaulting to `1000` can return larger payloads than before, but avoids surprising truncation for common analytic queries. Allowing explicit requests up to `10000` gives analysts a larger bounded escape hatch while keeping the default moderate. Callers that need smaller outputs can still pass an explicit lower `limit`.
