# 数据表数据源类型判定设计

- 日期: 2026-06-22
- 背景: PR #376 本地端到端验证发现，新建元数据表因 `data_table.replica_num` 默认值被既有 `isDorisTable` 误判为 Doris 表。
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）。不涉及前端、DataAgent、部署。
- 性质: 行为修正 + 语义收敛。

## 1. 现状

`data_table` 当前同时保存三类信息:

- SQL 对象类型: `table_type`，现有语义为 `BASE TABLE` / `VIEW` / `MATERIALIZED VIEW`。
- 数据源归属: `cluster_id` 指向 `doris_cluster`；该表虽仍命名为 Doris cluster，但 V16 已扩展 `source_type`，表示 `DORIS` / `MYSQL` 等数据源类型。
- Doris 建表属性: `table_model`、`bucket_num`、`replica_num`、`distribution_column`、`key_columns`、`partition_column`、`doris_ddl`。

`DataTableService` 和 `DataTableAutoPurgeTask` 仍使用 `isDorisTable` 启发式判断物理 Doris 表。该判断把 `is_synced=1` 或任意 Doris 属性存在都视为 Doris 表，包括 `replica_num > 0`。

由于 `V1__init.sql` 中 `replica_num INT DEFAULT 1`，普通新建元数据表会天然带上 `replica_num=1`，从而在字段增删改、软删除、恢复、清理等路径误入 Doris DDL 同步逻辑。

## 2. 问题

- `replica_num` 是 Doris 建表参数，不是表所属引擎或物理同步状态。
- `table_type` 已用于 SQL 对象类型，不能复用为 `DORIS/MYSQL/OCEANBASE` 这类引擎类型。
- `is_synced=1` 只表示本地元数据对应外部物理对象，不表示该对象一定是 Doris。
- 后续引入 OceanBase 等数据源时，继续用 Doris 属性推断会让非 Doris 表误走 Doris DDL API。

## 3. 目标语义

数据表相关判断拆成三件事:

| 概念 | 字段/来源 | 示例 | 用途 |
|------|-----------|------|------|
| SQL 对象类型 | `data_table.table_type` | `BASE TABLE` / `VIEW` | 区分表、视图、物化视图 |
| 数据源引擎 | `data_table.cluster_id -> doris_cluster.source_type` | `DORIS` / `MYSQL` / 后续 `OCEANBASE` | 判断由哪个物理连接/handler 处理 |
| 物理同步状态 | `data_table.is_synced` | `0` / `1` | 判断本地变更是否需要同步到外部物理对象 |

因此，Doris DDL 同步条件为:

```text
is_synced = 1
AND actual_cluster_id is present
AND datasource.source_type = DORIS
```

其中 `actual_cluster_id` 使用请求参数优先、表记录 `cluster_id` 兜底，保留既有 API 支持调用方显式指定数据源的能力。

## 4. 新建表语义

- 普通元数据表:
  - `is_synced=0`
  - `cluster_id` 可为空，也可指向一个设计目标数据源
  - `table_type='BASE TABLE'`
  - 即使 `replica_num` 有默认值，也只作为属性保存，不触发物理 DDL。
- Doris 表设计器创建并同步:
  - 校验目标数据源 `source_type=DORIS`
  - 写入 `cluster_id`
  - Doris DDL 执行成功后设置 `is_synced=1`
  - 后续字段/生命周期变更按 Doris DDL 路径处理。
- Doris 表设计器只保存草稿:
  - 写入 Doris 设计属性和 DDL
  - `is_synced=0`
  - 后续变更只改本地元数据，不执行 Doris DDL。
- 元数据同步导入:
  - 同步服务根据数据源写入 `cluster_id`、`table_type`、`is_synced=1`
  - 数据源引擎永远从 `doris_cluster.source_type` 解析。

## 5. 方案

### 5.1 引入数据源类型归一化

新增轻量枚举 `DatasourceType`，集中归一化 `source_type` 字符串:

- `DORIS`
- `MYSQL`
- `OCEANBASE`
- `UNKNOWN`

本轮不扩大 `DorisClusterService` 当前支持范围；未知或未来类型仅用于避免误走 Doris DDL。

### 5.2 引擎 handler 分发

新增 `TableEngineHandler` 策略接口和 registry:

- `DorisTableEngineHandler`
  - 负责 Doris 建表 DDL 生成。
  - 负责 Doris 专属元数据字段填充: `table_model`、`bucket_num`、`replica_num`、`distribution_column`、`key_columns`、`partition_column`、`doris_ddl`。
  - 负责 Doris 物理变更: rename/drop table、alter comment、add/modify/drop column、分桶和副本调整。
- `MysqlTableEngineHandler`
  - 负责清理 Doris 专属元数据字段，确保 MySQL 表不会保存 `replica_num` 等 Doris 属性。
  - 负责 MySQL 物理变更: rename/drop table、alter comment、add/modify/drop column。
  - 对 Doris 专属的分桶、副本调整明确拒绝。
- `UNKNOWN`/无数据源
  - 按 metadata-only 处理，清理 Doris 专属字段，不执行外部物理 DDL。

