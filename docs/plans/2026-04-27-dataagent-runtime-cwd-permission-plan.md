# 2026-04-27 DataAgent Runtime CWD Permission Plan

## Goal

Stop intelligent-query runs from writing runtime-only SDK project files under `/app/dataagent-backend`, so the default non-root container deployment can ask questions without permission denied errors.

## Tasks

1. Add regression coverage.
   - Assert enabled-skill runtime cwd defaults to `HOME/.dataagent/runtime/enabled-skills`
   - Verify enabled-skill symlinks are still exposed under the runtime cwd

2. Update runtime cwd resolution.
   - Add a `DATAAGENT_RUNTIME_PROJECT_CWD` setting
   - Resolve relative override paths from `dataagent-backend`
   - Default to the writable `HOME` runtime path

3. Update deployment defaults and docs.
   - Set the runtime cwd explicitly in compose and image defaults
   - Document that `dataagent-home` now stores both SDK session files and the filtered runtime cwd

4. Verify.
   - Run targeted `tests/test_skill_discovery.py`
   - Confirm no untracked `.runtime` directory remains under `dataagent-backend`

## Backout

- Remove the `DATAAGENT_RUNTIME_PROJECT_CWD` setting
- Revert `prepare_enabled_skills_project_cwd()` to the old backend-local `.runtime` path
- Remove the compose/image environment default and documentation updates
