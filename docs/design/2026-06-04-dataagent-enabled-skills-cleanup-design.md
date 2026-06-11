# DataAgent Legacy Enabled-Skills Cleanup Design

## Current State

Before per-topic workspaces, NL2SQL execution used a single shared filtered
project cwd at `HOME/.dataagent/runtime/enabled-skills`, built by
`skill_discovery.prepare_enabled_skills_project_cwd()` (symlinking only enabled
skills). That cwd location was pinned by `DATAAGENT_RUNTIME_PROJECT_CWD` /
`config.dataagent_runtime_project_cwd`.

Execution has since moved to per-topic workspaces (`prepare_topic_workspace`,
`/workspaces/<topic_id>`), which build their own `.claude/skills` symlinks per
topic/agent. The shared enabled-skills builder lost all live callers.

The same legacy setting also fed `agent_profile_service.resolved_agent_workdir()`
→ the `resolved_workdir` API field → the agent UI ("托管工作空间" in
`AgentDetailView`, the path line in `AgentStudio` cards). With per-topic
execution there is no per-agent workspace, so that field resolved to a phantom
path (e.g. `/workspaces/workspaces/<agent_id>`) that is never created or used.

## Problem

The dead builder and the phantom `resolved_workdir` are confusing: the builder
is unreachable code, and the UI shows a path that does not correspond to any real
directory or to how execution actually works.

## Solution

Remove both, in two layers:

1. Dead symlink builder: delete `prepare_enabled_skills_project_cwd` and its only
   helper `_resolve_runtime_project_cwd` from `skill_discovery.py`, the stale
   import in `agent_runtime.py`, and their tests. The live per-topic symlink path
   and the discovery-root helpers (`resolve_skill_discovery_root_dir`,
   `resolve_builtin_skill_root_dir`, …) are untouched.

2. Phantom agent workdir: remove `resolved_workdir` end to end — the backend
   `AgentProfile` schema field, `resolved_agent_workdir`/`_runtime_root` and the
   `_normalize_row` assignment, `config.dataagent_runtime_project_cwd`, the
   `DATAAGENT_RUNTIME_PROJECT_CWD` env (Dockerfile, dev/prod Compose,
   `.env.example`), the runner forwarded-env entry, the two frontend displays
   (with their CSS), and all test/mock references.

## Tradeoffs

Pros:

- removes unreachable code and a misleading UI field
- drops one legacy env knob from images, Compose, and `.env.example`

Cons:

- the agent UI no longer shows a per-agent "managed workspace" path; this matches
  reality (workspaces are per-topic), but is a visible removal
- `resolved_workdir` disappears from the agent API response (clients reading it
  get nothing; it was already a phantom value)

## Affected Stacks

- DataAgent backend: `core/skill_discovery.py`, `core/agent_runtime.py`,
  `core/agent_profile_service.py`, `models/schemas.py`, `config.py`,
  `sandbox_runner_main.py`
- Frontend: `AgentDetailView.vue`, `AgentStudio.vue` (+ specs, demo mocks)
- Deployment: `Dockerfile`, `deploy/docker-compose.dev.yml`,
  `deploy/docker-compose.prod.yml`, `deploy/.env.example`
- Tests: skill discovery, agent profile, admin routes, routes contract,
  agent studio/detail specs
