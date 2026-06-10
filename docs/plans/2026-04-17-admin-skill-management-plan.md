# Admin Skill Management Plan

## Summary

Upgrade Skill management from a single-active Skill model to multi-active Skill runtime management, simplify the list/detail UI, and add controlled ZIP import plus local imported Skill uninstall.

## Key Changes

- Persist `skill_runtime` in `da_agent_settings.raw_json` and derive document `enabled` from that map.
- Keep `skills_output_dir` as the primary compatibility Skill; bootstrap existing deployments with only that Skill enabled.
- Change `PUT /api/v1/dataagent/skills/runtime/{folder}` to toggle one Skill, reject disabling the last enabled Skill, and move the primary Skill when needed.
- Generate `dataagent/dataagent-backend/.runtime/enabled-skills/.claude/skills` with symlinks to enabled Skills only, and use it as the Claude project cwd for task executor NL2SQL runs.
- Add `DATAAGENT_ENABLED_SKILLS` and `DATAAGENT_ENABLED_SKILL_ROOTS`; keep `DATAAGENT_SKILL_ROOT` for existing scripts.
- Extract shared provider/prompt/env helpers into `core/agent_runtime.py` and remove the unused direct `stream_agent_reply` executor so only the task executor owns agent execution.
- Simplify frontend copy and layout: `已启用 / 未启用`, compact cards, custom detail title bar, merged left overview/file tree, `SKILL.md` first, lower-weight version history.
- Add `POST /api/v1/dataagent/skills/imports` for ZIP upload. Safely extract one Skill, reject unsafe paths/symlinks, index files, and keep a first-time imported Skill disabled by default.
- Support re-import of an existing managed Skill when the front matter `version` differs: replace files in place, prune stale document rows, and preserve the enabled state. Reject same-version re-import and built-in Skill overwrite.
- Add `DELETE /api/v1/dataagent/skills/{folder}` for managed Skill uninstall. Reject built-in Skill removal and last-enabled removal, remove indexed documents, clean `skill_runtime`, and reassign primary Skill when needed.
- Add list/detail UI actions for importing ZIP packages and uninstalling `source=managed` Skills with typed folder confirmation.

## Test Plan

- Backend:
  - contract tests for enriched document fields, runtime toggle API, import API, uninstall API, and uninstall error mapping
  - service tests for multi-enable, disabling one Skill, rejecting last-disable, and primary Skill reassignment
  - service tests for root ZIP import, folder ZIP import, unsafe archive rejection, same-version duplicate rejection, version-bump replacement, built-in overwrite rejection, missing `SKILL.md`, managed uninstall cleanup, built-in uninstall rejection, and last-enabled uninstall rejection
  - loader test proving runtime cwd exposes only enabled Skills
  - agent runtime-env test for enabled Skill env variables
  - task executor test covering the generated multi-Skill runtime cwd
- Frontend:
  - list tests for grouped Skill cards, enabled count, enable/disable calls, ZIP import, managed uninstall, and hidden bundled uninstall
  - detail tests for `SKILL.md` default selection, no `Back Back`, enable/disable behavior, and managed uninstall navigation
- Verification:
  - DataAgent targeted `pytest`
  - `nvm use`
  - Skill management `vitest`
  - frontend production build
  - Playwright screenshots for list and detail pages

## Assumptions

- No new database table in this iteration.
- At least one Skill must remain enabled.
- Multi-active means Claude Skill discovery sees all enabled Skills; primary `skills_output_dir` remains only as the script compatibility root for `DATAAGENT_SKILL_ROOT`.
- Only browser ZIP upload is supported for import; server paths, Git URLs, and marketplace discovery remain out of scope.
- Imported Skills default to disabled and are the only Skills that can be uninstalled.
