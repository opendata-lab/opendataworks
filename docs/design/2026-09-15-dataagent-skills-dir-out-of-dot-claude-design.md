# DataAgent Skills Directory Out of .claude Design

**Date:** 2026-09-15
**Goal:** 把仓库内置 skill 源码目录从 `dataagent/.claude/skills/` 迁到 `dataagent/skills/`，消除为「在被全局忽略的 `.claude/` 里维护受版本控制的源码」而存在的整套 `.gitignore` 反向放行规则。
**Tech Stack:** DataAgent 后端 FastAPI（`core/skill_discovery.py`、`core/agent_runtime.py`、`core/skill_admin_service.py`）；Alembic；`deploy/` Compose 与离线打包脚本；pytest。

## Scope

- 覆盖仓库内 skill **源码**目录位置，及其在后端发现逻辑、部署挂载、打包脚本中的引用。
- 覆盖 `da_agent_settings.skills_output_dir` 存量值的迁移。

不覆盖：容器内挂载点 `/app/.claude/skills`、topic workspace 运行时目录、skill 内容本身、脚本调用契约（见
[2026-09-15-dataagent-skill-invocation-anchor-unification-design.md](2026-09-15-dataagent-skill-invocation-anchor-unification-design.md)，两者正交）。

## Current State

仓库中存在三个同名路径，性质不同：

| 路径 | 角色 | 是否受版本控制 |
| --- | --- | --- |
| `dataagent/.claude/skills/` | skill 源码目录 | 是 |
| 容器内 `/app/.claude/skills` | bind mount 挂载点 | 否 |
| `<topic_workspace>/.claude/skills/` | 每次会话的运行时 skill 目录 | 否 |

第三个由 `core/topic_workspace.py:126` 写入，agent 的 cwd 是 topic workspace
（`core/task_executor.py:1077`、`:1188`），Claude Agent SDK 按 `<cwd>/.claude/skills` 约定发现 skill，
与同目录的 `.claude/plans`（`core/task_executor.py:69`）同属一套约定。**它不在本次范围内，也不能改名。**

宿主侧路径本就是可配置的：`DATAAGENT_SKILLS_DIR` 经
`${DATAAGENT_SKILLS_DIR}:/app/.claude/skills` 挂进容器，容器不感知宿主目录名。
离线包已经把它改写为不含 `.claude` 的 `./dataagent-runtime/skills`
（`scripts/create-offline-package.sh:225`）并正常运行，可行性已被现网验证。

## Problem

`.gitignore:46-65` 为了让被全局忽略的 `.claude/` 下存在受版本控制的源码，维护了四层规则：

```
.claude/                                  # 全局忽略
!/dataagent/.claude/                      # 放行一层
!/dataagent/.claude/skills/               # 再放行一层
/dataagent/.claude/skills/*/              # 再排除所有子目录
!/dataagent/.claude/skills/<folder>/      # 逐个放行，每个 skill 两行
!/dataagent/.claude/skills/<folder>/**
```

- 每新增一个内置 skill 必须补两行，漏补则文件被静默丢弃。该故障已发生过：
  提交 `e213f6cc` 的信息即为「commit the new skills' SKILL.md, which .gitignore had silently dropped」。
- 规则中仍放行已删除的 `dataagent-nl2sql`（`:50-51`），且被
  `tests/test_deepeval_packaging_hooks.py:113` 断言锁定。
- 源码目录借住在工具配置目录下，语义上也不成立：`.claude/` 的本意是本地工具配置与生成物。

## Design

### 1. 目录迁移

`git mv dataagent/.claude/skills dataagent/skills`，保留文件历史。
`dataagent/.claude/` 迁空后删除。

### 2. .gitignore 收敛

删除 `:47-65` 全部反向放行规则，只保留全局 `.claude/`（它对挂载点、topic workspace
与本地工具配置仍然正确）。`dataagent/skills/` 成为普通受控目录，新增 skill 无需任何 ignore 改动。

