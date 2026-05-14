# Metadata Sync, DataStudio, and Workflow Guidance Design

**Date:** 2026-05-14
**Goal:** 减少元数据同步对未变化表的写入，明确 DataStudio 中 Doris 存在但平台未同步表的状态，并在任务变更工作流后引导用户发布和上线。
**Tech Stack:** Java 8 + Spring Boot 2.7 backend, Vue 3 + Vite + Element Plus frontend, MySQL/Flyway, Doris-compatible JDBC.

## Current State

- `DorisMetadataSyncService.syncAllMetadata` 会遍历数据源下所有库表；已存在表会调用 `syncExistingTable`，即使结构未变也会刷新 `syncTime` 并执行字段同步检查。
- `auditAllMetadata` 当前也会同步统计信息，导致“只比对”接口存在写入行为。
- 自动同步入口是 `DataSourceMetadataAutoSyncTask`，按 `doris_cluster.auto_sync + sync_cron` 触发全量结构同步并记录 `metadata_sync_history`。
- DataStudio 左侧表树从 Doris 实时目录和平台 `data_table` 合并；Doris 有但平台没有的表可以出现在树上，但没有 `data_table.id`。
- DataStudio 右侧编辑、删除、字段、血缘等操作依赖平台表 ID；当前未同步表的按钮状态和提示没有明确区分“未选择表”和“平台不存在该表”。
- `TaskEditDrawer` 保存任务后只提示创建/更新成功；如果任务绑定了工作流，用户不会被提醒工作流定义已变化，需要发布到 Dolphin。
- 工作流列表和详情页已有 `deploy` 与 `online` 操作，但发布成功后不会主动询问是否立即上线。

## Design

- 元数据结构同步改为“只落库变化部分”：
  - 新表仍创建 `data_table` 和字段。
  - 已存在表只在结构字段、注释、Doris DDL、表类型、分层、字段定义等真实变化时更新。
  - 结构同步不再因为统计字段变化或 `syncTime` 单独变化而更新未变表。
  - 平台有、Doris 无的表仍按现有引用保护策略删除或阻断删除。
- 统计信息拆到独立定时任务：
  - 新增统计同步方法，只同步行数、数据量、Doris 更新时间和统计快照。
  - 新增 `DataSourceMetadataStatisticsSyncTask`，扫描启用自动同步且 active 的数据源，独立周期执行统计同步。
  - 为避免额外数据库迁移，统计任务的历史记录继续写 `triggerType=auto`，`scopeType=all`，`scopeTarget=statistics`。
- 稽核接口恢复“只比对不同步”语义：
  - `auditAllMetadata` 和 `auditDatabase` 不再写统计字段。
  - 统计变动由统计任务或后续手动统计同步覆盖。
- DataStudio 未同步表状态显式化：
  - 前端合并 Doris 表和平台表时，为 Doris 有但平台没有的表标记 `metadataMissing=true`。
  - 左侧树在表名旁显示红色警示图标和 tooltip。
  - 右侧顶部显示提示：“当前表在 Doris 中存在，平台中不存在”，并提供“立即同步”文字按钮。
  - 点击立即同步先弹确认框，再调用按库表名同步的新后端接口；成功后强制刷新该库表列表、更新当前 tab 数据并解锁编辑/删除/字段/血缘操作。
  - 对未同步表点击删除、编辑、字段编辑等操作时，提示“该表未同步到平台，需同步后才能操作”，不再提示“请先选择表”。
- 任务保存后的工作流引导：
  - 创建或更新任务时，只要保存后的任务绑定了工作流，就弹框提示“工作流有变化，请跳转到任务调度页面，将工作流发布到 Dolphin”。
  - 用户确认后跳转到工作流详情页并携带轻量 query 标记；工作流详情页展示发布引导，不自动发布。
  - 工作流 `deploy` 成功后，如果未进入审批且工作流尚未 online，弹框询问是否立即上线；确认后复用现有 `online` 发布接口。
  - 工作流列表和详情页保持一致的发布成功后上线询问行为。

## Interfaces

- 新增后端接口：`POST /api/v1/tables/sync-metadata/database/{database}/table/{tableName}?clusterId=...`
  - 用于没有平台表 ID 的 Doris 表按名称同步。
  - 响应沿用现有同步响应字段，并追加 `database`、`tableName`、`tableId`。
- 新增前端 API：`tableApi.syncTableMetadataByName(database, tableName, clusterId)`。
- 新增或复用前端状态字段：
  - `metadataMissing: boolean`
  - `metadataStatus: 'missing' | 'synced'`
  - `metadataSyncing: boolean` 放在 tab state，用于右侧提示按钮 loading。
- 不新增数据库表。`metadata_sync_history` 枚举保持不变。

## Risks and Tradeoffs

- 结构同步不再用统计字段驱动表更新，短期内统计变化可能不会立刻显示在平台元数据中；独立统计任务会补齐这一点。
- 统计任务如果频率过高会增加 Doris 查询压力；默认应保守，计划按 10 分钟固定延迟实现，并可通过 Spring 配置覆盖。
- 按名称同步接口需要正确处理 URL 编码和特殊表名；前端必须使用 `encodeURIComponent`。
- 发布成功后询问上线会改变工作流操作节奏，但只在用户主动发布成功后出现，不会自动上线。

## Verification

- 后端单测覆盖未变化表不更新、结构变化才更新、统计任务只更新统计字段、按名称同步返回 `tableId`、稽核不写库。
- 前端单测或组件级测试覆盖未同步表标记、红色图标/提示、立即同步成功后刷新状态、未同步表删除提示。
- 前端构建通过 `nvm use && npm --prefix frontend run build`。
- 后端最小测试通过 Maven 指定测试类。
- 若本地 Doris/MySQL/Dolphin 环境可用，补充一次 DataStudio 表同步 smoke 和一次工作流发布后上线询问 smoke；若不可用，报告未覆盖的外部运行态。
