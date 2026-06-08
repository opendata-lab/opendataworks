# DataAgent Sandbox Path Separation Design

## Background

DataAgent sandbox tasks prepare enabled skills under the task workspace at
`./.claude/skills/*` and then run Claude Code from that workspace. With Claude
Code 2.1.156, setting `HOME` to the same directory as the SDK `cwd` makes the
Skill tool registry fail to register those project skills. The files remain
visible to normal filesystem tools, but `Skill` calls return
`Unknown skill`.

The verified minimal reproduction is:

- `cwd=<topic workspace>` and `HOME=<topic workspace>`: project skill launch
  fails with `Unknown skill`.
- `cwd=<topic workspace>` and `HOME` set to a distinct directory: project skill
  launch succeeds.

## Design

Sandbox child containers use separate path semantics:

- `/mnt/workspace`: current task workspace and project skills root.
- `/mnt/home`: Claude HOME, mounted as child-local tmpfs and not persisted.
- `/tmp`: ordinary scratch space; only mounted explicitly when read-only rootfs
  mode is enabled.

The child workspace remains the source of project skills:

```text
/mnt/workspace/.claude/skills/<folder>/SKILL.md
```

Claude user-level state goes under:

```text
/mnt/home/.claude.json
/mnt/home/.claude/*
```

The sandbox runner no longer injects workspace-discovery environment variables
into the child. The child process already starts with `--workdir
/mnt/workspace`, so `sandbox_task_main.py` passes `Path.cwd()` to the local task
executor as the prepared workspace.

## Interfaces

No public API or database schema changes.

Internal runtime contract changes:

- `HOME` must not equal the task workspace.
- `SKILLS_ROOT_DIR` points to `/mnt/workspace/.claude/skills` in child tasks.
- `DATAAGENT_WORKSPACE_DIR`, `DATAAGENT_WORKSPACE_PREPARED`, and
  `DATAAGENT_SANDBOX_ROOT` are not injected into child task environments.
- `DATAAGENT_SANDBOX_ROOT` remains a backend/runner service-level setting for
  resolving host-side topic workspace roots.

## Tradeoffs

- Do not put project skills under Claude HOME. That would blur user-level Claude
  state and project skills again, and may change project skills into home-scoped
  behavior.
- Do not use `/tmp` as HOME. It would work technically, but it mixes Claude
  state with generic scratch files.
- Do not create a persisted host HOME for child tasks. Claude HOME state is not
  part of task output and should not persist across tasks or topics.

## Verification

Validation must cover:

- generated child container command uses `/mnt/workspace` as bind target and
  workdir;
- child env contains `HOME=/mnt/home` and
  `SKILLS_ROOT_DIR=/mnt/workspace/.claude/skills`;
- child env does not contain `DATAAGENT_WORKSPACE_DIR`,
  `DATAAGENT_WORKSPACE_PREPARED`, or `DATAAGENT_SANDBOX_ROOT`;
- local SDK execution preserves a distinct `HOME`;
- sandbox task execution passes `Path.cwd()` as the prepared workspace.
