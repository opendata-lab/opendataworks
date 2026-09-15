---
name: opendataworks-data-dev
description: "当请求需要在 OpenDataWorks 上进行数据开发时使用：生成/润色 SQL、按引擎规范建表、创建数据任务、组装工作流、发布与上线、配置调度，以及批量扫描并逐个完善已有表的元数据（表/字段注释、分层归属、数据新鲜度）。依赖 portal MCP 的写工具与对话内权限确认。不用于纯问数、业务语义或本体建模。"
compatibility: "需要可见的 portal MCP 写工具(portal_create_table/portal_create_task/portal_create_workflow/portal_preview_publish/portal_publish_workflow 等)。高危操作(建表/发布/上线)在 default/acceptEdits 权限模式下会触发对话内确认。"
tools: [Bash, Read]
---

# OpenDataWorks 数据开发助手技能

数据开发 Skill。OpenDataWorks Data Development Skill。

把"为某张表写一段 SQL → 建目标表 → 建任务 → 加入工作流 → 发布 → 上线 → 配调度"的开发链路，通过 portal MCP 写工具安全地编排出来。SQL 就绪规则与平台事实由 `opendataworks-platform-tools` 提供；业务含义由语义技能提供；本技能只负责**开发动作的编排与安全发布流程**。

## 范围

负责：

- 生成与润色 SQL（先核实表结构与输入/输出表，再产出）。
- 按目标引擎规范生成建表 DDL，并通过建表工具创建目标表(见 `reference/40-ddl-standards.md`)。
- 创建/更新数据任务（draft 优先），维护输入/输出表血缘。
- 组装工作流（绑定任务、设置依赖边）。
- 发布(deploy)、上线/下线(online/offline)、调度配置与上线/下线。
- 发布前强制预览，把差异/告警交给用户确认。
- 完善已有表的元数据：表描述、字段注释、受控属性(分层/业务域/数据域)、数据新鲜度契约（批量扫描找缺口 + 逐个完善，见 `reference/30-tool-recipes.md` 第 7 节）。

不负责：

- 纯问数（交给 `opendataworks-platform-tools`）与结果可视化（交给 `chart-visualization`）。
- 业务术语、指标口径、歧义消解（交给语义技能）。
- 本体建模（交给 `ontology-modeling-assistant`）。
- 直接执行含写操作的 SQL（写 SQL 只进任务定义，绝不通过只读查询工具试跑）。

## 工具

本技能以 portal MCP 写工具为主，配方见 `reference/30-tool-recipes.md`。关键工具：

- 探查：`portal_search_tables`、`portal_get_table_ddl`、`portal_analyze_sql`（平台工具技能提供前两者）。
- 建表：`portal_preview_create_table`(只读预览表名+DDL)、`portal_create_table`(执行建表，高危)。
- 任务：`portal_create_task`、`portal_update_task`、`portal_get_task`、`portal_list_tasks`。
- 工作流：`portal_create_workflow`、`portal_update_workflow`、`portal_get_workflow`、`portal_list_workflows`。
- 发布：`portal_preview_publish` →（确认）→ `portal_publish_workflow`。
- 调度：`portal_upsert_schedule`、`portal_workflow_schedule_online`、`portal_workflow_schedule_offline`。
- 完善元数据：`portal_update_table_metadata`（给已有表补齐描述/字段注释/受控属性/数据新鲜度，草稿级写工具）。

字段契约见 `reference/10-task-fields.md`，生命周期与状态机见 `reference/20-workflow-lifecycle.md`，建表 DDL 规范见 `reference/40-ddl-standards.md`，常见加工场景模板见 `reference/50-sql-scenarios.md`，命名/校验规则见 `assets/dev-policies.json`，引擎建表默认值见 `assets/engine-ddl-rules.json`，任务模板见 `assets/task-template.json`。

## Playbook（核心规则）

1. **SQL 生成 / 润色**
   - 生成前用 `portal_search_tables` / `portal_get_table_ddl` 核实库、表、字段，不臆造表名或字段。
   - 润色或落任务前，先 `portal_analyze_sql` 识别输入/输出表与操作类型；据此推荐 `input_table_ids` / `output_table_ids`。
   - 血缘字段是**全量列表**：创建时两侧都必须显式提供；更新时省略某侧表示保留原值，传数组表示整体替换该侧。只改一侧就省略另一侧，绝不要回传不完整的列表，漏掉的表 ID 会被删除。详见 `reference/10-task-fields.md`。
   - 常见加工场景(每日增量/全量快照/聚合/关联拉宽/upsert)可参照 `reference/50-sql-scenarios.md` 的模板起手，再按真实表结构改写。
   - 含写操作的 SQL（INSERT/UPDATE/INSERT OVERWRITE 等）只允许写进任务定义，**不要**用只读查询工具试跑。

