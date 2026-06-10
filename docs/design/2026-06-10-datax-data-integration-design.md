# DataX 数据集成设计

**Date:** 2026-06-10
**Goal:** 把仓库中已"预留 + 半成品"的 DataX 同步能力收敛为一份完整、可落地的设计，并就"DataX 放 Dolphin 还是放数据集成 / 是否抽象调度引擎"做出明确取舍。
**Tech Stack:** Java 8 + Spring Boot 2.7 backend, MySQL + Flyway, Vue 3 + Element Plus frontend, DolphinScheduler + DataX 执行层

## 背景与决策

DataX 同步能力当前分散在数据库迁移、实体、服务与前端里，且本地 DataX 已能跑起来。核心架构问题：

- 当前调度引擎主要用 **DolphinScheduler（dolphin）**。
- DataX 同步任务是 (A) 继续作为 **dolphin 工作流里的 DATAX 任务节点**、复用 dolphin 定时调度，还是 (B) 独立 **"数据集成"模块**自带定时调度，甚至抽象出"调度引擎"层支持 dolphin 之外的引擎。

**已确认决策：**

1. **DataX 放置 = 方案 A**：留在 dolphin 工作流，复用 dolphin 的 cron 调度 / 重试 / 补数 / 监控。
2. **引擎抽象 = 暂不抽象**：保持 dolphin 单引擎，不引入 `SchedulerEngine` 策略接口（符合 AGENTS.md「避免投机性抽象、优先低风险改动」）。
3. **保留方案 B 的一点**："数据集成"页面做成**面向用户的组织层 UI**——DataX 维度的任务管理视图，执行与调度仍下发 dolphin。

## Current State

DataX 已端到端接入为 **dolphin DATAX 任务类型**，不是纯占位：

- **数据模型**：`backend/src/main/resources/db/migration/V7__add_datax_fields_to_data_task.sql` 给 `data_task` 增加 `target_datasource_name` / `source_table` / `target_table` / `column_mapping`；`entity/DataTask.java` 映射这些字段，并带 `dolphinNodeType`(SHELL/SQL/DATAX/PYTHON/SPARK/FLINK) 与 `engine`(dolphin/dinky)。
- **校验 / 发布**：`service/DataTaskService.java`
  - `validatePublishMetadata()` 对 DATAX 要求 `datasourceName` + `targetDatasourceName`；
  - `publish()` 把两者解析成 dolphin 数据源 ID，并把 `getSourceTable()`/`getTargetTable()`/`getColumnMapping()` 传入 `buildTaskDefinition(...)`（约 L490-510）；
  - **注意**：`validateTask()`（建 / 改时）没有 DATAX 字段校验，只有发布时与前端规则有。
- **任务定义构建**：`service/DolphinSchedulerService.java` `buildTaskDefinition()` 对 `"DATAX"` 调 `TaskParams.datax(datasourceId, targetDatasourceId, sourceTable, targetTable, customJson)`；`column_mapping` 作为 `customJson` 透传。
- **调度**：走**工作流级** dolphin cron（`WorkflowScheduleService`：crontab / 起止 / 时区 / 失败策略 / 上线下线 / 补数）。DataX 任务必须属于某个 `data_workflow` 才能被调度；`DataTask.scheduleCron` 字段存在但发布路径不使用。
- **前端**：`frontend/src/views/tasks/TaskEditDrawer.vue` 已有完整 DATAX 分支（类型选项、源 / 目标数据源、源 / 目标表、列映射、必填规则、切换重置）。`frontend/src/views/integration/DataIntegration.vue` 是**桩页面**，仅渲染 `DataSourceManagement`，已在路由 `router/index.js` 与菜单 `Layout.vue`「数据集成」注册。
- **无引擎抽象**：全部硬编码到 dolphin（`DolphinSchedulerService` → `service/dolphin/DolphinOpenApiClient.java`）。`docs/design/2026-04-28-dolphin-engine-switch-design.md` 仅处理 dolphin **多实例**切换，且明确不抽象通用引擎。

