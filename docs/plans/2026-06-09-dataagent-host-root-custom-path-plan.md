# DataAgent Host Root Custom Path Plan

Paired design: `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`

## Tasks

1. Launcher resolution (`scripts/start.sh`).
   - Add `resolve_dataagent_host_root()` mirroring `resolve_dataagent_skills_dir()`:
     read `DATAAGENT_HOST_ROOT` from `.env` (default `/dataagent_runtime`),
     normalize absolute values, resolve relative values against `deploy/`.
   - Before the compose `up`, set and `export DATAAGENT_HOST_ROOT` to the
     resolved absolute path and echo it so the chosen host directory is visible.

2. Documentation.
   - `deploy/.env.example`: document that `DATAAGENT_HOST_ROOT` accepts a custom
     absolute path or a `deploy/`-relative path (expanded by `start.sh`), and
     that raw `docker compose` needs an absolute value.
   - `deploy/README.md`: same note on both DataAgent runtime-root bullets.

3. Tests.
   - `tests/test_deepeval_packaging_hooks.py`: assert `start.sh` defines
     `resolve_dataagent_host_root` and exports the resolved value before compose,
     guarding against regressing the launcher contract.

## Touched files

- `scripts/start.sh`
- `deploy/.env.example`
- `deploy/README.md`
- `tests/test_deepeval_packaging_hooks.py`
- `docs/design/2026-06-09-dataagent-host-root-custom-path-design.md`
- `docs/plans/2026-06-09-dataagent-host-root-custom-path-plan.md`

## Verification

- `bash -n scripts/start.sh` and a shell unit check that
  `resolve_dataagent_host_root` returns an absolute path for both an absolute and
  a relative input.
- `pytest tests/test_deepeval_packaging_hooks.py` for the packaging/launcher
  assertions.
- Manual: with `DATAAGENT_HOST_ROOT=./dataagent-runtime/runtime`, confirm
  `start.sh` prints an absolute host path under `deploy/`.

## Rollout

- Pure launcher + docs change; no image rebuild required. Existing deployments
  with an absolute `DATAAGENT_HOST_ROOT` are unaffected.

## Backout

- Revert the `scripts/start.sh`, `.env.example`, and `README.md` edits. No data
  migration is involved.
