# DataAgent Home Permission Design

## Current State

The Compose deployment runs `dataagent-backend` as a non-root user, defaulting to
`DATAAGENT_RUNTIME_UID:GID = 1000:1000`. DataAgent persists runtime state under
`HOME=/tmp/dataagent-home`, which is provided by a host bind mount:

```text
${DATAAGENT_HOME_HOST_DIR:-/tmp/dataagent-home}:/tmp/dataagent-home
```

At runtime the backend prepares topic workspaces under
`/tmp/dataagent-home/.dataagent/runtime/topics/<topic_id>/` and the SDK project cwd
under `/tmp/dataagent-home/.dataagent/runtime/enabled-skills`. The backend image
runs `chmod 1777 /tmp/dataagent-home`, but that only affects the image layer.

## Problem

When a host bind source directory does not already exist, Docker creates it as
`root:root` with mode `755`. The bind mount then masks the image's `chmod 1777`.
The non-root backend user (`1000`) therefore cannot create `.dataagent` under
`/tmp/dataagent-home`, and intelligent-query fails with:

```text
permission denied /tmp/dataagent-home/.dataagent
```

The failure happens while preparing the topic workspace
(`prepare_topic_workspace` -> `runtime_skills_dir.mkdir(...)`), so the UI shows a
run failure even though MySQL, Redis, and the skills volume are otherwise healthy.

The previous `2026-04-27` permission work moved the runtime cwd out of the
root-owned install directory into `HOME`, but `HOME` itself is still a
root-owned bind mount, so the same denial reappears at the home root.

## Scope

In scope:

- ensure the bind-mounted DataAgent home is writable by the non-root runtime user
- Compose dev/prod deployment wiring
- deployment documentation and `.env.example`

Out of scope:

- changing topic workspace layout or SDK session persistence
- changing the sandbox runner / child-container execution contract
- application-code changes in `core/topic_workspace.py` (a process running as
  `1000` cannot grant itself ownership of a root-owned mount)

## Solution

Add a one-shot init service `dataagent-home-init` to dev and prod Compose. It runs
as root, reuses the backend image, mounts the same DataAgent home bind, and before
the backend/runner start it:

- ensures `/tmp/dataagent-home/.dataagent/runtime/topics` (the per-topic workspace
  root) exists
- `chown -R ${DATAAGENT_RUNTIME_UID:-1000}:${DATAAGENT_RUNTIME_GID:-1000}` the home
- `chmod -R u+rwX` the home

The init only fixes ownership of the home root. It does not pre-create any
skill-partitioned directory: under the current topic-workspace model each topic/agent
exposes its own enabled skills under `<topic>/.claude/skills`, prepared at runtime by
`prepare_topic_workspace`. The legacy shared `runtime/enabled-skills` cwd
(`prepare_enabled_skills_project_cwd`, `DATAAGENT_RUNTIME_PROJECT_CWD`) is no longer
on the live SDK execution path, so the init must not assume it.

`dataagent-backend` and `dataagent-sandbox-runner` gain a
`depends_on: dataagent-home-init: condition: service_completed_successfully` edge.
The runner runs as root and can write regardless, but it depends on the init too so
it does not pre-create root-owned directories before ownership is fixed.

This keeps a single, explicit ownership-fix path at the deployment layer and stays
consistent if the operator changes `DATAAGENT_RUNTIME_UID/GID`.

## Tradeoffs

Pros:

- works with prebuilt published images; no image rebuild required
- image-agnostic and idempotent; safe to re-run on every `up`
- one explicit place that owns home permissions, driven by the same UID/GID vars

Cons:

- adds one short-lived service and a startup ordering edge
- root is briefly used for the chown step (init only), while services keep their
  existing non-root/root user settings

## Affected Stacks

- Deployment: `deploy/docker-compose.dev.yml`, `deploy/docker-compose.prod.yml`,
  `deploy/.env.example`, `deploy/README.md`
