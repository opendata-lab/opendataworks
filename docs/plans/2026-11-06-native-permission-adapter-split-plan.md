# 原生权限适配器按引擎拆分计划

- 状态：已完成（2026-11-06：单测与 Doris 2.0.15、PostgreSQL 16、MySQL 8 平台 HTTP 全流程通过）
- 设计：[原生权限适配器按引擎拆分设计](../design/2026-11-06-native-permission-adapter-split-design.md)
- 涉及：仅后端 `backend/core`（无前端、数据库迁移或部署变化）

## 任务

1. `spi/DatasourceAdapter` 增加 `permissions()` 默认方法；`MysqlAdapter`、`PostgresqlAdapter`、`DorisAdapter` 覆盖。
2. `service/permission/NativePermissionAdapter` 改为抽象基类；新增 `adapters/mysql/MysqlProtocolPermissionAdapter`、`MysqlPermissionAdapter`、`adapters/postgresql/PostgresqlPermissionAdapter`、`adapters/doris/DorisPermissionAdapter`，逐方法搬移各引擎分支，SQL 文本保持一致。
3. `DatasourcePermissionService.nativeAdapter` 改用注册表；删除 `forType`；测试改为直接构造各引擎适配器。

## 验证

- 全部权限相关单测：`mvn -o -pl core test -Dtest='*Permission*,ManagedLoginSqlTest'`（含 MySQL／PostgreSQL／Doris SQL 断言）。
- 本机 8082 重新构建后跑平台 HTTP 全流程：Doris 2.0.15（docker）与本机 MySQL 数据源各执行建角色→授权→分配成员→诊断→撤销→删除；PostgreSQL 16 用临时 docker 容器跑同一流程（含序列授权、Schema 授权与 WITH INHERIT 继承）。

## 回滚

单 PR，回滚即还原该合并提交；无数据或配置变更。
