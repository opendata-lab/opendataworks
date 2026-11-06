# 原生权限适配器按引擎拆分设计

## 现状

`NativePermissionAdapter` 是一个 571 行的单类，用 53 处 `postgres()`／`doris()` 分支同时实现 MySQL、PostgreSQL 与 Doris 的原生角色 SQL、回读与核验；工厂是自带的 `forType(type)` 字符串分支。数据源其它能力（连接、元数据、方言）已经按引擎拆成 `adapters/{mysql,postgresql,doris}` 下的 Spring 组件，由 `DatasourceAdapterRegistry.require(type)` 统一选择。

2026-11-06 修复 Doris 2.0 问题（#503、#504）时，Doris 的特殊处理（`default_cluster:` 前缀、CREATE／DROP ROLE 标识符、空格分隔权限）只能继续以分支插入共享方法，诊断文本一处还误作用于 MySQL（#505 关闭，并入本次）。

## 方案

沿用现有数据源适配器工厂，不新建工厂：

- `DatasourceAdapter` 新增 `default NativePermissionAdapter permissions()`，默认抛出「不支持该数据源权限管理」；MySQL、PostgreSQL、Doris 适配器各自返回本引擎的新实例（MySQL 实例持有连接级 `partial_revokes` 状态，故每次新建）。
- `NativePermissionAdapter` 改为抽象基类，只保留各引擎共用部分：字面量／标识符引用、动作能力表组装、成员授予语句骨架、默认空实现（`sequences`、`defaultRoleSql`、`literalDatabaseNames`、`validateSchemaScope`）。
- `adapters/mysql/MysqlProtocolPermissionAdapter`（抽象）：MySQL 协议共用的 `user@host` 账号、`CREATE/DROP USER`、`CURRENT_USER()` 身份解析。
- `MysqlPermissionAdapter`、`DorisPermissionAdapter` 继承它；`adapters/postgresql/PostgresqlPermissionAdapter` 直接继承基类。Doris 2.0 前缀与权限文本解析只存在于 `DorisPermissionAdapter`。
- `DatasourcePermissionService.nativeAdapter` 改为 `adapters.require(type).permissions()`；删除 `forType`。

## 接口与兼容

对外 HTTP 接口、生成的 SQL、回读与核验行为不变；服务仍通过 `postgres()`／`doris()` 判断少量编排差异（不在本次范围）。唯一可见变化：MySQL 诊断文本不再做 `default_cluster:` 替换（与 #505 意图一致）。

## 取舍

- 不把服务层的 8 处引擎判断一并下沉：那是编排逻辑，拆分需重新设计接口，超出「整理适配器」范围。
- 不引入新的注册表或 Spring Bean 管理权限适配器：复用 `DatasourceAdapterRegistry`，保持一个工厂。

计划见 [执行计划](../plans/2026-11-06-native-permission-adapter-split-plan.md)。
