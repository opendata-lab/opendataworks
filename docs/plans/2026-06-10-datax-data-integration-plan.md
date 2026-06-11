# DataX 数据集成实现计划

**Date:** 2026-06-10
**Design:** `docs/design/2026-06-10-datax-data-integration-design.md`
**Topic slug:** `datax-data-integration`

## 范围

落地设计文档中的方案 A：DataX 作为 dolphin 工作流 DATAX 节点；加固执行载荷与列映射语义；补建 / 改时校验；把"数据集成"升级为 DataX 维度管理视图。不做引擎抽象、不做任务级独立 cron、不做 Dinky。

## 前置核实

- 确认所用 DolphinScheduler 版本的 DATAX 任务参数 schema（字段名 `customConfig` / `json` / `dsType` / `dataSource` / `dtType` / `dataTarget` / `sql` / `targetTable` / `jobSpeedByte` / `jobSpeedRecord` / `xms` / `xmx` / `preStatements` / `postStatements`）。
- 确认 dolphin worker 已装 DataX 运行时，以及是否需要专用 `environmentCode`。

## 任务

### T1 — 修正 DATAX taskParams（核心）

- 文件：`backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java`
- 改 `TaskParams`（约 L1409-1460）与 `buildTaskDefinition` 的 DATAX 分支（约 L1096-1098）：
  - 让 DATAX 序列化输出符合 dolphin schema，不泄漏 shell/sql 字段（拆分 DATAX 专用 params 对象或用 `@JsonInclude`/定制序列化）。
  - 支持向导模式（`customConfig=0` + `dsType`/`dataSource`/`dtType`/`dataTarget`/`sql`/`targetTable`）与自定义模式（`customConfig=1` + `json`）。
  - 评估是否需要从 `buildTaskDefinition` 暴露 `environmentCode`（当前硬编码 -1，约 L1079）。

### T2 — `column_mapping` 三态翻译

- 文件：`backend/src/main/java/com/onedata/portal/service/DataTaskService.java`（`publish()` 传 `getColumnMapping()` 处，约 L504-510）或 T1 的 DATAX 参数构建器。
- 实现：空 → 全列同步；列清单 JSON → 生成 `SELECT <cols> FROM source_table`；完整 DataX JSON → 自定义模式直传。

### T3 — 建 / 改时 DATAX 校验

- 文件：`backend/src/main/java/com/onedata/portal/service/DataTaskService.java`（`validateTask()`，约 L1278-1290）。
- 加入源 / 目标数据源、源 / 目标表校验，与 `validatePublishMetadata()` 对齐，避免非法 DATAX 任务静默落库。

### T4 — 数据集成页实化 + 任务列表过滤

- 文件：`frontend/src/views/integration/DataIntegration.vue`、`frontend/src/views/tasks/TaskEditDrawer.vue`（DATAX 表单块约 L208-263）；后端 `DataTaskService.list` 及其 controller。
- 把数据集成页改为 DataX 维度列表 + 创建 / 编辑（复用任务 API，按 `dolphinNodeType=DATAX` 过滤）。
- 抽出 DATAX 表单块为可复用组件，供数据集成页与任务编辑共用。
- 后端任务列表加可选 `dolphinNodeType` 过滤参数。

### T5 — 文档与部署前提

- 在 `deploy/` 与 handbook 注明：dolphin worker 需装 DataX 运行时、DATAX 节点的 `environmentCode` 约定。
- 同步更新本设计 / 计划文档若实现期范围漂移。

## 验证

- **后端单测**：扩展 `backend/src/test/java/com/onedata/portal/service/DataTaskServiceWorkflowMetadataTest.java`（Mockito + `ArgumentCaptor` 捕获 `buildTaskDefinition` 参数），断言：
  - DATAX `taskParams` 输出符合 dolphin schema 的关键字段；
  - `column_mapping` 空 / 列清单 / 完整 JSON 三态行为；
  - 建 / 改时 DATAX 校验拒绝缺字段。
- **最小后端编译 / 测试**：仅跑触达模块（`DataTaskService` / `DolphinSchedulerService`）。
- **前端**：先 `nvm use`，再跑 `integration/` + `tasks/` 触达区域的最小构建 / lint。
- **本地 smoke**（环境可用时）：建 DATAX 任务 → 发布 → 确认 dolphin DATAX 节点参数合法 → 跑一次实例 → 状态回流 `getLatestExecutionStatus`。若无 DataX 运行时，明确声明执行层未验证、仅验证载荷形状与单测。

## 回滚

- 改动集中在 `DolphinSchedulerService`、`DataTaskService` 与前端数据集成 / 任务编辑；无 schema 变更，回滚即还原这些文件。
- DATAX 参数构建若在新版本 dolphin 出问题，可临时保留旧 `TaskParams.datax` 行为作单层兼容（仅在确认版本差异后），避免级联回退。

## 触达文件清单

- `backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java`
- `backend/src/main/java/com/onedata/portal/service/DataTaskService.java`
- `frontend/src/views/integration/DataIntegration.vue`
- `frontend/src/views/tasks/TaskEditDrawer.vue`
- `backend/src/test/java/com/onedata/portal/service/DataTaskServiceWorkflowMetadataTest.java`
- `deploy/`（DataX worker 部署前提说明）