私有业务 skill 资产的排除规则（`test_private_business_skill_assets_are_ignored_and_not_packaged`
所保护的语义）改为在新路径下按资产类型表达，不再按 skill 名逐个放行。

### 3. 发现根校验放宽

`core/skill_discovery.py:40-41` 当前强制 root 必须是 `.claude/skills`。
改为只校验目录名为 `skills` 且目录存在，不再约束父目录名。
该校验的目的是拦截明显配错的路径，父目录名并不承载语义——容器内是 `/app/.claude/skills`，
离线包是 `dataagent-runtime/skills`，本就不一致。

### 4. 消除形状嗅探

`core/agent_runtime.py:623-634` 的 `_skills_dir_from()` 依赖 `.claude/skills` 这个**路径形状**
来区分传入的是「主 skill 目录」还是「发现根本身」。迁移后 `dataagent/skills` 会被判为主 skill 目录，
取 `.parent` 得到 `dataagent/`，**产生错误的 `SKILLS_ROOT_DIR`**。

不修补判据，而是删除推导：`skill_runtime` 字典新增 `discovery_root` 字段，
由已持有该值的两个构造点直接写入（`core/agent_runtime.py:555`、`core/skill_admin_service.py:1639`），
env 构建直接读取。这样 `SKILLS_ROOT_DIR` 来自唯一事实来源，不再从路径反推。

### 5. skills_output_dir 兼容与迁移

`core/skill_admin_service.py:621-622` 的 `must be under .claude/skills` 校验改为接受
`.claude/skills/<folder>` 与 `skills/<folder>` 两种形式；`:44` 的默认值
`../.claude/skills/<folder>` 改为 `../skills/<folder>`。

新增 Alembic 迁移，把 `da_agent_settings.skills_output_dir` 中的 `.claude/skills/` 前缀重写为 `skills/`。
该字段的唯一实际用途是经 `_folder_from_skills_output_dir()` 推导 primary skill folder，
迁移后行为不变。

### 6. 部署与打包

- `deploy/.env.example:156` 默认值改为 `../dataagent/skills`
- `deploy/docker-compose.dev.yml:211,268` 与 `deploy/docker-compose.prod.yml:235,294`
  的 bind mount 源端默认值同步；**目标端 `/app/.claude/skills` 保持不变**
- `scripts/start.sh:123` 的内联默认值同步
- `scripts/create-offline-package.sh` 的源目录路径同步（其改写后的目标值 `./dataagent-runtime/skills` 不变）

容器内的 `SKILLS_ROOT_DIR=/app/.claude/skills` 全部保持不变，因此**容器契约零变更**，
只有宿主侧路径变化。

## Interfaces

- 变更：仓库内 skill 源码路径，`DATAAGENT_SKILLS_DIR` 默认值，`skills_output_dir` 取值形式
- 不变：`SKILLS_ROOT_DIR` 语义与容器内取值、`DATAAGENT_ENABLED_SKILL_ROOTS`、topic workspace 布局、SDK 发现约定

## Tradeoffs

- 本地开发者若已在 `.env` 里显式写死 `DATAAGENT_SKILLS_DIR=../dataagent/.claude/skills`，
  升级后仍指向旧路径且目录已不存在。通过 README 升级说明与启动期清晰报错覆盖，不做静默双路径回落——
  双路径回落正是 AGENTS.md 要求避免的级联 fallback。
- 另一种方案是保留目录不动、只把 `.gitignore` 换成更紧凑的写法，但四层套娃的根因是源码借住在被全局忽略的
  目录下，换写法不消除「新增 skill 要改 ignore」这一故障模式。
- 迁移与脚本调用锚点统一是两个正交变更：后者的命令模板使用 `${SKILLS_ROOT_DIR}` 变量而非字面路径，
  本次迁移不触碰任何命令模板。先做本次迁移，再做锚点统一。
