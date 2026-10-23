# PostgreSQL 数据源管理

## 现状与问题

Vue 数据源管理及 Java 数据源服务只接受 DORIS/MYSQL，连接、表字段查询使用 MySQL JDBC 和系统表。仅添加类型选项无法连接或同步 PostgreSQL。

## 范围与方案

- 前端沿用 Element Plus 表单，增加 PostgreSQL 类型（API 值 `POSTGRESQL`）、默认端口 5432 和必填连接数据库 `databaseName`。编辑保留数据库及留空密码语义；切换类型只替换原类型的默认端口，保留自定义端口。
- Java 8 / Spring Boot 后端增加 PostgreSQL JDBC 驱动、类型校验和可空 `doris_cluster.database_name`。MySQL 仍作为平台自身的持久化数据库。
- 一个 PostgreSQL 数据源连接一个 database；现有 `/databases` 接口、`DataTable.dbName` 对该类型表示该 database 内的 schema。只枚举账号可用的非系统 schema 和可见表/视图，避免同名表跨 schema 混淆。通过 JDBC `setSchema` 设置查询上下文，不做字符集初始化。凭据与 Doris/MySQL 走同一套用户映射（`doris_database_users` 按 schema 登记只读/读写账号）：该 schema 未登记映射账号时使用数据源配置账号；登记了映射账号但用户未授权时直接拒绝，不再退回数据源账号（三种数据源统一）。元数据枚举仍使用数据源账号，按用户过滤见 [待办](../TODO.md)。
- 独立 PostgreSQL catalog SQL 工具集中类型、注释、主键、默认值和统计查询，连接服务按引擎分派，保留现有同步服务的统一字段契约。
- 行数来自 PostgreSQL catalog 估计值，未知时保留 null；大小来自关系大小函数；不伪造 PostgreSQL 不提供的表创建/修改时间。表及列预览使用双引号并转义标识符。
- PostgreSQL 暂不提供完整可恢复 DDL 导出：元数据同步不调用 SHOW CREATE TABLE；DDL 查看明确提示使用 pg_dump。现有 Doris schema 备份、物理结构编辑能力限制沿用。
- DataAgent 经 Java agent API 支持 PostgreSQL：数据源解析返回 `engine=postgresql`（`database` 为 schema），`AgentJdbcExecutor` 以服务端只读事务（`readOnlyMode=always`）执行查询；DDL 接口从系统目录生成仅含列定义的参考 DDL。不扩展 DolphinScheduler 数据源能力。
- 列举表时排除分区子表（`relispartition`），只同步分区父表。
- 安全：数据源地址只接受主机名、IPv4、IPv6，拒绝会注入 JDBC URL 参数的字符；MySQL/Doris 模板路径的库名同样校验。DataAgent 的数据范围校验能识别双引号 schema，SQL 解析失败且含双引号标识符时直接拒绝。
- 元数据：主键按 `pg_constraint.conkey` 识别（不含 `INCLUDE` 列）；分区父表的行数和大小取叶子分区之和（PostgreSQL 12+）。查询、预览和 DataAgent 结果中的数组转为列表，json/jsonb 等驱动对象转为文本；DataAgent 查询通过 `setMaxRows` 限制驱动取数。
- 不支持的能力明确拒绝：DataStudio 对 PostgreSQL 表禁用字段编辑和删表（表信息中的平台属性仍可编辑），后端返回明确提示；新鲜度只支持 column / custom_sql 模式，配置 metadata / partition 时拒绝。DataAgent 解析同名 schema 时先按智能体数据范围收窄，已定位到具体表的 DDL 请求沿用该表的数据源。
- 数据查询页支持 PostgreSQL 基本信息/字段查看及手写 SQL 执行；自动生成 SELECT 使用 PostgreSQL 双引号，数据源类型判断避免将已同步 PostgreSQL 表当作 Doris。已完成查询页默认 SQL 和类型判断的组件回归；实际 analyze/execute HTTP 查询尚未验证。保留独立的 DataAgent 范围限制。

## 接口与部署

沿用 `/v1/doris-clusters` CRUD/测试连接/元数据入口，新增 `databaseName` 字段并规范类型为 `POSTGRESQL`。`V52` Flyway 在平台 MySQL 上添加可空列，旧记录无需回填。部署需同时更新前后端并执行迁移。

## 验证与取舍

覆盖保存校验、编辑保留、端口/表单、JDBC 分派、真实 PostgreSQL schema/表/列/统计/预览。运行 Java 定向测试和前端构建；本地环境允许时通过实际 HTTP 创建、连接及同步并核对平台存储和同步历史。只读展示 DDL 的限制明确返回错误，避免展示不完整 SQL 作为备份。

参考：[pgJDBC 连接及 URL 参数](https://jdbc.postgresql.org/documentation/use/)、[PostgreSQL pg_class](https://www.postgresql.org/docs/current/catalog-pg-class.html)、[系统信息函数](https://www.postgresql.org/docs/current/functions-info.html)。

适配器架构的后续提案独立见[数据源适配器设计](2026-10-22-datasource-adapter-design.md)，按用户要求先出方案，尚未实现。
