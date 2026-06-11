# 表资产元数据版本历史设计

- 日期：2026-06-11
- 主题：table-metadata-version
- 影响范围：backend（schema / 服务 / API）、frontend（Data Studio 右侧面板）

## 1. 现状与问题

表资产元数据（`data_table` / `data_field`）目前被多条路径直接覆盖更新，没有任何版本历史：

- 手动路径：`DataTableService.create/update/createField/updateField/deleteField`、`restoreDeprecatedTable`，以及建表向导 `TableCreateService.create`、巡检一键修复 `InspectionService.fixReplicaCountIssue`（直改 `replicaNum`）。
- 自动路径：`DorisMetadataSyncService.syncNewTable/syncExistingTable`（手动触发与 `DataSourceMetadataAutoSyncTask` 定时触发共用此漏斗）。

用户无法回答"这张表的元数据何时、被谁、改成了什么"。已有的 `metadata_sync_history` 只按"一次同步操作"聚合记录，不支持按表查询，也不覆盖手动编辑。

## 2. 目标与范围

- 每张表可查看自己的版本历史；**仅当元数据真正变化时记录新版本**。
- 仅查看 + 版本对比，不支持回滚（不在本次范围）。
- 统计类波动（行数、存储大小、Doris 更新时间等）不得产生版本。

## 3. 方案

镜像仓库中已验证的 `workflow_version` 快照模式：

- 新增追加式表 `data_table_version`，存储规范化元数据快照 JSON 与 SHA-256 哈希（`snapshot_hash`）。
- 统一入口 `TableMetadataVersionService.captureVersion(tableId, triggerSource, operator)`：从 DB 重载表 + 字段（调用方实体可能是部分更新，不可信）→ 构建白名单快照 → 规范化序列化（对象键排序，复用 `WorkflowService` 的 canonicalize/sha256 实现模式）→ 与最新版本哈希比对，相同则 no-op，不同则插入 `version_no + 1` 并生成变更摘要。
- `captureVersion` 内部吞掉所有异常仅记日志：同步路径运行在 `@Transactional(rollbackFor = Exception.class)` 大事务中，版本记录失败不允许回滚业务写入。
- `(table_id, version_no)` 唯一键防并发重复插入（手动编辑与定时同步竞争时捕获重复键异常跳过）。

### 3.1 快照内容（schemaVersion=1）

- `table` 节点（白名单）：tableName、dbName、tableComment、tableType、layer、businessDomain、dataDomain、owner、status、tableModel、partitionColumn、distributionColumn、keyColumns、bucketNum、replicaNum。
- `fields` 数组：按 `fieldOrder, fieldName` 排序，每项含 fieldName、fieldType、fieldComment、isNullable、isPrimary、isPartition、defaultValue、fieldOrder。
- **排除（永不触发版本）**：rowCount、storageSize、dorisUpdateTime、dorisCreateTime、syncTime、isSynced、clusterId、customIdentifier、statisticsCycle、updateType、lifecycleDays、originTableName、deprecatedAt、purgeAt、时间戳、deleted，以及 **dorisDdl**——Doris `SHOW CREATE TABLE` 输出包含动态分区列表，分区表每日变化，纳入快照会造成版本噪声；真正的结构变化（列/键/分桶）必然同时反映在白名单字段中。

### 3.2 Hook 全图

| 入口 | trigger_source | 说明 |
| --- | --- | --- |
| `DataTableService.create` | table_create | 插入后捕获（此路径字段单独创建，v1 可能无字段） |
| `TableCreateService.create` | table_create | 建表向导，最终 updateById 后捕获（v1 含全部字段） |
| `DataTableService.update` | manual_edit | 同时覆盖 updateTableComment / softDeleteTable 路由 |
| `DataTableService.restoreDeprecatedTable` | manual_edit | 恢复软删表（改名 + status） |
| `DataTableService.createField/updateField/deleteField` | manual_edit | 每次字段变更一次捕获 |
| `InspectionService.fixReplicaCountIssue` | inspection_fix | 直改 replicaNum 的旁路 |
| `DorisMetadataSyncService.syncNewTable` | metadata_sync | 字段同步完成后捕获 → v1 |
| `DorisMetadataSyncService.syncExistingTable` | metadata_sync | 守卫 `tableMetadataUpdated \|\| fieldChanges.hasChanges()`，每张变化表恰好一次 |

- 统计-only 路径（`syncAllStatistics` / `syncDatabaseStatistics` / `syncTableStatisticsOnly`）不加 hook；即使误入捕获，白名单也保证哈希不变（双重防护）。
- 守卫中 `tableMetadataUpdated` 可能因 volatile-only 字段（isSynced/dorisDdl/clusterId 等）为 true，此时捕获后哈希相同、正确地不产生版本。

### 3.3 初版语义（lazy baseline）

不做存量回填。表在功能上线后第一次 `captureVersion` 时获得 v1（建表 / 首次手动编辑 / 首次同步检出变化）。存量表"变更前"的状态不会被记录，历史只向前生长。取舍：迁移零成本、部署期间不批量写入上万基线行；代价是首个版本即"变更后"状态。

### 3.4 版本保留策略：永不清理（已确认）

表的全部删除路径（`DataTableService.delete`、`purgeTableMetadata`、同步侧删除）均为 `@TableLogic` 软删除，`data_table` 行本身保留。版本历史作为审计线索同样**永久保留**，本次变更不包含任何版本删除逻辑（服务不提供删除方法）。表 ID 自增不复用，孤儿版本无副作用；若未来存储成为问题，另行设计独立清理任务。

注意：软删除本身会产生一个版本（改名 + status=deprecated）——符合"状态属于元数据"的范围定义；恢复亦然。

## 4. 接口

新 controller `DataTableVersionController`（`/v1/tables`，避免膨胀已 1100+ 行的 `DataTableController`）：

- `GET /v1/tables/{id}/versions?pageNum=1&pageSize=20` → `Result<PageResult<DataTableVersion>>`，列表不返回 `metadataSnapshot` 大字段
- `GET /v1/tables/{id}/versions/{versionId}` → 含快照全文
- `POST /v1/tables/{id}/versions/compare`，body `{leftVersionId, rightVersionId}` → 结构化 diff（表属性变更 / 字段增删改）+ unified rawDiff（复用 workflow 版本对比的 LCS diff 实现模式）

## 5. 前端

Data Studio 右侧面板（`DataStudioRightPanel.vue`）在"访问情况"后新增"版本"标签页（懒加载）：

- `TableVersionHistoryPanel.vue`：版本列表（版本号 / 变更摘要 / 来源 / 操作人 / 时间）+ 分页 + 查看快照 + 选两个版本对比
- `TableVersionCompareDialog.vue`：摘要计数 + 结构化分区（表属性变更、字段增删改）+ rawDiff 视图，改编自 `WorkflowVersionComparePanel.vue`

## 6. 权衡与备选

- 备选"记录 diff 而非全量快照"：被否决——全量快照 + 哈希实现简单、可独立查看任意版本、与 workflow_version 模式一致；快照体积（数百字段的宽表约几十 KB）由 MEDIUMTEXT 承担。
- 备选"复用 metadata_sync_history"：被否决——其粒度是"一次同步操作"，不按表组织，且不覆盖手动编辑路径。
- 服务端对比 vs 前端对比：选服务端，与 workflow 版本对比一致，前端只渲染。
