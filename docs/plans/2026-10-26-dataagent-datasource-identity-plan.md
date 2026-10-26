# DataAgent 主体与数据库账号映射实施计划

日期：2026-10-26。状态：**已决定，待一次性交付**。[设计](../design/2026-10-26-dataagent-datasource-identity-design.md)。

## 1. 任务

按依赖顺序开发，整套一次发布。

| # | 栈 | 任务 |
| --- | --- | --- |
| 1 | Flyway | 删除 `user_database_permissions`、`doris_database_users`、`platform_users`；创建 `datasource_subject_account`；OAuth 用户的 `external_id` 加唯一约束；查询历史增加人、智能体、检查账号、执行账号字段。 |
| 2 | backend | 删除 `UserMappingService`、`PermissionManagementController` 及相关 entity/mapper；新增账号映射的增删改查接口（仅管理员，保存时测试连接，不回传密码）。 |
| 3 | backend | `DorisConnectionService` 改为按当前用户取映射账号，缺映射拒绝；Data Studio 目录用该账号实时列出，合并已同步元数据时过滤不可见对象；结构编辑同样使用人的账号。元数据同步继续用数据源账号。 |
| 4 | backend-agent-api | 请求头读取用户标识、智能体 ID、独立模式；按标识前缀找 `sys_user`（`local:<用户名>` 或 `<provider>:<sub>`）；代人问数：人账号 EXPLAIN → 智能体账号执行；独立模式只用智能体账号；给模型的元数据取两个账号可见对象的交集。删除 `X-Agent-Data-Scope`、`AgentDataScopeFilter/Context`、表引用范围检查、平台库旁路。保留只读检查。 |
| 5 | DataAgent Python | 调平台时由后端填写用户标识（登录会话现有的 `auth_user_id`）和智能体 ID，定时任务设置独立模式；删除数据范围的发送和配置字段（Alembic 迁移）；服务令牌不进入模型可见环境。portal-mcp、odw-cli 同步。 |
| 6 | frontend | 数据源页增加"账号映射"；智能体页删除"数据范围"；Data Studio 缺映射、无权限时给出明确提示。 |
| 7 | 部署文档 | 必须开启认证；本地登录时平台与 DataAgent 的用户名保持一致，OAuth 登录时两边接同一个第三方；升级前 DBA 为人和智能体建好账号并登记。 |

## 2. 验证

用真实 PostgreSQL 和 MySQL（临时容器或独立库），准备人 P、智能体 A 两个账号和表 x、y：
- 代人问数：P 和 A 都有权 → 成功；P 无权 → EXPLAIN 阶段拒绝；A 无权 → 执行阶段拒绝；缺任一映射 → 拒绝。
- 跨库 JOIN：两边都有权成功，任一无权失败。
- 视图：账号只有视图权限、没有底表权限时，EXPLAIN 和执行都能通过。
- 撤销：删除映射或 REVOKE 后，下一次请求失败。
- Data Studio：目录只显示人的账号能看到的对象；写入和结构编辑按账号权限允许或拒绝。
- 身份：本地登录用户和 OAuth 用户都能问数；缺用户标识的普通问数被拒绝；找不到对应 `sys_user` 被拒绝；没有服务令牌被拒绝。
- 回归：后端、Python、前端测试和构建；按 AGENTS.md 跑一次真实模型问数（模型账号可用时）。

Doris 由用户在内网验证：EXPLAIN 对无权表报错；视图权限不依赖底表。

结果写入 `docs/reports/<日期>-dataagent-datasource-identity-verification.md`。

## 3. 发布与回滚

发布：备份平台库和 DataAgent 库，停服务，执行 Flyway 和 Alembic，整套发布。缺映射就不能使用，不做兼容放行。

回滚：停服务，恢复两库备份和旧版本程序。
