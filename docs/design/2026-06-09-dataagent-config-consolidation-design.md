# DataAgent Config Consolidation Design

## Current State

DataAgent deployment exposed several variables for the same physical roots:

- `DATAAGENT_HOME_HOST_DIR`, `DATAAGENT_SANDBOX_ROOT`, and
  `DATAAGENT_SANDBOX_HOST_ROOT` all described the persistent topic runtime root
  from different viewpoints.
- `DATAAGENT_SKILLS_DIR` and `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` both described
  the same mounted skills source, with `scripts/start.sh` copying one value into
  the other.
- `DATAAGENT_SANDBOX_IMAGE` duplicated `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`.
- Portal MCP frontdoor token settings were duplicated between portal-mcp server
  variables and DataAgent client variables.

This made `.env` look like it had more independent choices than the runtime
actually supports.

## Solution

Expose only deployment-owned inputs:

- `DATAAGENT_HOST_ROOT` is the host-side persistent DataAgent runtime root.
  Compose mounts it to the fixed container path `/dataagent_runtime`.
- `DATAAGENT_SKILLS_DIR` is the only external skills source. The sandbox runner
  discovers its host source by inspecting its own `/app/.claude/skills` mount and
  uses that source for child skill binds.
- `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE` is the single runner image setting.
  Compose injects the same value as internal `DATAAGENT_SANDBOX_IMAGE`.
- `PORTAL_MCP_TOKEN` and `PORTAL_MCP_TOKEN_HEADER_NAME` are the shared
  frontdoor/client token settings. Compose injects them into both portal-mcp and
  DataAgent.

The container-visible runtime root is intentionally fixed at
`/dataagent_runtime`; it is not an external `.env` knob. Topic layout remains:

```text
/dataagent_runtime/<topic_id>/
  workspace/  -> child /mnt/workspace
  home/       -> child /mnt/home
  logs/       -> runner-side task logs
```

## Interfaces

Removed external variables with no compatibility fallback:

- `DATAAGENT_HOME_HOST_DIR`
- `DATAAGENT_SANDBOX_ROOT`
- `DATAAGENT_SANDBOX_HOST_ROOT`
- `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
- `DATAAGENT_SANDBOX_IMAGE`
- `DATAAGENT_SANDBOX_RUNNER_URL`
- `DATAAGENT_SANDBOX_NETWORK`
- `DATAAGENT_PORTAL_MCP_TOKEN`
- `DATAAGENT_PORTAL_MCP_TOKEN_HEADER_NAME`

New or retained external variables:

- `DATAAGENT_HOST_ROOT`
- `DATAAGENT_SKILLS_DIR`
- `OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE`
- `PORTAL_MCP_TOKEN`
- `PORTAL_MCP_TOKEN_HEADER_NAME`

Internal env still exists where needed by services, but it is set by compose and
is not documented as a user-facing `.env` setting.

## Tradeoffs

Removing compatibility fallback makes upgrades stricter: an old `.env` must be
updated before redeploying. The benefit is a single clear contract and no hidden
precedence rules between old and new names.