## Problem

1. **DATAX taskParams 形状与 dolphin 原生 schema 不一致。** `DolphinSchedulerService.TaskParams` 是 shell/sql/datax **共用的单一类**，`@Getter` 会把全部字段序列化。DATAX 变体实际发出 `targetDatasource` / `sourceTable` / `targetTable` / `customJson`（外加泄漏的 `sql`(null) / `sqlType` / `displayRows` 等）。DolphinScheduler 原生 DATAX 任务参数用的是 `customConfig`、`json`、`dsType`/`dataSource`、`dtType`/`dataTarget`、`sql`、`targetTable`、`jobSpeedByte`/`jobSpeedRecord`、`xms`/`xmx`、`preStatements`/`postStatements`。字段名对不上，节点可能不能在 dolphin 端正确执行。
2. **`column_mapping` 语义当前是"死的"。** 已存储、透传为 `customJson`，但 dolphin 端不认这个字段名，列映射不生效。
3. **建 / 改时缺 DATAX 校验**，仅发布时与前端有，非法 DATAX 任务可被静默落库。
4. **`environmentCode` 硬编码为 -1**（`buildTaskDefinition`）。dolphin DATAX 通常需要指向已装 DataX 运行时的 environment，属部署前提，未在平台体现。
5. **数据集成页是占位**，没有承担 DataX 维度的管理职责。

> 用户称 DataX「已经能跑起来」：以上以"**待核实 / 加固**"口径记录，需在所用 dolphin 版本上验证执行层，再决定改动力度。

## Scope

**In scope：**

- 对齐 / 加固 DATAX 执行载荷，使其符合所用 dolphin 版本的 DATAX 参数 schema。
- 实现 `column_mapping` 的语义翻译。
- 在建 / 改时补 DATAX 字段校验。
- 把"数据集成"从桩页升级为 DataX 维度的任务管理视图（执行仍走 dolphin）。

**Out of scope：**

- 通用调度引擎抽象（`SchedulerEngine` 策略接口）。
- 任务级独立 cron（继续用工作流级 dolphin 调度）。
- Dinky DataX 路径。
- DataX 资源 / 限速精调 UI（除非验证必须）。

## Solution

1. **修正 `TaskParams.datax`**，发出 dolphin 合法的 DATAX 参数，区分两种模式：
   - **向导模式**：用 `dsType`/`dataSource`（源）、`dtType`/`dataTarget`（目标）、`sql`（基于源表 + 列映射生成的 `SELECT`）、`targetTable`，`customConfig=0`。
   - **自定义模式**：当 `column_mapping` 是完整 DataX JSON 时，`customConfig=1` + `json` 直传，不再拼向导字段。
   - 不再让 shell/sql 的 `@Getter` 把无关字段泄漏进 DATAX 节点。
2. **翻译 `column_mapping`**（在 `DataTaskService.publish()` 传 `getColumnMapping()` 处，或新建的 DATAX 参数构建器内）：
   - 空 → 全列同步（`SELECT * FROM source_table` 或由 dolphin 默认推断）。
   - 列清单 JSON → 生成 `SELECT <mapped columns> FROM source_table`。
   - 完整 DataX JSON → 自定义模式（`customConfig=1` + `json`）。
3. **建 / 改时补 DATAX 校验**：在 `DataTaskService.validateTask()` 加入对源 / 目标数据源、源 / 目标表的校验，与发布时 `validatePublishMetadata()` 对齐。
4. **数据集成页实化**：`DataIntegration.vue` 改为 DataX 维度的列表 + 创建 / 编辑视图，复用现有任务 API（按 `dolphinNodeType=DATAX` 过滤）与 DATAX 锁定版的 `TaskEditDrawer`。

## Interfaces

