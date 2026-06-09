# DataAgent Sandbox Warm Container Reuse Design

## Background

In sandbox container mode (`DATAAGENT_SANDBOX_MODE` enabled and
`DATAAGENT_SANDBOX_BACKEND` set to `docker`/`podman`), every NL2SQL task runs in
its own child container. The sandbox runner executes one
`docker run --rm ... python sandbox_task_main.py` per task. The child reads a
single payload from stdin, executes exactly one task, prints the result, and the
process exits, after which `--rm` removes the container.

The consequence is that follow-up questions in the same conversation pay the
full cold-start cost again, even seconds apart:

- container scheduling and image layer setup
- Python interpreter startup
- Claude Agent SDK / CLI initialization and skill registration

This is wasteful for interactive NL2SQL, where users frequently ask several
follow-up questions in the same topic within a short window.

## Goal

Reuse a child container across follow-up tasks of the same conversation when a
follow-up arrives within an idle window, instead of recreating a child for every
task. Concretely:

- a child container stays alive after a task completes for a bounded idle TTL
- a follow-up task whose container requirements match an idle warm child reuses
  that child instead of creating a new container
- idle warm children past the TTL are killed and cleaned up automatically

## Current State

- `core/task_executor.py`
  - `execute_task_stream` dispatches to `_execute_task_stream_via_runner` when
    sandbox mode is on, which streams the run over HTTP to the runner.
- `sandbox_runner_main.py`
  - `run_sandbox_task` -> `_execute_task_stream_container` builds a per-task
    `docker run --rm` command via `_build_container_command`, sends the payload
    on stdin, then closes stdin, streams child stdout, and the container exits.
  - `RUNNING_CONTAINERS` maps `task_id -> (backend, container_name)` for cancel.
- `sandbox_task_main.py`
  - reads one payload (stdin or `DATAAGENT_TASK_PAYLOAD_B64`), runs
    `_execute_task_stream_local` once, prints the result, and exits.

Container mounts and isolation are fixed at `docker run` time:

- workspace bind-mount: `_topic_host_workspace(topic_id)`
- read-only skill bind-mounts: derived from the task's enabled skill folders
- isolation flags: network, read-only rootfs, tmpfs sizes, runtime uid/gid

Per-task data (provider, model, api key, question, history) travels in the
stdin payload and is resolved inside the child per run, so it is **not** fixed by
the container and does not constrain reuse.

## Design

Add an opt-out **warm child pool** in the sandbox runner. The child task process
becomes a long-lived loop that serves multiple payloads, and the runner keeps
idle children alive for an idle TTL so same-conversation follow-ups reuse them.

### Reuse key (container spec signature)

A warm child can only serve a task whose container requirements match what the
child was created with. The reuse key is a stable signature derived from the
mount and isolation inputs that are fixed at `docker run` time:

- backend and image
- topic host workspace path (so reuse never crosses topics)
- sorted enabled skill folder names (skill bind-mounts)
- isolation knobs: network, read-only rootfs, tmpfs size, runtime uid/gid

Two tasks with the same signature can share a warm child. Different topics, or
the same topic with a different enabled-skill set or isolation profile, produce
different signatures and therefore separate warm children. This keeps reuse
strictly within one conversation's isolation boundary.

### Child protocol (serve loop)

`sandbox_task_main.py` gains a serve-loop mode, selected when the runner sets
`DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT`:

- read newline-delimited JSON payloads from stdin
- for each payload: run `_execute_task_stream_local`, emit `record` lines, then
  print exactly one `result` line (same wire protocol as today)
- block on the next payload; exit cleanly on stdin EOF or when no new payload
  arrives within the child idle timeout (a self-protection cap slightly larger
  than the runner TTL, so an orphaned child cannot live forever)

Single-shot mode (no idle-timeout env) is unchanged for backward compatibility.

### Runner warm pool

The runner maintains an in-process pool of warm children keyed by container
name, with a signature index, a busy flag, and a `last_used` timestamp:

- **acquire**: under a pool lock, reuse an alive, idle child with a matching
  signature; otherwise create a new child (evicting an idle LRU child first if
  the pool is at capacity). The acquired child is marked busy.
