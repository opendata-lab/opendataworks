# DataAgent Topic Workspace Isolation Design

## Current State

DataAgent executes Claude Agent SDK runs through topic/task records. `da_agent_topic` owns the user-visible conversation and stores the current resumable SDK session id in `chat_conversation_id`. `da_agent_task` represents one execution attempt with queue, lease, event, cancellation, and recovery state.

Before this change, SDK `cwd` was prepared under a profile-level runtime workspace. That made filesystem behavior depend on agent profile rather than the actual conversation. It also allowed agents to gravitate toward the shared container `HOME=/tmp/dataagent-home`.

## Problem

File isolation should match user conversation semantics. A topic should keep its own workspace and SDK session files across turns, while different topics should not see each other's files or `.claude` state. Task/run records are internal execution attempts and should not create separate workspace or session layers.

## Solution

Use `topic_id` as the only workspace key:

```text
HOME/.dataagent/runtime/topics/<topic_id>/
  .claude/
    projects/
    skills/
```

The topic root is the full workspace. It is passed as SDK `cwd`, `HOME`, `PWD`, and `DATAAGENT_WORKSPACE_DIR` in the local execution path. Claude SDK session files therefore live under the topic root in `.claude/projects/<sanitized-cwd>/`.

Enabled skills are exposed through `.claude/skills`. The workspace helper copies each enabled skill folder from the discovery root into `<workspace>/.claude/skills/<folder>` as a real directory (refreshed on every prepare). Copies are used instead of symlinks because some Claude Code / Agent SDK skill discovery does not follow symlinked skill directories; copying also keeps the workspace skill in sync with same-name re-imports. When the discovery root and the workspace skills directory are the same path (e.g. the sandbox child where the skill is bind-mounted at `/app/.claude/skills/<folder>`), the copy is skipped because the skill is already in place.

The existing `PreToolUse` boundary hook remains as a second guard. It allows current workspace paths and enabled skill roots, and rejects parent-directory traversal or paths outside the workspace.

## Runner Interface

The backend supports a sandbox runner mode behind `DATAAGENT_SANDBOX_MODE`. This is a master/worker split: `dataagent-backend` owns topic/task coordination, while `dataagent-sandbox-runner` owns execution. When enabled, `task_executor` streams task execution through `DATAAGENT_SANDBOX_RUNNER_URL` using internal NDJSON messages:

- `{"type": "record", "record": ...}`
- `{"type": "result", "result": ...}`

The runner has its own Dockerfile and image (`opendataworks-dataagent-runner`) so execution permissions can diverge from the backend service. In container backend mode, the runner starts one child Docker/Podman container per task and streams that child process output back to the backend. The child container runs `sandbox_task_main.py`, calls the local SDK executor, and emits the same NDJSON event contract.

The runner service may mount the host Docker socket or use a Podman-compatible command, but the task child container does not receive that socket. The child container receives only:

- the current topic host directory mounted read/write as `/workspace`
- a host skills directory mounted read-only as `/skills`
- the runner image's bundled skills under `/app/.claude/skills` only as a last-resort fallback

By default the host skills bind is on: the runner inspects its own
`/app/.claude/skills` mount via the shared Docker socket and reuses that host
source for the child, so child containers see live and offline-package-updated
skills instead of the image-baked copy. `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
overrides the source; only when neither an explicit value nor a discoverable
runner mount exists does the child fall back to the image skills. See
`2026-06-04-dataagent-sandbox-live-skills-design.md`.

Because Docker bind mounts use host-visible paths, Compose deploys DataAgent home as a host bind directory by default:

```text
DATAAGENT_HOME_HOST_DIR=/workspaces
DATAAGENT_SANDBOX_HOST_ROOT=/workspaces
```

The default child network mode is `container:opendataworks-dataagent-sandbox-runner`, so task containers share the runner's service network namespace and can resolve the same internal service names without receiving Docker control privileges.

## Cleanup

Each task child container is ephemeral. The runner starts it with `--rm`, so normal completion removes the container object automatically. On cancellation, stream disconnect, or runner-side exception, the runner kills the child container and still relies on `--rm` for container object cleanup. Topic workspaces are not deleted when a task ends because they belong to the topic conversation.

Child containers are labeled with `dataagent.sandbox.managed_by=dataagent-sandbox-runner`, plus task/topic labels for inspection. When the runner starts, it scans for stale labeled child containers from a previous runner crash or restart and removes them with `rm -f`.

Deleting a topic deletes its topic workspace best-effort after the database row is removed. A reusable orphan workspace cleanup helper removes directories whose `topic_id` is no longer active.

## Tradeoffs

This change gives topic-level workspace and SDK session isolation in the application runtime. With `DATAAGENT_SANDBOX_MODE` enabled and a Docker/Podman backend available to the runner, task containers get a hard mount view of only the current topic workspace. CPU, memory, network egress, and secret isolation remain follow-up controls for the runner backend.
