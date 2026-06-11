# DataAgent Host Root Custom Path Design

## Current State

`DATAAGENT_HOST_ROOT` is the single host-side persistent runtime root introduced
by the config consolidation. Compose mounts it to the fixed container path
`/dataagent_runtime` for `dataagent-home-init`, `dataagent-backend`, and
`dataagent-sandbox-runner`:

```yaml
volumes:
  - ${DATAAGENT_HOST_ROOT:-/dataagent_runtime}:/dataagent_runtime
```

The sandbox runner additionally receives the value as an env var and uses it to
tell the host Docker/Podman daemon where to bind-mount each topic's
`workspace/` and `home/` into per-task child containers
(`sandbox_runner_main._host_sandbox_root()` →
`Path(raw).expanduser().resolve()`).

Any custom `DATAAGENT_HOST_ROOT` (even an absolute one) is broken, and a
relative value is doubly broken. There are two independent defects:

1. Runner conflates two roots. `sandbox_runner_main` used `DATAAGENT_HOST_ROOT`
   for both (a) the filesystem prep it performs *inside its own container*
   (`mkdir` the topic workspace/home, create child skill mount-target dirs,
   `chown`, write per-task logs) and (b) the bind-mount `source=` for child
   containers (resolved by the host Docker daemon). The runner sees the
   persistent volume at the fixed container path `/dataagent_runtime`, so the
   filesystem prep must target that path. These coincide only when
   `DATAAGENT_HOST_ROOT == /dataagent_runtime` — the default. With any custom
   root, the runner's `mkdir`/`chown`/log writes hit a non-existent path in its
   own overlay filesystem instead of the mounted volume: nothing is written
   under the custom host dir, the child's `.claude/skills/<folder>` mount targets
   never get created, and the child exits with "warm sandbox container exited
   without a result".

2. Relative values are re-resolved in the wrong place. Compose resolves a
   relative bind source relative to the project directory (`deploy/`), so
   `dataagent-backend` persists under `deploy/<rel>`. But the runner forwards the
   raw relative string and resolves it inside its own container (CWD `/app`), so
   `./runtime` becomes the container-local `/app/runtime` — a wrong child bind
   source. `scripts/start.sh` already normalizes `DATAAGENT_SKILLS_DIR` via
   `resolve_dataagent_skills_dir()` but had no equivalent for the runtime root.

## Problem

Operators cannot point the DataAgent runtime root at a custom host directory:
the sandbox runner writes its topic prep to the wrong (container-local) path, so
the custom directory stays empty and sandbox tasks fail. Relative values fail
additionally because the runner re-resolves them against its own filesystem.

## Scope

- Separate the two roots in the sandbox runner: a fixed container runtime root
  (`/dataagent_runtime`) for the runner's own filesystem operations, and the
  host root (`DATAAGENT_HOST_ROOT`) only for child bind-mount sources.
- Normalize a relative `DATAAGENT_HOST_ROOT` to an absolute host path in
  `scripts/start.sh` before invoking compose, mirroring the skills-dir handling.
- Keep the container-visible runtime root fixed at `/dataagent_runtime` and the
  compose contract unchanged.

Affected stacks: DataAgent runner (`dataagent/dataagent-backend/sandbox_runner_main.py`)
and deployment (`scripts/start.sh`, `deploy/.env.example`, `deploy/README.md`).

## Solution

Primary fix — runner root separation (`sandbox_runner_main.py`):

- Add `_container_runtime_root()` returning the fixed `CONTAINER_RUNTIME_ROOT`
  (`/dataagent_runtime`) and container-path helpers
  `_topic_container_workspace/home/logs`. Use these for every filesystem
  operation the runner performs itself: workspace/home `mkdir`, child skill
  mount-target prep, `chown`, and per-task log writes.
- Keep `_host_sandbox_root()` (`DATAAGENT_HOST_ROOT`) and `_topic_host_workspace/home`
  strictly for the child bind-mount `source=` strings handed to the host daemon.
- Because the runner mounts the same volume the backend does, files it writes to
  `/dataagent_runtime/<topic>/...` land on the host at
  `DATAAGENT_HOST_ROOT/<topic>/...`, which is exactly the child bind source.

Supporting fix — launcher normalization (`scripts/start.sh`):

- `resolve_dataagent_host_root()` normalizes absolute values and resolves
  relative values against `deploy/`; the resolved value is `export`ed so Compose
  interpolation (shell env beats `--env-file`) gives every
  `${DATAAGENT_HOST_ROOT:-/dataagent_runtime}` mount and the runner's forwarded
  env the same absolute host path.

Together: the operator sets one value in `.env`; all services and per-task child
binds agree on one host directory, and the runner's own prep always lands on the
mounted volume regardless of where that volume is on the host.

Direct `docker compose` invocations that bypass `start.sh` still require an
absolute value; this is documented in `.env.example` and `deploy/README.md`.

## Interfaces

- No new or removed `.env` variables. `DATAAGENT_HOST_ROOT` semantics widen from
  "absolute host path" to "absolute or `deploy/`-relative host path when launched
  via `start.sh`".
- Container runtime root stays fixed at `/dataagent_runtime` (an internal module
  constant, not an `.env` knob).

## Tradeoffs

- The relative-path convenience only applies through `start.sh`. Resolving a
  relative host path inside the runner container is impossible (the container
  cannot know the host's `deploy/` location), so the launcher is the correct
  single place to normalize it; raw `docker compose` keeps the explicit
  absolute-path requirement instead of a second, unreliable resolution layer.
- The container runtime root is kept as a fixed constant (test-overridable by
  monkeypatch) rather than a new setting, honoring the consolidation design's
  "container root is not an external knob" rule.
