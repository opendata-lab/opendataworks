# 2026-04-27 DataAgent Runtime CWD Permission Design

## Current State

The DataAgent task executor prepares a filtered Claude Agent SDK project cwd before each run. The cwd contains only enabled skills under `.claude/skills`.

Today `prepare_enabled_skills_project_cwd()` hardcodes that filtered cwd under `dataagent-backend/.runtime/enabled-skills`.

The Docker Compose deployment runs `dataagent-backend` as `DATAAGENT_RUNTIME_UID:GID`, defaulting to `1000:1000`. The image content under `/app/dataagent-backend` is owned by root, while `/tmp/dataagent-home` is the writable persisted runtime volume.

## Problem

When a user asks an intelligent-query question, the backend prepares the SDK project cwd and tries to create `/app/dataagent-backend/.runtime`. Under the default non-root deployment this fails with permission denied.

The failure happens before or during task execution, so the UI can show a save/run failure even though the session database and skills volume are otherwise available.

## Scope

In scope:

- DataAgent backend runtime cwd resolution
- Docker Compose and image runtime environment defaults
- deployment documentation for where runtime files are written
- focused regression tests for the cwd location

Out of scope:

- changing skill discovery or skill-edit storage
- changing Claude SDK session id persistence
- redesigning DataAgent task coordination

## Proposed Solution

Use a writable runtime cwd by default:

- default filtered project cwd: `HOME/.dataagent/runtime/enabled-skills`
- in Docker Compose, `HOME` remains `/tmp/dataagent-home`, backed by the existing `dataagent-home` volume
- expose `DATAAGENT_RUNTIME_PROJECT_CWD` for deployments that need to pin the cwd explicitly
- keep the enabled-skill filtering behavior unchanged: the runtime cwd still contains symlinks from `.claude/skills/<folder>` to the configured skills discovery root

## Tradeoffs

Pros:

- works with the existing non-root container user
- keeps runtime-only files out of the application install directory
- reuses the persisted `dataagent-home` volume already required by SDK session resume

Cons:

- the SDK project cwd path changes for deployments that previously wrote successfully to `dataagent-backend/.runtime`
- old SDK local session files are still in `HOME`, but their project subdirectory may differ if the cwd path changed

## Affected Stacks

- DataAgent backend: `core/skill_discovery.py`, `config.py`
- Deployment: `Dockerfile`, `deploy/docker-compose.dev.yml`, `deploy/docker-compose.prod.yml`, `deploy/.env.example`, `deploy/README.md`
- Tests: `dataagent/dataagent-backend/tests/test_skill_discovery.py`
