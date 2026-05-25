# DataAgent Agent Workspace Isolation Plan

## Goal

Give every intelligent-query profile an independent managed workspace and prevent runtime tool calls from searching parent directories or sibling profile state.

## Tasks

1. Add path-resolution regression coverage.
   - Assert every profile, including `agent_default`, resolves under `HOME/.dataagent/runtime/workspaces/<agent_id>`.
   - Assert custom agent IDs are sanitized before path use.

2. Add runtime boundary regression coverage.
   - Deny `Read` with `../secret.md`.
   - Deny absolute paths outside the current workspace and enabled Skill roots.
   - Allow paths inside the workspace.
   - Allow paths inside an enabled Skill root.
   - Deny `Bash` commands that reference `..`.

3. Update workspace path resolution.
   - Keep the API field `resolved_workdir`.
   - Change its value to the managed workspace path.
   - Keep legacy no-profile fallback execution on the existing global enabled-Skill cwd.

4. Add SDK PreToolUse boundary hooks.
   - Build allowed roots from the resolved workspace and enabled Skill roots.
   - Add the hook to `ClaudeAgentOptions`.
   - Keep existing allowed tool, MCP, permission mode, and env behavior unchanged.

5. Refresh focused docs and UI copy if touched.
   - Keep design and plan files in sync.
   - Prefer “工作空间” wording for visible profile workspace text.

6. Verify.
   - Run `dataagent/dataagent-backend/.venv-py313/bin/python -m pytest dataagent/dataagent-backend/tests/test_agent_profile_service.py dataagent/dataagent-backend/tests/test_agent_runtime.py dataagent/dataagent-backend/tests/test_task_executor.py -q`.
   - If frontend copy changes, run `nvm use` and the smallest relevant Vitest target.

## Backout

- Revert `resolved_workdir` to the previous `runtime/agents/<agent_id>` and default enabled-Skill cwd behavior.
- Remove the SDK hook wiring and boundary helper tests.
- Keep the documentation change reverted together with code so deployment notes match runtime behavior.