- **后端**：任务列表查询新增可选 `dolphinNodeType` 过滤参数（`DataTaskService.list` 及其 controller）；复用现有 publish / schedule / execute，不新增端点。
- **前端**：实化 `DataIntegration.vue`；抽出 `TaskEditDrawer.vue` 的 DATAX 表单块为可复用组件，供数据集成页与任务编辑共用。

## Data Model

- **不改 schema**，复用 `data_task` 现有 DataX 列（`target_datasource_name` / `source_table` / `target_table` / `column_mapping`）。
- 明确 `column_mapping`（TEXT）的三种取值格式：空 / 列清单 JSON / 完整 DataX JSON，并在文档与前端提示里写明。

## Scheduling Model

- DataX 使用**工作流级** dolphin cron（`WorkflowScheduleService`：crontab / 起止 / 时区 / 失败策略 / 上线下线 / 补数），不引入新调度器。
- DataX 任务**必须归属某个 `data_workflow`** 才能被定时调度；单任务无独立 cron。

## Trade-offs / Decision

- **采用方案 A**：现有代码已约 80% 落地，剩余是"对齐 DATAX 载荷 + 列映射翻译"的定向低风险改动；dolphin 已提供 cron / 重试 / 补数 / 监控 / 多环境绑定，方案 B 的"自带调度"等于重复造已成熟的基础设施并增加运维风险；DataX 需要装有 DataX 运行时的执行 worker，dolphin worker 是天然宿主。
- **不做引擎抽象**：当前仅 dolphin 一个确认引擎（dinky 仅是枚举值，`publish()` 硬拒非 dolphin）；引入策略接口将是单实现的投机抽象，与 2026-04-28 文档"不抽象通用引擎"的结论一致。
- **保留数据集成 UI 层**：作为方案 B 里唯一有价值的部分，给用户清晰的"数据集成"心智，而不另建调度器。

## Gaps / Roadmap

| # | 缺口 | 文件 |
|---|------|------|
| 1 | DATAX taskParams 形状对齐 / 加固 | `service/DolphinSchedulerService.java`（`TaskParams` + `buildTaskDefinition` DATAX 分支） |
| 2 | `column_mapping` 三态翻译 | `service/DataTaskService.java`（`publish()` 传 `getColumnMapping()` 处）或新建参数构建器 |
| 3 | 建 / 改时 DATAX 校验 | `service/DataTaskService.java`（`validateTask()`） |
| 4 | `environmentCode` 与 DataX 运行时前提 | `service/DolphinSchedulerService.java`（`buildTaskDefinition` 硬编码 -1）+ `deploy/` 文档 |
| 5 | 数据集成页实化 + 任务列表 `dolphinNodeType` 过滤 | `frontend/src/views/integration/DataIntegration.vue`、`frontend/src/views/tasks/TaskEditDrawer.vue`、`DataTaskService.list` |

## 部署前提

- dolphin worker 需安装 DataX 运行时。
- DATAX 节点通常需配置指向 DataX 环境的 `environmentCode`（当前硬编码 -1），在 `deploy/` 与 handbook 注明。

## 验证要点

- 后端单测：扩展 `backend/src/test/java/com/onedata/portal/service/DataTaskServiceWorkflowMetadataTest.java`（已用 Mockito + `ArgumentCaptor` 捕获 `buildTaskDefinition` 参数），断言 DATAX `taskParams` 关键字段正确、`column_mapping` 三种格式、建 / 改 DATAX 校验。
- 前端：先 `nvm use`，再跑 `integration/` + `tasks/` 触达区域的最小构建 / lint。
- 本地 smoke（环境可用时）：建 DATAX 任务 → 发布 → 确认同步出的 dolphin DATAX 节点参数合法 → 跑一次实例 → 状态回流 `getLatestExecutionStatus`；若本地无 DataX 运行时，明确声明执行层未验证、仅验证载荷形状与单测。

## 关联文档

- 实现计划：`docs/plans/2026-06-10-datax-data-integration-plan.md`
- 相关设计：`docs/design/2026-04-28-dolphin-engine-switch-design.md`
