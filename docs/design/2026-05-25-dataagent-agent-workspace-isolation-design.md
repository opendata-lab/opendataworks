# DataAgent Agent Workspace Isolation Design

## Current State

DataAgent profile execution already passes a managed `cwd` to Claude Agent SDK and generates enabled Skill symlinks under `<cwd>/.claude/skills`.

The current profile cwd layout is inconsistent:

- the default profile uses `HOME/.dataagent/runtime/enabled-skills`
- custom profiles use `HOME/.dataagent/runtime/agents/<agent_id>`

That `agents` directory name is easy to confuse with Claude subagents. More importantly, the SDK receives only `cwd`, allowed tool names, and permission mode. The runtime does not currently enforce that file-oriented tools stay inside the current profile cwd.

## Problem

Different intelligent-query agents should work in separate profile workspaces. During a run, an agent must not search upward into parent directories or inspect sibling profile state.

Relying only on Claude SDK `cwd` is not enough. Tools such as `Read`, `LS`, `Glob`, `Grep`, and `Bash` can receive relative paths, absolute paths, or commands that refer to `..` or another directory outside the run workspace.

## Scope

In scope:

- DataAgent managed workspace path resolution for agent profiles.
- SDK runtime options for enforcing tool filesystem boundaries.
- Regression tests for workspace paths and parent-directory denial.
- Documentation for the new runtime layout.

Out of scope:

- Database schema changes.
- Arbitrary administrator-supplied filesystem workspaces.
- Changing Skill storage or Skill editing APIs.
- Container-level sandboxing or OS namespace isolation.

## Solution

Use one workspace layout for every DataAgent profile:

```text
HOME/.dataagent/runtime/
  workspaces/
    agent_default/
      .claude/
        skills/
    agent_opendataworks/
      .claude/
        skills/
    <custom_agent_id>/
      .claude/
        skills/
```

The backend continues to expose `resolved_workdir` for API compatibility, but its value becomes the profile workspace path.

For each task:

1. Resolve the selected profile workspace from the profile `agent_id`.
2. Generate the enabled Skill symlinks under `<workspace>/.claude/skills`.
3. Pass the workspace as SDK `cwd`.
4. Register a `PreToolUse` runtime hook that denies filesystem escape attempts:
   - deny any `Read`, `LS`, `Glob`, or `Grep` path input containing a parent-directory segment
   - deny any resolved file path outside the current workspace and explicitly enabled Skill roots
   - deny `Bash` commands that include parent-directory path segments
   - deny absolute paths in `Bash` unless they are inside an allowed root or are the configured DataAgent Python executable

Enabled Skill roots are allowed because the workspace exposes them through symlinks under `.claude/skills`; the hook still resolves symlinks and only allows roots for Skills enabled for the current profile.

## Interfaces

No public API shape changes are required.

- `resolved_workdir` remains in agent profile responses.
- The visible path changes from `.../runtime/agents/<agent_id>` to `.../runtime/workspaces/<agent_id>`.
- Runtime logs continue to report the SDK `cwd`.

## Tradeoffs

The SDK session project path changes for profile runs because `cwd` changes. Existing historical topics still keep their DataAgent topic/task records, but Claude SDK local resume files tied to the old cwd path may not be reused.

The runtime hook is an application-level guard, not a container sandbox. It prevents normal SDK tool calls from escaping the workspace, but it does not replace OS-level hardening. Container deployments should still run the DataAgent backend as a non-root user with a narrow writable home volume.

The Bash validation is intentionally conservative for parent-directory segments and absolute paths. This matches the intelligent-query contract, where platform scripts must be invoked through `"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...` instead of probing arbitrary filesystem locations.

## Verification

- Unit tests for profile workspace path resolution.
- Unit tests for file tool parent-directory and outside-root denial.
- Unit tests for allowed workspace and enabled Skill access.
- Task executor test proving SDK options include workspace boundary hooks.
- Targeted pytest for `test_agent_profile_service.py`, `test_agent_runtime.py`, and `test_task_executor.py`.

