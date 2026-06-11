# AGENTS.md

## Purpose

OpenDataWorks is a unified data portal for metadata management, workflow orchestration, lineage analysis, and intelligent query.

When working in this repository, optimize for:

- preserving clear boundaries between the main Java backend, the Vue frontend, and the Python-based DataAgent intelligent-query module
- keeping workflow, metadata, and intelligent-query behavior aligned with the current deployment model under `deploy/`
- preferring targeted, low-risk changes over broad refactors or speculative abstractions

## Tech Stack & Runtime

- `backend/`: main business backend for metadata, workflow, lineage, and platform APIs
- `frontend/`: main web application
- `dataagent/dataagent-backend/`: FastAPI-based intelligent-query backend
- `dataagent/.claude/skills/dataagent-nl2sql/`: intelligent-query skill bundle
- `deploy/`: Docker Compose, environment templates, and image/build assets

### Frontend stack

- `Vue 3` + `Vite 5`
- `Vue Router 4`
- `Pinia`
- `Element Plus` as the primary existing UI component library
- `Sass` for current style organization
- `Tailwind CSS` is an approved additive styling layer for new frontend work when utility-first styling improves implementation speed or consistency
- `ECharts`, `Vue Flow`, `CodeMirror`, `Axios`

### Backend stack

- Main backend:
  - `Java 8`
  - `Spring Boot 2.7`
  - `Spring MVC` + `WebFlux`
  - `MyBatis-Plus`
  - `MySQL 8`
  - `Flyway`
  - `Lombok`
  - `Hutool`
  - `JSqlParser`
  - `Apache POI`
- Intelligent-query backend:
  - `Python`
  - `FastAPI`
  - `Pydantic`
  - `PyMySQL`
  - `Alembic`
  - `AnyIO`

### Frontend stack rules

- Do not rewrite established `Element Plus` or `Sass` surfaces just to force Tailwind adoption.
- When introducing Tailwind CSS, do it incrementally and only for the touched UI area.
- Keep frontend changes aligned with the existing Vue component structure and routing/state patterns already in the repo.

### Node / nvm baseline

