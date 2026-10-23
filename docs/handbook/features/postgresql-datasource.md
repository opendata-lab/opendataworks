# PostgreSQL 数据源

要求 PostgreSQL **12 及以上**（元数据同步使用 `pg_partition_tree`）。

在「数据源管理 → 新增数据源」选择 **PostgreSQL**，填写地址、端口（默认 5432）、连接数据库、用户名及密码，保存后可测试连接、比对/同步元数据和查看同步历史。编辑时密码留空保留原密码。

一个数据源对应一个 PostgreSQL database；目录树中的数据库层级对应这个 database 内的 schema。连接账号需要 database 的 CONNECT 权限、目标 schema 的 USAGE 权限及目标表/列的访问权限。不同 schema 下的同名表分别同步。若要接入另一 database，请创建另一个数据源。

支持表、视图、字段类型及精度、字段顺序、默认值、可空性、主键和注释同步，支持数据预览及表统计。行数使用 PostgreSQL 统计估计，尚未 ANALYZE 的表可能无行数；创建时间及更新时间不提供。分区表只同步父表，行数和大小为各分区之和。

地址只接受主机名、IPv4 或 IPv6，不能带路径或参数。如果某个 schema 在用户映射中登记了只读/读写账号，未授权的用户访问该 schema 会被拒绝，不会退回使用数据源账号。

在 Data Studio 中，PostgreSQL 表的表名、表注释、字段和删表不能在线修改，需要在数据库中修改后重新同步；分层、业务域等平台属性仍可编辑。新鲜度检查只支持 column 和 custom_sql 模式。智能查询中，PostgreSQL 未加引号的标识符按小写匹配数据范围，带引号的按原样匹配。

智能查询（DataAgent）可直接查询已同步的 PostgreSQL schema，按 PostgreSQL 方言生成只读 SQL；按用户映射的 schema 账号或数据源配置账号连接，服务端强制只读事务。分区表只同步父表。

当前范围不包含 PostgreSQL 物理结构编辑、Doris schema 快照备份和完整 DDL 导出。DDL 导出请使用 PostgreSQL 的 `pg_dump --schema-only`。

升级需部署新版前后端；后端启动时 Flyway V52 自动为平台 MySQL 的 `doris_cluster` 增加 `database_name` 列。平台本身仍使用 MySQL 存储配置和元数据。

设计：[PostgreSQL 数据源管理](../../design/2026-10-22-postgresql-datasource-design.md)。
