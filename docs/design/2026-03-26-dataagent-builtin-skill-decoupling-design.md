# DataAgent Builtin Skill Decoupling Design

**Date:** 2026-03-26  
**Goal:** 将仓库内置 `dataagent-nl2sql` 收敛为 OpenDataWorks 平台通用问数 skill，把租户/业务域知识从内置 skill 中拆出，并为后续多 skill 扩展保留清晰边界。  
**Tech Stack:** 前端 `Vue 3` + `Vite 5` + `Element Plus`；DataAgent 后端 `FastAPI` + `Pydantic`；内置 skill bundle `SKILL.md + reference/ + assets/ + scripts/`。

## Scope

- 覆盖 `dataagent/.claude/skills/dataagent-nl2sql` 的内置知识边界与内容清理
- 覆盖 `dataagent/dataagent-backend` 中与内置 skill 目录语义、提示词、默认模板相关的实现
- 覆盖前端配置页与 Skill 管理页的文案和 skill 展示逻辑
- 覆盖回归测试、设计文档和实施计划
- 在仓库根目录创建一个本地未提交的 `architecture-assistant` skill，用来承接拆出的业务知识

不在范围内：

- 不实现扩展 skill 上传压缩包
- 不实现扩展 skill 启用/停用管理
- 不让根目录 `.claude/skills/architecture-assistant` 进入当前 DataAgent 运行时发现链路
- 不新增 registry 表、上传接口或 settings schema

## Current Problems

- 当前内置 `dataagent-nl2sql` 同时承载：
  - OpenDataWorks 平台术语和平台表
  - 数据中台通用问数规则
  - 明显带有业务域色彩的环境、对象、示例和默认口径
- 这些业务知识已经写入 `assets/*`、`reference/*` 和 `SKILL.md`，例如：
  - `PROD / SIM`
  - `env_name`
  - 数据中心 / CFC 环境命名
  - 组件 / 接口类对象
  - 指向具体业务表名的 few-shot 和 SQL 模板
- 后端和前端虽然还只运行单内置 skill，但代码里已经把 `skills_output_dir`、技能目录、运行时发现目录混成一个概念，容易阻碍后续扩展

结果是：

- 内置 skill 的职责边界不清，难以长期维护
- 业务知识会误导成“平台默认能力”
- 后续要支持多个 skill 时，运行时发现目录、内置目录、本地扩展目录容易继续耦合

## Target State

- 仓库内置 `dataagent-nl2sql` 只保留三类知识：
  - OpenDataWorks 平台术语、平台表、元数据/血缘/数据源路径
  - 数据中台通用问数规则，例如 `df/di`、`ds`、schema 前缀、单源路由
  - 通用只读执行约束、图表契约和脚本调用规范
- 以下内容不再出现在内置 skill 中：
  - 业务环境默认值
  - 业务对象与业务对象别名
  - 业务 few-shot
  - 业务 SQL 模板
  - 依赖隐藏业务口径的默认回答
- 被移除的业务知识转移到仓库根目录本地 skill：
  - `.claude/skills/architecture-assistant`
  - 该 skill 不提交到 Git
  - 当前运行时不自动发现它
- 后端内部明确区分：
  - 内置 skill 根目录
  - Claude SDK 发现用的 `.claude/skills` 目录
- 前端文案明确当前页面只管理“内置 skill”

## Design

### 1. Builtin skill content boundary

- 保留：
  - `workflow_publish_record`、`data_table`、`data_lineage`、`doris_cluster` 等平台核心表知识
  - `df` 快照表、`di` 增量表、`ds` 时间分区规则
  - `<schema>.<table>` 约束
  - 平台核心表可直接进入 `database=opendataworks`、`engine=mysql` 的只读查询路径的规则
- 移除：
  - `env_name`
  - `PROD / SIM`
  - 数据中心 / CFC 环境命名约定
  - 组件 / 接口相关术语与 SQL 示例
  - 指向具体业务表名的 few-shot
- 内置 skill 遇到业务问题时采用“弱回答”：
  - 可以基于真实 metadata、显式表名和通用规则继续回答
  - 不能再依赖仓库里提交的业务默认值或业务术语别名

### 2. Reference generation remains asset-driven

- `reference/20-term-index.md`、`21-metric-index.md`、`22-sql-example-index.md` 继续由 `build_reference_digest.py` 汇总生成
- 汇总脚本本身改成“平台术语 / 通用规则”口径，避免重新引入“业务术语”语义
- 回归测试直接检查内置 skill 快照，防止业务知识再次写回 `assets` 或生成后的 `reference`

### 3. Backend path semantics are separated

- 继续保留外部配置字段 `skills_output_dir`
- 但在内部实现中拆成两个概念：
  - builtin skill root：当前内置 bundle 目录
  - skill discovery root：Claude SDK 发现 `.claude/skills` 的目录
- `resolve_agent_project_cwd()` 基于 discovery root 计算工作目录
- `resolve_skills_root_dir()` 保持兼容，继续返回 builtin skill root，避免影响现有 API 和管理页

### 4. Frontend language reflects builtin-only management

- 配置页将 `skills_output_dir` 明确解释为“内置 skill 目录”
- Skill Studio 明确说明自己只管理当前内置 skill
- 聊天工具展示不再依赖 `dataagent-nl2sql` 字面值识别 skill block，而是根据输入/输出中的 skill 标识做泛化识别

### 5. Local architecture-assistant skill

- 通过 `skill-creator` 在仓库根目录初始化：
  - 目录名：`architecture-assistant`
  - 展示名：`架构助手 Skill`
- 仅保留知识 overlay：
  - 业务术语与对象
  - 业务环境与命名约定
  - 业务场景示例与问法
  - 业务 SQL 模板与口径提示
- 不复制 DataAgent 执行脚本，不修改当前运行时契约
- 在当前 worktree 的本地 Git exclude 中排除根目录 `.claude/`

## Interfaces Affected

- `GET /api/v1/nl2sql-admin/settings`
  - 字段不变，但 `skills_root_dir` 的语义明确指向内置 skill 根目录
- Skill document list APIs
  - 通过文件列表读取自动刷新管理索引
- DataAgent 运行时：
  - 仍只消费 `dataagent/.claude/skills/dataagent-nl2sql`
  - 不发现根目录 `.claude/skills/architecture-assistant`

## Risks

- 移除业务 few-shot 后，某些历史问题会从“自动代入业务默认值”变成“基于 metadata 的弱回答或追问”
- 若 reference 手工文件和 assets 清理不同步，生成索引可能重新带回旧语义
- 若前端文案不改，用户仍可能误以为当前页面管理未来所有扩展 skill

## Verification

- 后端单测：
  - builtin skill 静态快照不再包含被移除的业务词汇
  - skill discovery / prompt helper 仍可工作
- 前端单测：
  - 通用 skill 标识识别不依赖 `dataagent-nl2sql`
- 本地 smoke 环境可用时：
  - 平台通用题可跑通
  - `df/di` 规则仍生效
  - 业务模糊题不再使用内置业务默认值
- 本地 `architecture-assistant` skill：
  - 运行 `quick_validate.py`
  - 不纳入仓库提交验证
