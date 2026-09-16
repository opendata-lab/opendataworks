# DataAgent Skill Invocation Anchor Unification Design

**Date:** 2026-09-15
**Goal:** 把 skill 脚本调用契约统一到单一锚点 `${SKILLS_ROOT_DIR}/<skill-folder>/scripts/<name>.py`，移除与之恒等的 `DATAAGENT_PLATFORM_SKILL_ROOT`。
**Tech Stack:** DataAgent 后端 FastAPI（`core/agent_runtime.py`、`sandbox_runner_main.py`）；内置 skills `opendataworks-platform-tools` 与 `opendataworks-methodology-dag` 的 SKILL.md、reference 与脚本；`dataagent-runtime-pi`（TypeScript）；pytest。

## Scope

- 覆盖 skill 脚本的可执行调用契约，以及运行时注入该契约所依赖的环境变量。
- 覆盖 `opendataworks-platform-tools` 与 `opendataworks-methodology-dag` 中依赖该变量的脚本内部解析逻辑。
- 覆盖 system prompt、skill 文档、`dataagent/README.md`、`deploy/README.md` 中的命令模板。

不覆盖：脚本自身的参数契约、portal MCP 协议、skill 启停与发现逻辑、沙箱目录拓扑本身。

## Current State

运行时目前向 skill 子进程注入两个可用于定位脚本的锚点：

- `SKILLS_ROOT_DIR`：skill 发现根目录（`.claude/skills`）。
- `DATAAGENT_PLATFORM_SKILL_ROOT`：平台工具 skill 的根目录。

两者并存导致文档中存在两种等价的调用形式：

```
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/<skill-folder>/scripts/<name>.py" ...
"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/<name>.py" ...
```

`chart-visualization` 与 `report-generation` 已经迁移到前者，并由
`tests/test_builtin_skill_content.py::test_generic_presentation_skills_use_shared_skills_dir_anchor`
锁定（docstring：「跨技能引用必须走通用锚点，不能再退回 per-skill 专属环境变量」）。
`opendataworks-platform-tools` 仍使用后者。

### 等价性验证

`DATAAGENT_PLATFORM_SKILL_ROOT` 在所有构造分支下恒等于 `${SKILLS_ROOT_DIR}/opendataworks-platform-tools`：

| 构造点 | 平台根 | `SKILLS_ROOT_DIR` |
| --- | --- | --- |
| `core/agent_runtime.py:556` | `discovery_root / folder` | `_skills_dir_from(primary_root)` → 同一 `discovery_root` |
| `core/skill_admin_service.py:1641` | `discovery_root / folder` | 同上 |
| `sandbox_runner_main.py:415` | `f"{CHILD_SKILLS_ROOT}/{PLATFORM_TOOLS_SKILL_FOLDER}"` | `CHILD_SKILLS_ROOT` |

没有任何分支产出不同值。`deploy/docker-compose.dev.yml` 与 `deploy/docker-compose.prod.yml` 只设置
`SKILLS_ROOT_DIR`，从不设置 `DATAAGENT_PLATFORM_SKILL_ROOT`，因此它不是部署可配置项，
移除不影响任何现有部署。

## Problem

- 一个运行时契约存在两种等价写法，直接违反 AGENTS.md「Prefer one stable invocation contract」。
- 命令模板分散在 13 处文档，改一个脚本入口要同时改两套模板，容易只改一半。
- `core/agent_runtime.py:659` 的回落分支 `skills_root.parent / PLATFORM_TOOLS_SKILL_FOLDER` 存在
  off-by-one：`resolve_builtin_skill_root_dir()` 返回的是发现根 `.claude/skills` 本身而非某个 skill 目录，
  此时该表达式算出 `.claude/opendataworks-platform-tools`。`is_dir()` 守卫使它不会传播错误路径，
  但变量会静默缺失，失败模式不可见。
- `opendataworks-platform-tools/scripts/_opendataworks_runtime.py::skill_root_dir()` 为此维护了
  三层回落（env → `DATAAGENT_ENABLED_SKILL_ROOTS` JSON → `__file__` 父目录），
  违反 AGENTS.md「Keep fallback minimal, explicit, and single-layer」。

## Design

### 1. 单一调用契约

所有 skill 脚本统一为：

```
"$DATAAGENT_PYTHON_BIN" "${SKILLS_ROOT_DIR}/<skill-folder>/scripts/<name>.py" ...
```

平台工具即 `<skill-folder>` = `opendataworks-platform-tools`，不再有专属形式。

### 2. 运行时注入收敛

`core/agent_runtime.py` 不再计算与注入 `DATAAGENT_PLATFORM_SKILL_ROOT`，连同
`platform_skill_root` 的 sibling 回落分支一并删除。`sandbox_runner_main.py` 同步停止向子容器注入。
`dataagent-runtime-pi/src/tools/tool-registry.ts` 的透传白名单移除该项。

`SKILLS_ROOT_DIR` 与 `DATAAGENT_ENABLED_SKILL_ROOTS` 保持不变，仍是运行时锚点。

### 3. 脚本内部解析收敛

`_opendataworks_runtime.py::skill_root_dir()` 由三层回落收敛为单层：以 `__file__` 推导自身 skill 根。
脚本位于自己的 skill 目录内，这是唯一不依赖宿主注入、且在沙箱与本地都成立的解析方式。

`opendataworks-methodology-dag/scripts/engine.py::_platform_skill_candidates()` 移除 env 覆盖候选，
保留同级目录解析（该函数自身的 docstring 已说明同级目录「needs nothing from the host」）。

### 4. 测试契约

将既有的 `test_generic_presentation_skills_use_shared_skills_dir_anchor` 扩展到平台工具与方法论 skill，
使「统一锚点」成为全部 skill 的硬约束；新增断言确保 `DATAAGENT_PLATFORM_SKILL_ROOT`
不再出现在运行时注入、skill 文档与脚本中。

## Interfaces

移除的运行时环境变量：`DATAAGENT_PLATFORM_SKILL_ROOT`。

保留不变：`DATAAGENT_PYTHON_BIN`、`SKILLS_ROOT_DIR`、`DATAAGENT_ENABLED_SKILL_ROOTS`、`DATAAGENT_SKILL_ROOT`
（后者仍指向 primary skill，且仍不得用于定位兄弟 skill）。

## Tradeoffs

- 选择彻底移除而非保留为废弃别名：该变量非部署可配置项，无外部消费者，保留别名只会让
  「两种等价写法」的问题继续存在，与本次目标相悖。
- 代价是所有引用点必须在同一次变更内改完，否则模型会读到指向未注入变量的命令模板。
  通过测试断言覆盖运行时、skill 文档与脚本三层来防止改漏。
- 另一种方案是反向统一到 per-skill 专属变量，但这会要求每个 skill 都注入一个专属根，
  与已迁移的 chart / report 以及既有测试约束冲突，且 skill 数量增长时注入面线性膨胀。
