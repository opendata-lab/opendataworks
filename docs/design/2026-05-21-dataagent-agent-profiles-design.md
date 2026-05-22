# DataAgent Agent Profiles Design

**Date:** 2026-05-21
**Goal:** Add configurable DataAgent profiles so intelligent query can serve multiple usage scenarios through scoped prompts, skills, tools, MCP services, and runtime settings.
**Tech Stack:** DataAgent FastAPI / Claude Agent SDK, MySQL/Alembic, Vue 3 + Element Plus.

## Current State

The current intelligent-query runtime has one global behavior surface:

- global system prompt from `dataagent-backend/prompts/data_agent_system_prompt.md`
- globally enabled skills from DataAgent settings
- fixed safe tools and optional `portal` MCP injection
- global interactive/background max-turn settings
- one topic list in the frontend chat UI

This makes all questions share the same role, prompt, skill set, and tool boundary. It works for the current intelligent-query path, but it cannot represent scenario-specific assistants such as platform operations, workflow analysis, quality inspection, or narrow business-domain agents.

## Problem

Administrators need multiple intelligent-query agents. Each agent should have a clear positioning prompt and only a small set of relevant skills/tools/services. A chat topic should be reproducible: future messages in that topic must keep using the same agent profile snapshot that was selected when the topic was created.

## Scope

In scope:

- Add a DataAgent profile model and API.
- Seed two built-in profiles: a default general agent and an OpenDataWorks assistant.
- Add an “智能体” tab inside the existing intelligent-query module.
- Show agents in a multi-column card list.
- Support create, detail, edit, save, and “start chat with this agent”.
- Add an agent selector in the intelligent-query chat header.
- Bind each topic and task to an agent profile snapshot.
- Apply agent-specific prompt, skills, allowed tools, existing MCP services, max turns, environment variables, and managed runtime cwd.

Out of scope:

- Top-level main navigation changes.
- MCP server CRUD.
- Memory management UI or persistence.
- Arbitrary server filesystem workdir input.
- Per-message agent switching inside an existing topic.

## Design

### Agent Profile Model

Add `da_agent_profile` in the DataAgent session schema. Each profile stores:

- `agent_id`
- `name`
- `description`
- `system_prompt`
- `permission_mode`: `inherit`, `default`, or `bypassPermissions`
- `allowed_tools_json`
- `mcp_server_ids_json`
- `skill_folders_json`
- `max_turns`
- `env_vars_json`
- `is_default`
- `is_builtin`
- timestamps

The backend resolves `resolved_workdir` from runtime configuration instead of storing arbitrary paths. The default profile is the built-in `通用智能体`, which has no skills or MCP services and uses only a minimal read-only tool set. The built-in `OpenDataWorks助手智能体` preserves the OpenDataWorks-specific intelligent-query behavior by enabling the OpenDataWorks skill folders, safe tool set, and Portal MCP service. User-created profiles use a managed cwd under the DataAgent runtime home.

### Topic And Task Binding

Add `agent_id` and `agent_snapshot_json` to `da_agent_topic` and `da_agent_task`.

Topic creation accepts `agent_id`. The backend snapshots the selected profile into the topic. Every task created in that topic copies the topic snapshot, so later profile edits only affect new topics.

Existing topics are backfilled to the default general agent. Existing tasks can have a null snapshot but are normalized to the general profile at runtime.

### Runtime Integration

`execute_task_stream` receives an agent snapshot through `TaskExecutionInput`. Runtime helpers accept an optional agent profile:

- system prompt = base DataAgent prompt + agent positioning prompt + runtime context
- enabled skills = agent `skill_folders` when set, otherwise global enabled skills
- allowed tools = agent `allowed_tools` plus selected MCP tools
- MCP servers = only selected existing MCP services; v1 includes current `portal`
- max turns = agent `max_turns` when positive, otherwise existing interactive/background defaults
- env vars = validated agent env merged after provider/runtime env, excluding reserved runtime keys
- cwd = default agent uses existing enabled-skills cwd; custom agents use managed per-agent cwd

Root execution still downgrades `bypassPermissions` to SDK-compatible default behavior.

### Frontend UX

The existing intelligent-query shell gains an “智能体” tab. The list uses cards in a responsive grid and shows name, description, enabled skills, allowed tools, MCP services, max turns, default marker, and built-in marker.

The detail view is an Element Plus form with sections:

- basic info: name, description
- positioning: system prompt
- capabilities: skills, tools, MCP services
- advanced: permission mode, max turns, env vars

Memory management is intentionally omitted in v1.

The chat view adds an agent selector in the upper-left. Selecting an agent reloads the topic list for that agent. New topics and submitted messages use the active agent. Existing topics cannot switch agents.

### Built-in Agent Semantics

Two profiles are owned by the system:

- `agent_default` / `通用智能体`: default selector target and backfill target for records that had no agent before profiles existed. It is intentionally broad but not all-powerful: no skill folders, no MCP services, and only `Read`, `LS`, `Glob`, and `Grep` tools.
- `agent_opendataworks` / `OpenDataWorks助手智能体`: OpenDataWorks-specific assistant with the current business-knowledge and platform-tools skill folders, the safe tool set, and Portal MCP.

Both built-in agents are visible and can start chats. They cannot be deleted. User-created agents are always non-built-in.

## Interfaces

New routes:

- `GET /api/v1/dataagent/agents`
- `POST /api/v1/dataagent/agents`
- `GET /api/v1/dataagent/agents/{agent_id}`
- `PUT /api/v1/dataagent/agents/{agent_id}`
- `DELETE /api/v1/dataagent/agents/{agent_id}`
- `GET /api/v1/dataagent/agents/capabilities`

Changed routes:

- `POST /api/v1/nl2sql/topics` accepts `agent_id`
- `GET /api/v1/nl2sql/topics` accepts optional `agent_id`
- topic/task responses include agent summary fields
- task submission accepts optional `agent_id` only for validation against the topic binding

## Risks And Tradeoffs

Keeping snapshots on topics/tasks duplicates profile data, but it protects historical conversations from later admin edits and keeps SDK resume behavior deterministic.

Using managed workdirs reduces flexibility, but avoids exposing arbitrary server paths and keeps deployment behavior consistent.

Omitting memory management leaves one requested label out of v1, but prevents adding unclear persistence semantics before the product behavior is defined.

## Verification

- Unit tests for profile validation, default bootstrap, env-var filtering, and profile snapshots.
- API contract tests for agent CRUD, capabilities, topic filtering, and task submission payloads.
- Runtime tests for prompt/tool/MCP/skill/max-turn/env/cwd overrides.
- Frontend tests for agent list/detail, chat selector filtering, and submitted `agent_id`.
- If local MySQL, Redis, provider settings, and `.venv-py313` are available, run one real HTTP smoke through agent creation and chat execution.
