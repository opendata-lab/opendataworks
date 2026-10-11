# 数据表数据源类型判定执行计划

- 日期: 2026-06-22
- 关联设计: `docs/design/2026-06-22-data-table-source-type-design.md`
- 影响栈: 后端（Java · Spring Boot 2.7 · MyBatis-Plus）
- 原则: 明确表对象类型、数据源引擎、物理同步状态的边界；最小化 PR #376 修复面。

## T1 — 文档化语义

- 目标: 记录 `table_type`、`source_type`、`is_synced` 的职责边界和新建表语义。
- 触及文件:
  - `docs/design/2026-06-22-data-table-source-type-design.md`
  - `docs/plans/2026-06-22-data-table-source-type-plan.md`
- 验证: 文件命名、路径和交叉链接符合仓库规则。

## T2 — 数据源类型归一化

- 目标: 新增轻量 `DatasourceType`，集中处理 `DORIS/MYSQL/OCEANBASE/UNKNOWN` 归一化，避免业务代码散落字符串判断。
- 触及文件:
  - 新增 `backend/src/main/java/com/onedata/portal/util/DatasourceType.java`
- 验证:
  - 编译通过。

## T3 — 引入 TableEngineHandler

- 目标: 将 Doris/MySQL/未知数据源的建表元数据规范化和物理 DDL 操作分到不同类。
- 触及文件:
  - 新增 `backend/src/main/java/com/onedata/portal/service/table/TableEngineHandler.java`
  - 新增 `backend/src/main/java/com/onedata/portal/service/table/TableEngineHandlerRegistry.java`
  - 新增 `backend/src/main/java/com/onedata/portal/service/table/DorisTableEngineHandler.java`
  - 新增 `backend/src/main/java/com/onedata/portal/service/table/MysqlTableEngineHandler.java`
- 做法:
  - Doris handler 负责 Doris DDL 生成、Doris 专属字段填充和 Doris 物理操作。
  - MySQL handler 清理 Doris 专属字段，并负责 MySQL rename/drop table、alter comment、add/modify/drop column。
  - MySQL handler 对 Doris 专属分桶/副本设置明确拒绝。
  - 无数据源/未知数据源走 metadata-only 规范化，不写 Doris 专属字段。
- 验证:
  - handler 单测或服务单测覆盖 Doris/MySQL/metadata-only 分发。

## T4 — 收敛 DataTableService 判定

- 目标: 删除 `replica_num/table_model/bucket_num` 等 Doris 属性推断逻辑，按 `is_synced + actual cluster source_type` 判断是否需要 Doris DDL。
- 触及文件:
  - `backend/src/main/java/com/onedata/portal/service/DataTableService.java`
- 做法:
  - Doris 表设计器创建时校验目标数据源必须是 `source_type=DORIS`。
  - 新增 `resolveSyncedPhysicalEngine(DataTable, Long)`。
  - 请求参数 `clusterId` 优先，表上的 `cluster_id` 兜底。
  - 未同步表仅改本地元数据。
  - 已同步表由对应 `TableEngineHandler` 处理物理 DDL。
  - metadata-only/MySQL 表创建时清理 Doris 专属字段。
- 验证:
  - 新增/更新服务单测。

## T5 — 自动清理任务委托统一判定

- 目标: `DataTableAutoPurgeTask` 不再复制旧 `isDorisTable` 启发式逻辑。
- 触及文件:
  - `backend/src/main/java/com/onedata/portal/scheduled/DataTableAutoPurgeTask.java`
- 验证:
  - 服务单测覆盖判定；编译确认任务调用签名正确。

## T6 — schema 默认值修正

- 目标: `replica_num` 从 Doris 专属字段回归为可空属性，不再污染新建普通表/MySQL 表。
- 触及文件:
  - 新增 `backend/src/main/resources/db/migration/V46__data_table_clear_non_doris_physical_fields.sql`
- 做法:
  - 修改 `data_table.replica_num` 默认值为 `NULL`。
  - 清理无数据源或非 Doris 数据源表上的 Doris 专属字段。

## T7 — 单元测试

- 目标: 锁定修复场景和未来非 Doris 扩展行为。
- 触及文件:
  - 新增 `backend/src/test/java/com/onedata/portal/service/DataTableServiceTest.java`
- 覆盖:
  - metadata-only 创建不会保留 `replicaNum=1`。
  - MySQL 表创建不会保留 Doris 专属字段。
  - 未同步表新增字段只写元数据。
  - 已同步 Doris 表即使请求未传 `clusterId`，也使用表所属数据源执行 Doris DDL。
  - 已同步 MySQL 表结构变更委托 MySQL handler，不调用 Doris DDL。
  - MySQL handler SQL 生成覆盖表注释、字段新增、字段重命名/修改、字段删除和 Doris 专属副本拒绝。
  - Doris 表设计器拒绝 MySQL 数据源。