`DataTableService` 只根据 `cluster_id -> source_type` 选择 handler，并维护本地元数据事务；不再直接拼接或执行具体引擎 DDL。

`TableCreateService` 保留现有 API，但 Doris 建表 DDL 和 Doris 专属字段写入委托给 `DorisTableEngineHandler`。创建时会校验 `dorisClusterId` 对应的数据源必须是 `source_type=DORIS`；后续如果新增 MySQL/OceanBase 建表入口，应新增对应 request/handler，而不是在 Doris 建表器中加分支。

### 5.3 收敛 Doris DDL 判定

在 `DataTableService` 内新增统一判定:

- `requiresDorisPhysicalSync(table, clusterId)`:
  - 未同步表直接返回 `false`
  - 已同步表必须解析实际数据源
  - `source_type=DORIS` 返回 `true`
  - 非 Doris 类型返回 `false`；写路径不再依赖它决定是否同步，而是通过 `resolveSyncedPhysicalEngine` 委托给对应 handler

所有表结构和生命周期写路径使用该判定:

- `updateTable`
- `updateTableComment`
- `softDeleteTable`
- `restoreTable`
- `purgeTableNow`
- `createField`
- `updateField`
- `deleteField`

### 5.4 自动清理任务

`DataTableAutoPurgeTask` 不再复制 `isDorisTable` 启发式逻辑。它委托 `DataTableService.dropPhysicalTableIfRequired(table)` 判断并执行物理删除:

- 未同步表: 只清理元数据。
- 已同步 Doris 表: Doris handler 先 drop Doris 表，再清理元数据。
- 已同步 MySQL 表: MySQL handler 先 drop MySQL 表，再清理元数据。
- 已同步未知类型或缺失数据源: 抛出明确异常并保留记录，等待补充对应 handler 或修复归属数据。

### 5.5 schema 默认值修正

`replica_num` 默认值改为 `NULL`。该字段是 Doris 专属副本数，不应污染 metadata-only 或 MySQL 表。迁移同时清理无数据源或非 Doris 数据源表上的 Doris 专属字段:

- `table_model`
- `bucket_num`
- `replica_num`
- `partition_column`
- `distribution_column`
- `key_columns`
- `doris_ddl`

## 6. 不做范围

- 不重命名 `doris_cluster` 表；这是更大规模的数据源模型重命名。
- 不新增 OceanBase 物理 DDL handler。
- 不把 Doris 建表字段迁移到独立扩展表。
- 不实现 MySQL 建表设计器；本轮只覆盖已同步 MySQL 表的常见物理变更。

## 7. 风险与权衡

- 已同步但 `cluster_id` 缺失的历史记录将不再凭 `is_synced=1` 直接走 Doris DDL；调用方需要传入 `clusterId`，或先修复表归属数据。
- 已同步 MySQL 表的结构/生命周期写操作会改走 MySQL DDL；Doris 专属属性如分桶/副本调整会被明确拒绝。
- 本轮保持 `source_type` 字符串模型，不引入跨模块 datasource 抽象，避免扩大 PR #376 的 refactor 面。

## 8. 验证

- 新增 `DataTableServiceTest` 覆盖:
  - `replicaNum=1` 的未同步表新增字段不要求 Doris 集群、不调用 Doris DDL。
  - 已同步 Doris 表使用表所属 `cluster_id` 执行 Doris DDL。
  - 已同步 MySQL 表委托 MySQL handler，不调用 Doris DDL。
  - MySQL handler 生成 rename/comment/field DDL，并拒绝 Doris 专属副本设置。
  - Doris 表设计器拒绝 MySQL 数据源。
- 运行 PR #376 既有目标测试。
- 如本地 MySQL/Redis 可用，重复表生命周期 HTTP smoke，确认不再需要手工清空 `replica_num`。

## 9. 2026-10-11 MySQL 真实验证补充

DataStudio 真实 MySQL 验证发现：`DorisMetadataSyncService` 在导入与更新 MySQL 表时仍写入 `is_synced=0`，与本文“元数据同步导入表示已关联物理对象”的语义冲突。字段保存虽然返回成功，却只更新平台记录，没有进入已实现的 MySQL handler。

修正同步成功后的导入和更新分支，统一写入 `is_synced=1`；Doris 专属属性仍按数据源类型处理。历史 MySQL 表在下一次针对该表同步时修复状态，不批量修改现有记录或运行数据库迁移。普通元数据草稿仍为 0，账号不可见对象仍为 inactive/0。REST 签名、数据结构、前端布局、DataAgent 和部署不变。修复后已同步 MySQL 对象的显式字段/注释修改会执行真实 MySQL DDL；以专用临时表验证并清理。

## 2026-10-11 真实验证后续：默认值同步

字段增量同步显式写入默认值，允许物理数据库移除默认值后将平台值清为 SQL NULL。范围仅限同步服务，不修改实体的全局更新策略。保留 NULL 与 SQL 空字符串默认值的区别；一次修正后重复同步应为零字段变更。通过实际 MySQL 复验清空、空字符串和非空默认值。