- This repository uses `nvm`.
- Before running any frontend command (`vite`, `npm --prefix frontend ...`, build/dev), load and switch Node from `.nvmrc`:
  - `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
- If the target Node version is missing, install it first:
  - `nvm install`

### Frontend execution rule

- Always run `nvm use` successfully before frontend build/test/dev commands.
- If a frontend command fails with Node engine/runtime errors, retry only after `nvm use`.

### Python baseline

- Do not assume the first `python3` on `PATH` is the correct interpreter for `dataagent/dataagent-backend`.
- In this workspace, prefer a dedicated local virtualenv when it already exists for DataAgent work.
- For this repository, default to `dataagent/dataagent-backend/.venv-py313` when it exists.
- `claude-agent-sdk` requires Python `>=3.10`. Do not try to install or run the DataAgent execution path with the Xcode Python `3.9.6`.
- If no project virtualenv is present, prefer the Xcode Python at `/Applications/Xcode.app/Contents/Developer/usr/bin/python3` for local DataAgent commands, because the host `/usr/local/bin/python3` may point to a different interpreter without the required packages.
- Before running backend commands, verify the interpreter can import the required runtime modules for the touched path, at minimum:
  - `fastapi`
  - `uvicorn`
  - `alembic`
  - `pymysql`
  - `anyio`
- For any execution path that reaches the real agent runtime, also verify:
  - `claude_agent_sdk`
- If the preferred interpreter cannot import the required modules, install the missing packages into the chosen environment before claiming the backend cannot be started.
- If the runtime reports `claude-agent-sdk 未安装`, first verify the active interpreter and Python version before concluding that the package is actually missing. In this repo, that error usually means the wrong interpreter was used.

## Architecture Overview

- `backend/src`: core platform logic for data assets, workflow orchestration, and lineage
- `frontend/src`: main UI for workflows, lineage, Data Studio, and intelligent-query entrypoints
- `dataagent/dataagent-backend`: NL2SQL runtime, session persistence, prompt assembly, and skill integration
- `docs/design`: active technical design documents for medium and large changes
- `docs/plans`: execution plans paired with design documents
- `docs/handbook`: long-lived handbook and feature documentation
- `docs/reports`: reports, validation notes, and post-change summaries

## Working Rules

- Prefer one verified primary path by default.
- Only add compatibility branches when real version or environment differences are confirmed.
- Keep fallback minimal, explicit, and single-layer.
- Avoid repeated or cascading fallback chains and duplicate guard branches.
- Prefer small, targeted changes that preserve existing module boundaries.
- When changing a public API, schema, runtime contract, or deployment behavior, update the related docs in the same change when the impact is medium or large.
- Do not move skill-specific behavior into shared runtime modules unless the behavior is genuinely generic.

## Design & Plan Workflow

- Change sizing:
  - Small changes: single-file or local fixes without schema, API, architecture, deployment, or cross-module impact may proceed directly.
  - Medium and large changes: anything involving architecture, data model, public interfaces, cross-frontend/backend coordination, deployment behavior, timeout strategy, or operational risk must start with design and plan documents.
- Active document directories:
  - design docs live in `docs/design/`
  - implementation plans live in `docs/plans/`
- Naming rules:
  - design: `YYYY-MM-DD-<topic>-design.md`
  - plan: `YYYY-MM-DD-<topic>-plan.md`
  - design and plan for the same change must share the same `<topic>` slug
- Authoring order:
  - write design before plan
  - keep design focused on current state, problem, scope, solution, interfaces, and tradeoffs
  - keep plan focused on executable tasks, touched files, verification, rollout, and backout
  - identify the affected frontend, backend, DataAgent, and infrastructure stacks when that context matters for implementation
- Reuse rules:
  - if a matching active topic already exists, update it instead of creating a duplicate
  - if scope drifts during implementation, update design and plan before continuing code changes
- Applicability:
  - repository-wide rules apply to the whole repo
  - modules may define stricter local rules in addenda, but may not weaken the repository-wide rules

## Module Addenda

### Intelligent Query module rules

- Scope: these rules apply to the NL2SQL / intelligent-query flow under `dataagent/dataagent-backend` and the skill bundle under `dataagent/.claude/skills/dataagent-nl2sql`.
- Keep generic agent and runtime modules skill-agnostic. Do not hardcode skill-specific script names, CLI subcommands, prompt recipes, or deployment paths in shared modules such as `core/nl2sql_agent.py`.
- The skill bundle is the single source of truth for question-routing playbooks, script invocation patterns, exact required arguments, and recovery rules. If behavior is specific to intelligent-query, put it in `SKILL.md`, `reference/*`, or skill-local scripts.
- Never assume deployment-only absolute paths such as `/app/scripts/...`. Resolve from `skills_output_dir`, skill root, or another runtime-derived root.
- For intelligent-query, do not add an extra wrapper layer unless a verified runtime limitation requires it. Prefer direct execution of skill-local scripts via `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...`.
- The current canonical invocation contract is:
  - runtime exposes `DATAAGENT_PYTHON_BIN` and `DATAAGENT_SKILL_ROOT`
  - executable form is `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/<name>.py" ...`
  - executable references in docs must use the full form above, not bare `run_sql.py` or guessed relative paths
- Avoid duplicating invocation contracts across layers. When changing script entrypoints or required parameters, update the skill docs, skill template or sync generator, and regression tests in the same change.
- Prefer one stable invocation contract. Do not keep multiple equivalent command forms unless a real environment difference has been verified.

### Intelligent Query timeout rules

- Treat intelligent-query timeouts as a chain, not a single knob. When changing one timeout, review backend agent timeout, SQL timeout, reverse-proxy stream timeout, and frontend streaming behavior together.
- For the current SSE chat flow, backend timeout is the primary model-run cutoff. Do not assume frontend axios timeout controls streaming requests without checking the actual streaming implementation.
- Reverse-proxy read and send timeout must be greater than the backend agent timeout, otherwise proxy timeout will hide the real backend failure mode.
- Before increasing timeout, first reduce unnecessary turns, duplicate reads, path guessing, and repeated tool retries. Raise timeout only after the execution path is already simplified.
- Distinguish conversation lifetime from single-run lifetime:
  - a chat session may stay alive for a long time
  - one NL2SQL run still needs bounded execution time
  - long-lived chat does not justify an unbounded single request
- Prefer a two-level timeout model for intelligent-query:
  - total run timeout for one request
  - idle or progress timeout for detecting stalled runs with no new stream or tool output
- For interactive NL2SQL, prefer a longer bounded run timeout over the current short default. As a rule of thumb, `300-420s` total is more appropriate than `180s` once the execution path is already simplified.
- For queries that may exceed interactive limits, prefer asynchronous or background execution instead of endlessly extending the synchronous request timeout.

### Intelligent Query verification rules

- For medium and large intelligent-query changes that span `frontend/`, `dataagent/dataagent-backend/`, task coordination/runtime execution, schema, or deployment wiring, targeted unit or contract tests are not enough on their own.
- Before claiming the change is fully validated, run at least one local end-to-end smoke flow when the environment is available.
- The minimum local intelligent-query smoke flow is:
  - start the required local services for the touched path, typically MySQL, `dataagent-backend`, and the frontend when UI behavior changed
  - submit one real NL2SQL request through the actual entrypoint affected by the change
  - verify task creation, event streaming or polling, terminal status persistence, and result rendering or topic recovery for the changed path
- If the change specifically affects async or background execution, the smoke flow must cover:
  - request accepted with `task_id`
  - coordinator pickup and execution
  - `/api/v1/nl2sql/tasks/{task_id}` status transition
  - `/api/v1/nl2sql/tasks/{task_id}/events` or stream consumption
  - final assistant message persistence
- If a local full-flow test was not run, do not describe the change as fully verified. State exactly which layer was verified and which end-to-end path remains untested.

### Intelligent Query local smoke method

- Use the local Docker MySQL as the default smoke database when it is available. In this repo, `deploy/docker-compose.dev.yml` publishes MySQL on `127.0.0.1:3316`; `3306` is typical only for standalone MySQL or `docker-compose.prod.yml`.
- Use local Docker Redis as the default task-coordination backend when it is available. Prefer publishing it on `127.0.0.1:6379`.
- Treat local Docker MySQL as the default first assumption for intelligent-query validation. Do not ask the user to restate this setup unless connection checks against the expected local ports have already failed.
- Treat local Docker Redis as the default first assumption for intelligent-query validation. Do not ask the user to restate this setup unless connection checks against `127.0.0.1:6379` or Docker availability have already failed.
- If Docker CLI is unavailable but Podman is installed, treat Podman as the default container runtime fallback for local Redis bootstrap and other local containerized smoke dependencies.
- When connecting to local Docker MySQL through a published port, prefer `MYSQL_HOST=127.0.0.1` over `localhost` to avoid misleading failures caused by host resolution differences.
- When connecting to local Docker Redis through a published port, prefer `REDIS_HOST=127.0.0.1` over `localhost`.
- Prefer the dedicated session schema `dataagent` for local intelligent-query smoke tests. Do not mix smoke-session data into unrelated business schemas when a dedicated schema can be created.
- If `dataagent` schema or user is missing on a reused local MySQL volume, initialize it first with the repository-standard credentials:
  - database: `dataagent`
  - user: `dataagent`
  - password: `dataagent123`
  - privileges: `SELECT` on `opendataworks.*`, full privileges on `dataagent.*`
- For DataAgent full-flow smoke, prefer a dedicated Python `3.10+` virtualenv instead of the host Python when local package versions are uncertain.
- Standard local intelligent-query smoke sequence:
  - ensure local container CLI is available for Redis bootstrap:
    - prefer `docker`
    - if `docker` is unavailable, use `podman`
  - if Redis is not already listening on `127.0.0.1:6379`, prefer:
    - `docker start odw-local-redis`
    - or `docker run -d --name odw-local-redis -p 6379:6379 redis:7.2-alpine`
    - if using Podman instead:
      - `podman start odw-local-redis`
      - or `podman run -d --name odw-local-redis -p 6379:6379 docker.io/library/redis:7.2-alpine`
  - create or reuse a local Python `3.10+` virtualenv
  - in this repository, prefer reusing `dataagent/dataagent-backend/.venv-py313` when it exists
  - if no `3.10+` virtualenv is available, fall back to `/Applications/Xcode.app/Contents/Developer/usr/bin/python3` only for non-agent tasks after confirming imports succeed there
  - install `dataagent/dataagent-backend/requirements.txt`
  - set runtime env to local MySQL:
    - `MYSQL_HOST=127.0.0.1`
    - `MYSQL_PORT=3316` when using `deploy/docker-compose.dev.yml` (or the actual published port in your local setup)
    - `MYSQL_USER=dataagent`
    - `MYSQL_PASSWORD=dataagent123`
    - `MYSQL_DATABASE=opendataworks`
    - `SESSION_MYSQL_DATABASE=dataagent`
    - `REDIS_HOST=127.0.0.1`
    - `REDIS_PORT=6379`
  - run `alembic upgrade head` in `dataagent/dataagent-backend`
  - ensure `da_agent_settings` in `dataagent` contains a valid provider selection and runtime DB config before starting services
  - start `uvicorn main:app`
  - do not start a separate `worker_main.py`; the task coordinator is started inside `main.py`
  - drive the smoke through real HTTP requests, not mocked store calls
  - do not stop at `/api/v1/nl2sql/health`; also verify `POST /api/v1/nl2sql/topics` succeeds, because `health` can be green while topic/task-store MySQL access is still broken

### Intelligent Query environment defaults

- For local UI validation of intelligent-query, default to starting:
  - frontend dev server with `nvm use`
  - `dataagent-backend`
- When switching the DataAgent Python environment or restarting services after fixing dependencies, stop any stale `main.py` processes first. Old backend processes can keep using the wrong interpreter and surface outdated errors such as `claude-agent-sdk 未安装` even after the correct environment has been fixed.
- Do not ask the user to repeat frontend `nvm`, Python interpreter choice, or Docker MySQL as long as these repository defaults are applicable.
- Do not ask the user to repeat Docker Redis as long as the repository default `127.0.0.1:6379` assumption is still applicable.
- If startup fails, report the concrete failed assumption directly, for example:
  - MySQL port unreachable
  - no usable container CLI for Redis bootstrap (`docker` and `podman` both unavailable)
  - Redis port unreachable
  - selected Python interpreter missing required packages
  - wrong Python major/minor for `claude-agent-sdk`
  - provider credentials or `da_agent_settings` not configured
- Minimum smoke scenarios for async intelligent-query changes:
  - background run accepted: verify `POST /api/v1/nl2sql/tasks` or `POST /api/v1/nl2sql/tasks/deliver-message` returns `accepted=true` and a `task_id`
  - task pickup: verify task status transitions `waiting -> running -> success|failed|suspended`
  - event persistence: verify `GET /api/v1/nl2sql/tasks/{task_id}/events` or `GET /api/v1/nl2sql/tasks/{task_id}/events/stream` returns the terminal event stream
  - message persistence: verify the final assistant message is present in `GET /api/v1/nl2sql/topics/{topic_id}/messages`
  - cancel path: verify cancellation through `POST /api/v1/nl2sql/tasks/{task_id}/cancel`
- Recommended smoke prompts:
  - minimal run liveness check: `你好，请直接回复 smoke-ok。`
  - real NL2SQL path: `最近 30 天工作流发布次数趋势`
- Cleanup after smoke:
  - delete smoke sessions created during the test
  - stop local backend and worker processes
  - keep any schema or user bootstrap needed for repeatability unless the test explicitly requires cleanup
- Report the exact environment used in the verification note:
  - MySQL host and schema
  - Redis host and bootstrap method
  - Python interpreter or virtualenv
  - whether real provider credentials and real model execution were used
  - which smoke scenarios passed or were skipped

## Verification Expectations

- Run the narrowest relevant verification before claiming completion.
- Frontend changes:
  - run `nvm use` first
  - then run the smallest relevant frontend build, test, or lint command
- Backend or platform changes:
  - run the smallest relevant backend test or compile check for the touched area
- DataAgent changes:
  - prefer focused `pytest` coverage for the touched module or contract
  - if code paths are sensitive to prompt or runtime configuration, add or update a targeted regression test
- Cross-layer changes:
  - if a change crosses frontend, backend, worker, persistence, or deployment boundaries, add a local end-to-end smoke verification on top of targeted tests when the required environment can be started locally
  - if that smoke verification cannot be run, report the missing local full-flow coverage explicitly
- Docs-only changes:
  - verify directory placement, file naming, cross-links, and consistency with repository rules
- If verification was not run, say so explicitly.