## T8 — 验证

- 目标: 证明 PR #376 原有重构测试仍通过，并补一轮生命周期 smoke。
- 命令:
  - `mvn -pl backend -am -DfailIfNoTests=false -Dtest=DataTableServiceTest,TableCreateServiceTest,MysqlTableEngineHandlerTest,DataTableControllerTest,DataTableMetadataSyncServiceTest test`
  - `mvn -pl backend -am -DskipTests compile`
- 可选有状态验证:
  - 使用本地 Podman MySQL/Redis 启动 backend。
  - 创建普通 metadata-only 表后确认 Doris 专属字段为空。
  - 创建已同步 MySQL 表元数据后，通过 HTTP 驱动 MySQL 字段 CRUD、软删除、恢复、清理。

### 2026-06-22 本地验证结果

- 单元/控制器测试:
  - `mvn -pl backend -am -DfailIfNoTests=false -Dtest=DataTableServiceTest,TableCreateServiceTest,MysqlTableEngineHandlerTest,DataTableControllerTest,DataTableMetadataSyncServiceTest test`
    - 结果: `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`
- 编译/打包:
  - `mvn -pl backend -am -DskipTests compile` 通过。
  - `mvn -pl backend -am -DskipTests package` 通过。
- 有状态 smoke:
  - MySQL: Podman 容器 `data-portal-mysql`，`127.0.0.1:3306`。
  - 平台临时 schema: `opendataworks_pr376_engine_e2e`。
  - 物理 MySQL 临时 schema: `odw_mysql_engine_physical`。
  - Backend: `SERVER_PORT=18080`，`AUTH_ANONYMOUS_ENABLED=true`，临时 schema 上 Flyway 成功应用 46 个迁移。
  - HTTP 流程:
    - 插入 `source_type=MYSQL` 数据源，物理库创建 `mysql_orders` 表。
    - `POST /api/v1/tables` 创建已同步 MySQL 表元数据，并确认 `replica_num/table_model/bucket_num` 入库为 `NULL`。
    - `PUT /api/v1/tables/{id}/comment` 确认物理 MySQL 表注释更新。
    - `POST/PUT/DELETE /api/v1/tables/{id}/fields` 确认物理 MySQL 字段新增、重命名/改类型、删除。
    - `soft-delete -> restore -> soft-delete -> purge-now` 确认物理 MySQL 表重命名、恢复和删除。
    - 查询 `information_schema.COLUMNS` 确认 `data_table.replica_num` 默认值为 `NULL`。
  - 结论: 已同步 MySQL 表走 MySQL handler/JDBC DDL，不再误调用 Doris；metadata-only/MySQL 表不再保留 Doris 专属副本字段。
  - 清理: 已停止临时 backend，并删除 `opendataworks_pr376_engine_e2e` 和 `odw_mysql_engine_physical` schema。

## 回滚

- 回退本计划对应提交即可恢复旧启发式判定。
- 文档、枚举、服务判定和测试均为单次收敛改动，不涉及 schema 和对外 REST 签名变更。

## T9 — 2026-10-11 修复 MySQL 同步状态

- 修正 `DorisMetadataSyncService` 新表导入和已有表同步的 `is_synced` 写入，使成功导入的 MySQL 表进入既有 MySQL handler。
- 在 `DorisMetadataSyncServiceTest` 覆盖新 MySQL 表导入、历史 MySQL 未同步记录重新同步，以及再次同步不产生无意义更新。
- 运行该同步服务、`DataTableServiceTest`、`MysqlTableEngineHandlerTest` 的定向测试，重新打包后端。
- 使用本地 MySQL 8.0.43（127.0.0.1:3306）、正式前端、真实 HTTP/UI 验证临时表字段及注释更新，直接核对 information_schema，验证新增与删除字段及新鲜度持久化。
- 验证记录见 `../reports/2026-10-11-datastudio-mysql-smoke.md`。回退代码可恢复原同步状态行为；本轮不批量迁移历史记录。

## 2026-10-11 后续执行

- 在字段同步更新中显式设置 default_value，补清空与重复同步回归。
- 用专用 MySQL 表验证移除默认值持久化为 NULL、空字符串默认值仍保留、连续同步零变更。
- 运行同步服务定向测试、后端打包；清理测试连接、表和关联记录。
- 回退：撤销同步服务本轮局部改动，无 schema 迁移。

上述 2026-10-11 后续任务已完成，定向测试、构建及真实 MySQL/UI 复验通过，临时资源清理完毕。证据见 [后续验证报告](../reports/2026-10-11-datastudio-mysql-followup.md)。
