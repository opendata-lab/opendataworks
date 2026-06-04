# DataAgent Sandbox Live Skills Design

## Current State

With `DATAAGENT_SANDBOX_MODE` enabled, `dataagent-sandbox-runner` starts one
child container per task. Skills are exposed to the child through the topic
workspace's `.claude/skills/<folder>` symlinks, whose target is the
`DATAAGENT_SKILL_LINK_ROOT` chosen by the runner:

- `/skills/<folder>` when `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` is set (runner binds
  that host dir read-only into the child as `/skills`)
- `/app/.claude/skills/<folder>` otherwise — the **image-baked** skills

Both the backend and runner already bind-mount the live skills
(`${DATAAGENT_SKILLS_DIR}:/app/.claude/skills`); offline packages repoint
`DATAAGENT_SKILLS_DIR` to `deploy/dataagent-runtime/skills`.

## Problem

When `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` is unset (the default), the child falls
back to `/app/.claude/skills` from the **runner image**, i.e. the skills baked at
image build time. Live edits and offline-package skill updates are invisible to
child task containers until the runner image itself is rebuilt. The operator has
to manually discover and set an absolute host path to fix it, which is easy to
miss.

## Solution

Make the live-skills child mount on by default.

The runner shares the host Docker socket, so at startup it inspects its own
container and reads the host source backing its `/app/.claude/skills` mount:

```text
docker inspect --format
  '{{range .Mounts}}{{if eq .Destination "/app/.claude/skills"}}{{.Source}}{{end}}{{end}}'
  <self-hostname>
```

The discovered absolute host path is cached and used as the child `/skills` bind
source. Resolution order in `_build_container_command`:

1. explicit `DATAAGENT_SANDBOX_HOST_SKILLS_DIR` (unchanged override)
2. auto-discovered runner skills mount source
3. image-baked `/app/.claude/skills` (last-resort fallback)

Discovery is best-effort: any failure (no socket, no such mount, non-container
backend) returns empty and the child keeps the previous image-skills fallback,
so nothing regresses when the runner is not container-backed.

## Tradeoffs

Pros:

- live and offline-package skills reach child task containers with no extra config
- explicit override preserved; fallback behavior unchanged on failure
- no new image build or named-volume plumbing

Cons:

- relies on the runner being able to inspect itself via the Docker socket (which
  it already holds); environments that hide the socket fall back to image skills
- assumes the runner's own skills are bind-mounted at `/app/.claude/skills`
  (true for the shipped Compose)

## Affected Stacks

- DataAgent backend: `dataagent/dataagent-backend/sandbox_runner_main.py`
- Deployment: `deploy/.env.example` (documented default-on behavior)
- Tests: `dataagent/dataagent-backend/tests/test_sandbox_runner_main.py`
