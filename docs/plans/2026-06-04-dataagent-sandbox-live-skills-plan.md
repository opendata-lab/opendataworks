# DataAgent Sandbox Live Skills Plan

## Goal

Default sandbox child task containers to the live (and offline-package-updated)
skills instead of the runner image's baked-in copy, without requiring operators
to set an absolute host skills path.

## Tasks

1. Auto-discover the runner's own host skills mount.
   - Add `_discover_host_skills_dir()` in `sandbox_runner_main.py`: `docker/podman
     inspect <self-hostname>` for the host source of the `/app/.claude/skills`
     mount; best-effort, returns "" on any failure.
   - Cache the result at startup in `lifespan` (module global).

2. Use it by default.
   - Add `_resolve_host_skills_dir(cfg)`: explicit `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
     wins, else the auto-discovered source, else image fallback.
   - Wire it into `_build_container_command` for the `/skills` bind and
     `DATAAGENT_SKILL_LINK_ROOT`.

3. Docs + tests.
   - Document the default-on behavior in `deploy/.env.example`.
   - Update the runner child-mount contract in
     `2026-06-04-dataagent-topic-workspace-isolation-design.md`.
   - Add regression tests: discovery parses the inspect source; resolver
     precedence; child command mounts the auto-discovered dir and sets
     `DATAAGENT_SKILL_LINK_ROOT=/skills`.

## Verification

- `pytest tests/test_sandbox_runner_main.py` — 7 passed (4 existing + 3 new).
- `py_compile sandbox_runner_main.py` — ok.
- Live `docker compose up` with `DATAAGENT_SANDBOX_MODE` on, editing a skill file
  on the host and confirming the child task container sees the edit, remains
  unrun: Docker unavailable in the change environment.

## Backout

- Revert `sandbox_runner_main.py` to read `dataagent_sandbox_host_skills_dir`
  directly with the image fallback, and drop the discovery/resolver helpers and
  their tests. No schema or API changes.