- **run**: write the payload line to the child's stdin (kept open), stream
  stdout `record`/`result` lines for this task, and stop at the `result` line,
  leaving the child idle for the next task.
- **release**: mark the child idle and refresh `last_used`; if the child process
  died during the run, drop it from the pool and clean it up.
- **reaper**: a background task periodically kills and removes idle children
  whose `last_used` age exceeds the idle TTL, plus any dead children.
- **shutdown**: kill and remove all warm children on runner shutdown; the
  existing label-based startup cleanup still reaps stragglers.

A warm child serves one task at a time. Concurrent tasks with the same signature
simply create additional warm children, bounded by the max-pool-size cap.

### Cancellation

Cancellation keeps current semantics: the runner kills the warm child's
container. The run loop observes EOF and returns a suspended result, and release
drops the killed child from the pool. Mid-run interruption without destroying
the container is intentionally out of scope.

### Session persistence (topic dir split)

Warm reuse keeps the same container alive within the idle window, so resume
works there. But a follow-up after TTL eviction, a runner restart, or with reuse
disabled lands on a fresh container. Claude stores resume session transcripts
under `$HOME/.claude/projects`, so HOME must persist on the host, not in a
child-local tmpfs, otherwise the follow-up fails with "session not found".

The per-topic host directory is therefore split into two separately mounted
sibling subdirectories:

```text
<sandbox_root>/<topic>/            # topic root (never bind-mounted directly)
  ├─ workspace/   -> /mnt/workspace # agent cwd: uploads/, output/, .claude/skills
  └─ home/        -> /mnt/home      # persisted Claude HOME (resume transcripts)
```

- `home` is a sibling of `workspace`, not inside it, so the agent working in
  `/mnt/workspace` never sees session data, while both stay under `<topic>` for
  findability and are removed together when the topic is deleted.
- `/mnt/home` stays a distinct path from cwd `/mnt/workspace`, preserving project
  skill registration.
- The shared workspace contract `resolve_topic_workspace(topic)` (backend topic
  file APIs, local execution, runner bind source) now resolves to
  `<topic>/workspace`; `resolve_topic_root(topic)` resolves to `<topic>` and is
  used for deletion and orphan cleanup. Backend and runner must agree on this
  path or file I/O and warm reuse signatures diverge.
- Existing on-disk topics from the pre-split layout are not migrated; the split
  applies to new topics only.

## Interfaces

No public API or database schema changes.

New runner/runtime configuration (all under the existing sandbox namespace):

- `DATAAGENT_SANDBOX_REUSE_ENABLED` (bool, default `true`): when container
  backend is active, use the warm pool. When false, behavior is identical to
  today's one-shot-per-task container path.
- `DATAAGENT_SANDBOX_IDLE_TTL_SECONDS` (int, default `600`): idle lifetime of a
  warm child before the reaper removes it.
- `DATAAGENT_SANDBOX_MAX_WARM_CONTAINERS` (int, default `32`): soft cap on warm
  children; idle LRU children are evicted to make room.
- `DATAAGENT_SANDBOX_REAPER_INTERVAL_SECONDS` (int, default `30`): how often the
  reaper scans the pool.

Internal runtime contract additions:

- The runner sets `DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT` on warm children to
  switch the child into serve-loop mode; the value is the idle TTL plus a buffer.
- Warm children are named `dataagent-warm-<topic>-<sig>` and labelled with the
  existing sandbox labels (task id label is `warm`), so the existing
  label-based startup cleanup continues to reap them.
- The runner sends each warm payload as one newline-terminated JSON line and
  keeps the child's stdin open across tasks.

## Tradeoffs

- Reuse retains in-container state (tmpfs `HOME`, `/tmp`, process memory) across
  tasks of the same conversation. This matches the existing per-topic workspace
  bind-mount sharing and is desirable for session continuity; reuse never
  crosses topics or isolation profiles.
- Warm children hold resources during the idle window. This is bounded by the
  idle TTL, the max-pool-size cap with idle LRU eviction, and the child-side
  self-idle exit.
- The change is gated behind `DATAAGENT_SANDBOX_REUSE_ENABLED`. Disabling it
  restores the exact current one-shot container behavior as a single-layer
  fallback.
