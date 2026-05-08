# DataAgent Skill Runtime Simplification Plan

## Summary

Reduce DataAgent Skill runtime ownership to SDK discovery only, while preserving admin file management and multi-Skill enablement.

## Key Changes

- Add `core/skill_discovery.py` for primary root resolution, discovery root resolution, SDK cwd resolution, and enabled-Skill symlink generation.
- Remove backend parsing/validation of `assets/*.json` and delete static semantic/LF/exporter modules.
- Remove startup default bundle generation and static runtime reload.
- Rename document indexing behavior to `reindex_documents_from_disk`; list/detail APIs continue to refresh the admin document index automatically.
- Remove `POST /api/v1/dataagent/skills/sync`, `SkillSyncResponse`, frontend `syncSkills()`, and the SkillStudio refresh button.
- Keep runtime env contracts for `DATAAGENT_SKILL_ROOT`, `DATAAGENT_ENABLED_SKILLS`, and `DATAAGENT_ENABLED_SKILL_ROOTS`.

## Test Plan

- Backend:
  - `pytest tests/test_skill_discovery.py`
  - admin route/service tests for document listing, imports, runtime toggles, and removed sync POST
  - task executor and agent runtime tests for enabled-Skill cwd/env behavior
- Frontend:
  - SkillStudio and SkillDetailView Vitest coverage for list/import/enable/uninstall and absence of refresh UI
- Smoke:
  - Confirm `/api/v1/dataagent/skills/documents` lists the builtin Skill.
  - Confirm a real task run can discover enabled Skills through the generated SDK cwd when credentials are configured.

## Assumptions

- The builtin `dataagent-nl2sql` Skill is deployed with the application.
- At least one Skill remains enabled.
- Agent SDK owns Skill file loading and interpretation.
