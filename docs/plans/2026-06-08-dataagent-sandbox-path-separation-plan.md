# DataAgent Sandbox Path Separation Plan

## Goal

Separate sandbox child Claude HOME from the task workspace so project skills
under `./.claude/skills/*` are registered reliably by Claude Code.

## Implementation

- Update `dataagent/dataagent-backend/sandbox_runner_main.py`.
  - Change child workspace target to `/mnt/workspace`.
  - Change child skills root to `/mnt/workspace/.claude/skills`.
  - Set `HOME=/mnt/home`.
  - Add child-local tmpfs mount for `/mnt/home`.
  - Stop injecting `PWD`, `DATAAGENT_WORKSPACE_DIR`,
    `DATAAGENT_WORKSPACE_PREPARED`, and `DATAAGENT_SANDBOX_ROOT`.

- Update `dataagent/dataagent-backend/sandbox_task_main.py`.
  - Pass `Path.cwd()` to `_execute_task_stream_local()` as the prepared
    workspace.

- Update `dataagent/dataagent-backend/core/task_executor.py`.
  - Add an internal `prepared_workspace_dir` parameter.
  - Use that parameter instead of reading workspace location from env.
  - Preserve inherited `HOME`; do not replace it with the topic workspace.
  - Remove `DATAAGENT_WORKSPACE_DIR` and `DATAAGENT_WORKSPACE_PREPARED` from the
    SDK env.

- Update tests.
  - Cover separated HOME/workspace behavior in local SDK execution.
  - Cover child command mounts and env.
  - Cover sandbox task handoff from `Path.cwd()`.

- Update deployment docs.
  - Document child path semantics separately from backend/runner service paths.

## Verification

Run from `dataagent/dataagent-backend`:

```bash
. .venv-py313/bin/activate
pytest tests/test_task_executor.py tests/test_sandbox_runner_main.py tests/test_sandbox_task_main.py -q
```

Recommended smoke after unit tests:

- start DataAgent backend and frontend;
- send a platform assistant prompt that requires
  `opendataworks-business-knowledge` and `opendataworks-platform-tools`;
- verify SDK records contain `Launching skill: ...` and no
  `Unknown skill`.

## Backout

Revert the three runtime files and tests in this plan. The previous behavior
will restore child workspace at `/app` and HOME aliasing, but may reintroduce
`Unknown skill` on Claude Code 2.1.156.
