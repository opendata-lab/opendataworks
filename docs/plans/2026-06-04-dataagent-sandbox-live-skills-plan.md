# DataAgent Sandbox Scoped Skills Plan

> 2026-06-08 update: child path semantics were revised by
> `2026-06-08-dataagent-sandbox-path-separation-plan.md`. Use that plan for
> current child workspace, HOME, and skill target paths.

## Goal

Make sandbox child containers clean and deterministic: they run from `/app`, use
`SKILLS_ROOT_DIR=/app/.claude/skills`, and only receive the skills enabled by the
current assistant.

## Tasks

1. Unify skills root configuration.
   - Add `skills_root_dir` / `SKILLS_ROOT_DIR`.
   - Make `resolve_skill_discovery_root_dir()` read only `SKILLS_ROOT_DIR`.
   - Fail fast when the root is empty, missing, or not `.claude/skills`.

2. Remove image-baked skills.
   - Move backend and runner service code to `/opt/dataagent-backend`.
   - Drop `COPY dataagent/.claude` from backend and runner Dockerfiles.
   - Keep runtime skills mounted by compose at `/app/.claude/skills`.

3. Scope child mounts by assistant.
   - Mount topic workspace to child `/app`.
   - Resolve enabled folders from `agent_snapshot.skill_folders` when present.
   - Bind only enabled skill folders to `/app/.claude/skills/<folder>:ro`.
   - Remove `/skills`, `/workspace`, and `DATAAGENT_SKILL_LINK_ROOT` from the
     child contract.

4. Keep offline package behavior intact.
   - Keep `scripts/start.sh` auto-populating `DATAAGENT_SANDBOX_HOST_SKILLS_DIR`
     from `DATAAGENT_SKILLS_DIR`.
   - Keep `scripts/create-offline-package.sh --exclude='*-assistant'` unchanged.

## Verification

- Focused pytest for skill discovery, topic workspace, sandbox runner, task
  executor, agent runtime, admin skill routes, Dockerfile contracts, and package
  hooks.
- `bash -n` for changed shell scripts.
- `py_compile` for changed DataAgent Python modules.
- Real offline-package sandbox E2E remains a separate smoke if Docker/provider
  credentials are available.

## Backout

Revert the Dockerfile path move, restore the old child workspace mount target,
and reintroduce the previous skill mount contract. No schema migration is
required.