2. **建表（当需求需要新目标表时）**
   - **新目标表一律走建表工具直接建表，不要为"建表"单独创建执行 DDL 的数据任务**;数据任务只承载 DML 加工逻辑。
   - 建表 DDL **必须匹配目标引擎规范**，严格遵循 `reference/40-ddl-standards.md`；默认值(分桶数、副本数、表模型等)取自 `assets/engine-ddl-rules.json`，与后端一致。
   - Doris:先判定表模型——明细层用 `DUPLICATE`(默认)，需去重/ upsert 用 `UNIQUE KEY(...)`，预聚合用 `AGGREGATE`；给出 KEY 列、分区列、分桶键与桶数、副本数。
   - 优先 `portal_preview_create_table` 传结构化字段，让后端产出规范化 DDL 与最终表名；把 DDL/表名/是否已存在展示给用户核对。
   - 确认后 `portal_create_table` 执行建表(高危，会触发确认卡；批准即执行)。建成的表再进入建任务环节维护血缘。
   - 后端建表当前仅执行 Doris；MySQL 等目标表按规范产出 DDL 供落任务或人工执行。

3. **创建任务（DML 加工）**
   - **落任务前 DML SQL 必须验证通过**:`portal_analyze_sql` 解析成功、输入/输出表可解析、无 error 级风险;必要时用 `portal_query_readonly` 只读抽样核对 SELECT 逻辑。**验证不通过不建任务、不加入计划**;含写操作的整段 SQL 不试跑,只落任务定义。
   - 一律以 draft 创建（`status: draft`）。
   - 必填字段参照 `reference/10-task-fields.md` 与 `assets/task-template.json`；命名遵循 `assets/dev-policies.json`。
   - 传入 analyze 得到的输入/输出表 ID，保持血缘完整。

4. **组装工作流**
   - 绑定任务、设置依赖边后，向用户复述 DAG 结构（节点 + 依赖），口头确认无误再进入发布。
   - 工作流必须处于 draft 才能改结构。

5. **发布与上线（高危，强制顺序）**
   - 先 `portal_preview_publish`，把 `diffSummary` / `errors` / `warnings` 摘要展示给用户；存在 error 级 issue 时**禁止**继续，转为修复建议。
   - 调用 `portal_publish_workflow(operation="deploy", preview_token=<上一步返回>)`。`deploy`/`online` 必须携带 preview 返回的 `preview_token`。
   - deploy 成功后再 `portal_publish_workflow(operation="online", preview_token=...)` 上线。
   - 在 default / acceptEdits 权限模式下，发布/上线会触发对话内权限确认卡片；请把操作目标与差异摘要放进工具参数的 `title` / `summary`，便于用户判断。

6. **调度**
   - `portal_upsert_schedule` 配置 cron/时区等；`portal_workflow_schedule_online`（需 preview_token，高危）启用；`portal_workflow_schedule_offline` 停用。

7. **完善已有表的元数据（批量扫描 + 逐个完善）**
   - 用 `portal_search_tables` / `portal_export_metadata` 扫出注释缺失/薄弱的表，排出待完善清单（复用读工具，无专门扫描接口）。
   - 逐张表：`portal_get_table_ddl` 取字段与现有注释 → 基于 DDL/字段/血缘提案 → `portal_update_table_metadata` 写回（草稿级，`default` 触发确认）。一张一确认，不要一次批量自动写。
   - 受控属性只填平台清单内编码、`freshness.loaded_at_field` 必须是真实时间列；服务端会丢弃清单外的编码/列并写进 `skipped`，据此纠正后只重试该表。枚举取值本工具不写。详见 `reference/30-tool-recipes.md` 第 7 节。

8. **失败恢复**
   - 发布失败时读取后端返回的报错与 `workflow_publish_record` 信息，判断是结构问题（回 draft 修改后重走预览）还是引擎问题（提示用户检查 DolphinScheduler 配置）；不要盲目重试同一请求。
   - 建表失败时读取后端报错(如表已存在、字段/类型非法、集群不可达)，回到 DDL 规范修正后重新预览；不要盲目重试同一请求。

## 安全边界

- 永远不要绕过 `portal_preview_publish` 直接发布/上线。
- 建表前优先 `portal_preview_create_table` 预览并让用户核对表名与 DDL，再 `portal_create_table` 执行；建表是不可逆高危操作。
- 不要把权限模式或确认流程当作可跳过的步骤——高危确认由运行时强制，模型无法绕过。
- 写工具的可用性取决于会话权限模式：`plan` 模式下写工具(含 `portal_create_table`)不可用，只能产出方案。
