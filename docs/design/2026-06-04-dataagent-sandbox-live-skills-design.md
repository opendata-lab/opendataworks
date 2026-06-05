# DataAgent Sandbox Scoped Skills Design

## Current State

`dataagent-backend` and `dataagent-sandbox-runner` run from container images, while
platform-managed skills are mounted at runtime from `DATAAGENT_SKILLS_DIR`.
Sandbox mode delegates task execution to the runner, which starts one child
container per task.

The execution contract is now:

- service code lives in `/opt/dataagent-backend`
- runtime skills root is `SKILLS_ROOT_DIR=/app/.claude/skills`
- child task workspace is mounted at `/app`
- child task skills are mounted directly under `/app/.claude/skills/<folder>`

## Problem

The old child contract had multiple skill locations (`/skills`,
`/app/.claude/skills`, and `/workspace/.claude/skills`) plus an image-baked
fallback. In offline-package deployment, platform-imported skills could be shown
as enabled on a custom agent but still be invisible to the child task container,
causing Claude Code skill lookup failures.

The fallback also made child containers less clean: a task could see a full
skills tree or stale image-baked skills instead of only the skills enabled for
the current assistant.

## Solution

Use one skills root concept and scope child mounts by assistant.

Backend and runner compose services set:

```text
SKILLS_ROOT_DIR=/app/.claude/skills
```

Both services mount the live platform skills tree there for administration,
import, indexing, and runner validation:

```text
${DATAAGENT_SKILLS_DIR}:/app/.claude/skills
```

The host source of the runner's own `/app/.claude/skills` mount is the bind
source each child container must use. It is resolved in this order:

1. explicit `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` (set directly, or filled from
   `DATAAGENT_SKILLS_DIR` by `scripts/start.sh` before compose startup); else
2. auto-discovered at runner startup by inspecting the runner's own
   `/app/.claude/skills` mount via the shared Docker socket
   (`_discover_host_skills_dir`).

This host path is generally **not** visible inside the runner container (the
runner sees the same skills at `/app/.claude/skills`). Therefore the runner
validates each enabled folder (`is_dir` + `SKILL.md`) against a runner-visible
root — the explicit host path only if it happens to be visible (same-path
mount), otherwise the runner's own `/app/.claude/skills` mount — and uses the
host path solely to construct the child bind-mount source. This avoids the
failure mode where a valid host path fails an in-runner `is_dir()` check.

For each task, the runner resolves the current assistant's
`agent_snapshot.skill_folders` and mounts only those folders into the child:

```text
<host-skills>/<folder>:/app/.claude/skills/<folder>:ro
```

The child workspace is mounted to `/app`, with `HOME`, `PWD`,
`DATAAGENT_WORKSPACE_DIR`, and `DATAAGENT_SANDBOX_ROOT` all set to `/app`.
The child runs `/opt/dataagent-backend/sandbox_task_main.py`, so mounting the
workspace at `/app` cannot hide the service code.

`resolve_skill_discovery_root_dir()` reads only `SKILLS_ROOT_DIR`; it no longer
derives the root from `skills_output_dir`.

## Tradeoffs

Pros:

- child containers see only the skills enabled for the current assistant
- no image-baked skills fallback
- one runtime skills root: `/app/.claude/skills`
- offline-package and platform-imported skills are used without rebuilding images

Cons:

- sandbox tasks with enabled skills fail fast if `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
  is missing or points to an invalid host path
- direct compose startup without `scripts/start.sh` must provide the host skills
  path explicitly

## Affected Stacks

- DataAgent backend runtime configuration and skill discovery
- Sandbox runner child container command construction
- DataAgent backend and runner Dockerfiles
- Dev/prod compose deployment files
