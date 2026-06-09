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

An **absolute** custom `DATAAGENT_HOST_ROOT` works end to end. A **relative**
custom value does not:

- Compose resolves a relative bind source relative to the project directory
  (`deploy/`), so `dataagent-backend` persists topics under
  `deploy/<rel>` correctly.
- The runner forwards the same raw relative string and resolves it inside its
  own container, whose CWD is `/app`. `./runtime` becomes `/app/runtime`, a
  container-local path, so the child bind source is wrong and sandbox tasks
  fail.

`scripts/start.sh` already normalizes `DATAAGENT_SKILLS_DIR` to an absolute host
path via `resolve_dataagent_skills_dir()`, but there is no equivalent for
`DATAAGENT_HOST_ROOT`. The offline package and `.env.example` use relative paths
for other mounts, so a relative `DATAAGENT_HOST_ROOT` is a natural thing for an
operator to try, and it silently breaks the sandbox path.

## Problem

Operators cannot reliably point the DataAgent runtime root at a custom host
directory: absolute paths work, but relative paths break the sandbox runner's
child bind mounts because the runner re-resolves the value against its own
container filesystem instead of the host.

## Scope

- Make `DATAAGENT_HOST_ROOT` support custom host directories, including relative
  paths, consistently across `dataagent-home-init`, `dataagent-backend`, and the
  sandbox runner's child binds.
- Keep the container-visible runtime root fixed at `/dataagent_runtime`.
- No backend code or compose contract changes; the fix lives in the launcher and
  documentation.

Affected stacks: deployment (`scripts/start.sh`, `deploy/.env.example`,
`deploy/README.md`). DataAgent backend/runner code is unchanged.

## Solution

Resolve `DATAAGENT_HOST_ROOT` to a host absolute path in `scripts/start.sh`
before invoking compose, mirroring `resolve_dataagent_skills_dir()`:

- Absolute values are normalized as-is.
- Relative values are resolved against `deploy/`.
- The resolved value is `export`ed so Compose interpolation (which gives shell
  environment variables precedence over the `--env-file`) uses the same absolute
  host path for every `${DATAAGENT_HOST_ROOT:-/dataagent_runtime}` mount and for
  the runner's forwarded `DATAAGENT_HOST_ROOT` env. The runner therefore resolves
  the same absolute host path it would bind for child containers.

This keeps a single source of truth: the operator sets one value in `.env`, and
all three services plus the per-task child binds agree on one host directory.

Direct `docker compose` invocations that bypass `start.sh` still require an
absolute value; this is documented in `.env.example` and `deploy/README.md`.

## Interfaces

- No new or removed `.env` variables. `DATAAGENT_HOST_ROOT` semantics widen from
  "absolute host path" to "absolute or `deploy/`-relative host path when launched
  via `start.sh`".
- Container runtime root stays fixed at `/dataagent_runtime`.

## Tradeoffs

- The relative-path convenience only applies through `start.sh`. Resolving a
  relative host path inside the runner container is not possible (the container
  cannot know the host's `deploy/` location), so the launcher is the correct
  single place to normalize it. Documenting the absolute-path requirement for
  raw `docker compose` keeps the contract explicit instead of adding a second,
  unreliable resolution layer.
