---
name: opendataworks-data-dev
description: "当请求需要在 OpenDataWorks 上进行数据开发时使用：生成/润色 SQL、创建数据任务、组装工作流、发布与上线、配置调度。依赖 portal MCP 的写工具与对话内权限确认。不用于纯问数、业务语义或本体建模。"
compatibility: "需要可见的 portal MCP 写工具(portal_create_task/portal_create_workflow/portal_preview_publish/portal_publish_workflow 等)。高危操作(发布/上线)在 default/acceptEdits 权限模式下会触发对话内确认。"
tools: [Bash, Read]
---

# OpenDataWorks 数据开发助手技能

数据开发 Skill。OpenDataWorks Data Development Skill。

把"为某张表写一段 SQL → 建任务 → 加入工作流 → 发布 → 上线 → 配调度"的开发链路，通过 portal MCP 写工具安全地编排出来。SQL 就绪规则与平台事实由 `opendataworks-platform-tools` 提供；业务含义由语义技能提供；本技能只负责**开发动作的编排与安全发布流程**。

## 范围

负责：

- 生成与润色 SQL（先核实表结构与输入/输出表，再产出）。
- 创建/更新数据任务（draft 优先），维护输入/输出表血缘。
- 组装工作流（绑定任务、设置依赖边）。
- 发布(deploy)、上线/下线(online/offline)、调度配置与上线/下线。
- 发布前强制预览，把差异/告警交给用户确认。

不负责：

- 纯问数与结果可视化（交给 `opendataworks-platform-tools`）。
- 业务术语、指标口径、歧义消解（交给语义技能）。
- 本体建模（交给 `ontology-modeling-assistant`）。
- 直接执行含写操作的 SQL（写 SQL 只进任务定义，绝不通过只读查询工具试跑）。

## 工具

本技能以 portal MCP 写工具为主，配方见 `reference/30-tool-recipes.md`。关键工具：

- 探查：`portal_search_tables`、`portal_get_table_ddl`、`portal_analyze_sql`（平台工具技能提供前两者）。
- 任务：`portal_create_task`、`portal_update_task`、`portal_get_task`、`portal_list_tasks`。
- 工作流：`portal_create_workflow`、`portal_update_workflow`、`portal_get_workflow`、`portal_list_workflows`。
- 发布：`portal_preview_publish` →（确认）→ `portal_publish_workflow`。
- 调度：`portal_upsert_schedule`、`portal_workflow_schedule_online`、`portal_workflow_schedule_offline`。

字段契约见 `reference/10-task-fields.md`，生命周期与状态机见 `reference/20-workflow-lifecycle.md`，命名/校验规则见 `assets/dev-policies.json`，任务模板见 `assets/task-template.json`。

## Playbook（核心规则）

1. **SQL 生成 / 润色**
   - 生成前用 `portal_search_tables` / `portal_get_table_ddl` 核实库、表、字段，不臆造表名或字段。
   - 润色或落任务前，先 `portal_analyze_sql` 识别输入/输出表与操作类型；据此推荐 `inputTableIds` / `outputTableIds`。
   - 含写操作的 SQL（INSERT/UPDATE/INSERT OVERWRITE 等）只允许写进任务定义，**不要**用只读查询工具试跑。

2. **创建任务**
   - 一律以 draft 创建（`status: draft`）。
   - 必填字段参照 `reference/10-task-fields.md` 与 `assets/task-template.json`；命名遵循 `assets/dev-policies.json`。
   - 传入 analyze 得到的输入/输出表 ID，保持血缘完整。

3. **组装工作流**
   - 绑定任务、设置依赖边后，向用户复述 DAG 结构（节点 + 依赖），口头确认无误再进入发布。
   - 工作流必须处于 draft 才能改结构。

4. **发布与上线（高危，强制顺序）**
   - 先 `portal_preview_publish`，把 `diffSummary` / `errors` / `warnings` 摘要展示给用户；存在 error 级 issue 时**禁止**继续，转为修复建议。
   - 调用 `portal_publish_workflow(operation="deploy", preview_token=<上一步返回>)`。`deploy`/`online` 必须携带 preview 返回的 `preview_token`。
   - deploy 成功后再 `portal_publish_workflow(operation="online", preview_token=...)` 上线。
   - 在 default / acceptEdits 权限模式下，发布/上线会触发对话内权限确认卡片；请把操作目标与差异摘要放进工具参数的 `title` / `summary`，便于用户判断。

5. **调度**
   - `portal_upsert_schedule` 配置 cron/时区等；`portal_workflow_schedule_online`（需 preview_token，高危）启用；`portal_workflow_schedule_offline` 停用。

6. **失败恢复**
   - 发布失败时读取后端返回的报错与 `workflow_publish_record` 信息，判断是结构问题（回 draft 修改后重走预览）还是引擎问题（提示用户检查 DolphinScheduler 配置）；不要盲目重试同一请求。

## 安全边界

- 永远不要绕过 `portal_preview_publish` 直接发布/上线。
- 不要把权限模式或确认流程当作可跳过的步骤——高危确认由运行时强制，模型无法绕过。
- 写工具的可用性取决于会话权限模式：`plan` 模式下写工具不可用，只能产出方案。
