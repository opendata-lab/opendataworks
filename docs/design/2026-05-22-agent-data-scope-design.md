# Agent Data Scope Design

**Date:** 2026-05-22
**Goal:** Add strong per-agent data access boundaries for DataAgent metadata and read-only query paths.

## Current State

Agent profiles currently scope prompts, tools, skills, MCP services, max turns, and environment variables. They do not scope data access. Portal MCP and local SQL tools can resolve metadata or execute read-only SQL for any database reachable through the platform metadata and configured credentials.

## Problem

Administrators need each agent to have an explicit allowed data range. The range must be enforced by runtime and backend boundaries, not just by prompt instructions. An empty range must deny access.

## Solution

Add `data_scope_json` to `da_agent_profile`. The API field is `data_scope`:

```json
{
  "allowed_scopes": [
    {
      "cluster_id": 3,
      "source_type": "DORIS",
      "database": "ads_user"
    }
  ]
}
```

The first version supports datasource/schema scope only. Empty or missing `allowed_scopes` means no data is authorized. The profile snapshot copied to topics and tasks includes the same scope so historical conversations keep their original authorization.

DataAgent injects scope into runtime context and Portal MCP HTTP headers. Portal MCP forwards the scope header to the Java Agent API. Java Agent API parses the scope from a request-scoped context and applies it to metadata lookup, datasource resolution, and read-only query execution.

Non-portal entrypoints must bind to an explicit agent before they can reach data. The embeddable widget reads `data-agent-id` from the script tag and sends that `agent_id` when creating topics, listing agent-scoped topics, and delivering messages. Online evaluation runners require `--agent-id` or `DATAAGENT_EVAL_AGENT_ID` for non-dry-run executions and include it in topic/task payloads.

## Interfaces

- `GET /api/v1/dataagent/data-scope/options`: returns selectable datasource/schema options for the Agent detail UI.
- Agent profile create/update/list/detail responses include `data_scope`.
- Widget script config includes required `data-agent-id`; widget payloads include `agent_id`.
- Evaluation runners expose required non-dry-run `--agent-id` and `DATAAGENT_EVAL_AGENT_ID`.
- Portal MCP forwards `X-Agent-Data-Scope`, a base64url-encoded JSON `data_scope` payload.
- Local tool runtime reads `DATAAGENT_DATA_SCOPE_JSON` and denies databases not present in `allowed_scopes`.

## Enforcement

- Metadata search, export, DDL, lineage, datasource resolution, and query execution must require a non-empty allowed scope.
- Global metadata search with no database is narrowed to the allowed scopes.
- Query execution rejects request databases outside the scope before datasource resolution.
- SQL parsing rejects explicit references to schemas outside the allowed set.
- Prompt text lists authorized scopes only as user-visible guidance; it is not trusted for enforcement.

## Verification

Focused pytest, Vitest, and Maven tests cover profile normalization, runtime header injection, local SQL rejection, Portal MCP forwarding, frontend scope editing, and Java query scope rejection.
