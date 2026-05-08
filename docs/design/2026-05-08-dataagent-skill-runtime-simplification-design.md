# DataAgent Skill Runtime Simplification Design

## Current State

DataAgent already executes intelligent-query requests through Claude Agent SDK with a generated project cwd that exposes enabled Skills under `.claude/skills`.

The backend still kept older static-skill responsibilities: generating default bundle skeletons, validating `assets/*.json`, loading a `SkillsBundle`, reloading a semantic layer, and carrying LF validation/compiler modules. Those paths no longer feed the main task executor.

## Problem

The backend duplicated work that belongs to the Agent SDK and made Skill management harder to reason about. Administrators need file management, import, enablement, and uninstall. Runtime only needs deterministic SDK discovery. Static asset parsing created a second source of truth for Skill content.

## Solution

- Replace static bundle loading with a lightweight discovery helper that resolves:
  - primary Skill root from `skills_output_dir`
  - discovery root under `.claude/skills`
  - SDK project cwd
  - filtered enabled-Skill symlinks for execution
- Keep `DATAAGENT_SKILL_ROOT`, `DATAAGENT_ENABLED_SKILLS`, and `DATAAGENT_ENABLED_SKILL_ROOTS` unchanged.
- Stop generating default Skill files at backend startup. The deployed builtin Skill must exist on disk.
- Keep management APIs for list/detail/edit/rollback/import/enable/uninstall.
- Remove the manual Skill sync API and UI button. The document list automatically reindexes files from disk.
- Delete the static semantic/LF/exporter modules that are not part of the main execution path.
- Allow `claude_cli_path`, `DATAAGENT_CLAUDE_CLI_PATH`, or `CLAUDE_CLI_PATH` to override the SDK CLI path when a local bundled Claude CLI is unusable. Leaving it unset preserves SDK default discovery.

## Interfaces

Removed:

- `POST /api/v1/dataagent/skills/sync`
- `SkillSyncResponse`

Kept:

- `GET /api/v1/dataagent/skills/documents`
- `GET /api/v1/dataagent/skills/documents/{document_id}`
- `PUT /api/v1/dataagent/skills/documents/{document_id}`
- `POST /api/v1/dataagent/skills/imports`
- `PUT /api/v1/dataagent/skills/runtime/{folder}`
- `DELETE /api/v1/dataagent/skills/{folder}`

## Tradeoffs

The backend no longer validates Skill asset schemas before execution. That is intentional: the SDK reads Skill content, and malformed Skill docs should be handled as Skill authoring/runtime issues rather than DataAgent startup blockers.

The document list still maintains `da_skill_document` and versions for admin editing, but it is an index over files, not a semantic runtime bundle.
