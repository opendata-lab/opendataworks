# DataAgent Workspace Path Simplification Design

## Current State

DataAgent persisted runtime state under a deeply nested path:

```text
HOME=/tmp/dataagent-home
topic workspace: /tmp/dataagent-home/.dataagent/runtime/topics/<topic_id>/
legacy filtered cwd: /tmp/dataagent-home/.dataagent/runtime/enabled-skills
```

The `/tmp/dataagent-home` host dir, the `.dataagent` hidden dir, and the `runtime`
grouping dir are three stacked layers before the meaningful `topics/<topic_id>` part.
Because `HOME` is already DataAgent-specific, `.dataagent/runtime` is redundant.

## Problem

The path is long and repetitive in logs, env defaults, bind mounts, and the
`PreToolUse` workspace-boundary messages. Operators asked to shorten it.

## Solution

Collapse the whole prefix into a single top-level directory `/workspaces`:

```text
HOME=/workspaces
bind mount: ${DATAAGENT_HOME_HOST_DIR:-/workspaces}:/workspaces
topic workspace: /workspaces/<topic_id>/
  .claude/
    projects/
    skills/
```

`DATAAGENT_SANDBOX_ROOT` and `DATAAGENT_SANDBOX_HOST_ROOT` default to `/workspaces`,
so a topic workspace is just `<root>/<topic_id>`. The runner derives the same host
root via `resolve_topic_workspace("_placeholder_").parent`, which stays correct.

`/workspaces` doubles as the container `HOME` and the bind-mount point. Topic runs
still override `HOME`/`PWD`/`cwd` to the per-topic dir, so `.claude` session state
lands under `/workspaces/<topic_id>/.claude`, not the shared root.

The non-env code fallback in `topic_workspace._resolve_sandbox_root` becomes
`HOME/workspaces` (kept namespaced so local dev never resolves topic dirs into a
developer's bare `$HOME`). In containers the explicit `DATAAGENT_SANDBOX_ROOT=/workspaces`
env drives the value, so root equals `HOME`.

The legacy shared `enabled-skills` cwd and `DATAAGENT_RUNTIME_PROJECT_CWD` are dead on
the live SDK path (no live callers; superseded by per-topic workspaces). They are
repointed to `/workspaces/enabled-skills` only for env/default consistency, not
revived. Full removal is tracked as a separate cleanup.

## Tradeoffs

Pros:

- shorter, readable paths everywhere; one root instead of four stacked segments
- `HOME == sandbox root == bind mount` is a single mental model

Cons:

- `/workspaces` is a top-level host dir; the bind source moves from `/tmp/...`
- because root equals `HOME` in containers, the (currently unused)
  `cleanup_orphan_topic_workspaces` helper would iterate the home root directly;
  it has no live caller, but a future caller must scope to topic dirs only

## Affected Stacks

- DataAgent backend: `core/topic_workspace.py`, `prompts/data_agent_system_prompt.md`
- Deployment: `dataagent/dataagent-backend/Dockerfile`, `Dockerfile.runner`,
  `deploy/docker-compose.dev.yml`, `deploy/docker-compose.prod.yml`,
  `deploy/.env.example`, `deploy/README.md`
- Tests: `tests/test_agent_runtime.py`
