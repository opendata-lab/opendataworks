# DataAgent Topic Workspace Isolation Plan

## Goal

Make DataAgent file and Claude SDK session state topic-scoped instead of agent-profile-scoped, while preserving task as an internal run/execution record.

## Tasks

1. Add topic workspace helpers.
   - Resolve workspaces from `topic_id` only under `DATAAGENT_SANDBOX_ROOT` or `HOME/.dataagent/runtime/topics`.
   - Prepare `.claude/skills` for enabled skills.
   - Support host skill links for local execution and `/skills/<folder>` links for runner/container execution.
   - Add delete and orphan cleanup helpers.

2. Move SDK execution to topic workspaces.
   - Replace agent-profile runtime cwd selection in `task_executor`.
   - Pass topic workspace as SDK `cwd`.
   - Inject `HOME`, `PWD`, and `DATAAGENT_WORKSPACE_DIR` with the topic workspace path.
   - Keep agent snapshot ownership for tools, skills, MCP services, prompts, max turns, and env vars.

3. Add sandbox runner API seam.
   - Add settings for sandbox mode, runner URL, root, host root, image, backend, child network, and optional host skills bind.
   - Add NDJSON streaming runner client in `task_executor`.
   - Add `sandbox_runner_main.py` with internal run and cancel endpoints.
   - Add a dedicated runner Dockerfile/image so the architecture is backend master + runner worker.
   - Add `sandbox_task_main.py` as the child container task entrypoint.
   - Implement Docker/Podman CLI child execution in the runner with only the current topic mounted as `/workspace`.
   - Run child containers as ephemeral `--rm` containers and label them for startup stale-container cleanup.
   - Keep sandbox mode disabled by default.

4. Preserve boundaries and cleanup.
   - Keep `PreToolUse` hooks on local execution.
   - Add regression coverage for `/tmp/dataagent-home` denial.
   - Add regression coverage for runner child container labels and startup stale-container cleanup.
   - Delete topic workspace when a topic is deleted.

5. Update deployment and docs.
   - Add runner environment knobs to dev/prod Compose.
   - Add a runner service entrypoint and separate runner image.
   - Include the runner image in image build scripts, CI release matrix, offline package creation, and offline image loading.
   - Bind DataAgent home from a host-visible path so the runner can mount exact topic directories into child containers.
   - Mount Docker socket into the runner service only; do not mount it into task child containers.
   - Document topic workspace semantics and runner/backend ownership.

## Verification

- `dataagent/dataagent-backend/.venv-py313/bin/python -m pytest dataagent/dataagent-backend/tests/test_topic_workspace.py dataagent/dataagent-backend/tests/test_agent_runtime.py dataagent/dataagent-backend/tests/test_task_executor.py dataagent/dataagent-backend/tests/test_sandbox_runner_main.py dataagent/dataagent-backend/tests/test_runner_dockerfile.py -q`
- `dataagent/dataagent-backend/.venv-py313/bin/python -m py_compile dataagent/dataagent-backend/config.py dataagent/dataagent-backend/core/topic_workspace.py dataagent/dataagent-backend/core/task_executor.py dataagent/dataagent-backend/core/topic_task_store.py dataagent/dataagent-backend/sandbox_runner_main.py dataagent/dataagent-backend/sandbox_task_main.py`
- For a full validation run, start MySQL, Redis, dataagent-backend, and optionally dataagent-sandbox-runner; create one topic, write a file in one turn, read it in the next turn, and verify a second topic cannot read it.

## Backout

- Revert `task_executor` to the previous agent-profile workspace path.
- Remove sandbox runner settings, client, and service entrypoint.
- Keep topic/task schema unchanged because this change does not add database columns.
