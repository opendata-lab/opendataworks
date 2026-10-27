# AGENTS.md

## Purpose

OpenDataWorks is a unified data portal for metadata management, workflow orchestration, lineage analysis, and intelligent query.

When working in this repository, optimize for:

- preserving clear boundaries between the main Java backend and Vue frontend, with DataAgent maintained in its independent repository
- keeping workflow, metadata, and intelligent-query behavior aligned with the current deployment model under `deploy/`
- preferring targeted, low-risk changes over broad refactors or speculative abstractions

## Tech Stack & Runtime

- `backend/`: main business backend for metadata, workflow, lineage, and platform APIs
- `frontend/`: main web application
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

### Frontend stack rules

- Do not rewrite established `Element Plus` or `Sass` surfaces just to force Tailwind adoption.
- When introducing Tailwind CSS, do it incrementally and only for the touched UI area.
- Keep frontend changes aligned with the existing Vue component structure and routing/state patterns already in the repo.

### Frontend visual structure rules

- Keep visible frames shallow. Two levels of border are the normal maximum: the outer panel or card, and the content's own frame.
- `Element Plus` content components usually bring their own frame (`el-table` with `border`, `el-descriptions` with `border`, code blocks, metric cards). Do not wrap them in another bordered or tinted card.
- Every wrapper element must earn its place. Drop a container that has a single child and provides no title, no actions, and no layout of its own.
- Prefer a section title plus whitespace over a card frame when grouping sibling blocks. When frames are removed, increase the gap between blocks so grouping stays readable.
- Use the available horizontal space. A key-value block that fills its container should render multiple columns (`el-descriptions` with `:column="2"` or more) instead of one narrow column; give long text items a full-width span.
- For panes inside a user-resizable pane, pick the column count from the measured container width and fall back to fewer columns when it gets narrow, instead of hardcoding a count that only works at one width.
- When changing the visual structure of one tab or pane, apply the same treatment to its sibling tabs or panes in the same container. Do not leave one surface flat and its neighbors nested.

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

- In this workspace, prefer a dedicated local virtualenv when it already exists for DataAgent work.
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
- `docs/design`: active technical design documents for medium and large changes
- `docs/plans`: execution plans paired with design documents
- `docs/handbook`: long-lived handbook and feature documentation
- `docs/reports`: reports, validation notes, and post-change summaries
- `docs/TODO.md`: the single backlog of confirmed but deferred work

## Working Rules

- Prefer one verified primary path by default.
- Only add compatibility branches when real version or environment differences are confirmed.
- Keep fallback minimal, explicit, and single-layer.
- Avoid repeated or cascading fallback chains and duplicate guard branches.
- Prefer small, targeted changes that preserve existing module boundaries.
- When changing a public API, schema, runtime contract, or deployment behavior, update the related docs in the same change when the impact is medium or large.
- Do not move skill-specific behavior into shared runtime modules unless the behavior is genuinely generic.
- Design for the simplest solution that works, delivered in one step. Do not add transition periods, phased rollouts, or optional branches unless the user asks for them.

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

## Backlog

- `docs/TODO.md` is the single place for deferred work. Record every confirmed follow-up there: items a review or verification found but the current change does not fix, known gaps of a shipped feature, pre-existing failures noticed in passing, and environment blockers that left verification undone.
- Do not leave deferred work only in code comments, commit messages, chat summaries, or scattered TODO files.
- Each entry states the current state, what needs to be done, and its source with the date it was found, and links the related design, plan, or report.
- Before starting a backlog item, check whether it is medium or large; if so, write the design and plan first and link them from the entry.
- When an item is done, move it to the "已完成" section of `docs/TODO.md` with a link to the result instead of deleting it.

## DataAgent

DataAgent 源码、运行时与通用 Skill 已迁往独立仓库 [opendataagent](https://github.com/MingkeVan/opendataagent)。本仓库仅维护 `odw-mcp-servers/opendataworks/` 与 `odw-skills/`，通过普通 HTTP MCP 与 Skill 导入接入。

## Verification Expectations

- Run the narrowest relevant verification before claiming completion.
- Frontend changes:
  - run `nvm use` first
  - then run the smallest relevant frontend build, test, or lint command
- Backend or platform changes:
  - run the smallest relevant backend test or compile check for the touched area
  - for mapper-layer SQL contract tests, prefer an in-memory DuckDB (`jdbc:duckdb:`) so the test needs no local MySQL and no Spring context; the existing `@SpringBootTest` integration tests require a real MySQL on `localhost:3306` and are not a fit for a focused SQL guard
  - lock the production SQL itself, not a copy: reflect the `@Select`/`@Update` value off the mapper method, swap `#{param}` for a JDBC `?`, then execute it against DuckDB (see `TableTaskRelationMapperWriteTablesSqlTest`)
  - keep the DuckDB driver at a Java-8-compatible line (`org.duckdb:duckdb_jdbc:0.10.x`) to match the backend baseline; write DuckDB-portable DDL in the test (`BIGINT`/`VARCHAR`/`INTEGER`, no MySQL-only types)
- DataAgent changes:
  - prefer focused `pytest` coverage for the touched module or contract
  - if code paths are sensitive to prompt or runtime configuration, add or update a targeted regression test
- Cross-layer changes:
  - if a change crosses frontend, backend, worker, persistence, or deployment boundaries, add a local end-to-end smoke verification on top of targeted tests when the required environment can be started locally
  - if that smoke verification cannot be run, report the missing local full-flow coverage explicitly
- Docs-only changes:
  - verify directory placement, file naming, cross-links, and consistency with repository rules
- If verification was not run, say so explicitly.
