# DataAgent 主体与数据库账号映射设计

日期：2026-10-26。状态：**已决定，待一次性交付**。代码基线 `2448e84b`。

关联：[计划](../plans/2026-10-26-dataagent-datasource-identity-plan.md)、[TODO](../TODO.md)、[适配器设计](2026-10-22-datasource-adapter-design.md)。

## 1. 模型

**平台只维护"主体 → 数据库账号"的映射，权限以数据库账号自身的 GRANT 为准。智能体代表人问数时，先用人的账号 `EXPLAIN`，再用智能体的账号执行，两边都由数据库判断，结果不超过两者的交集。**

- 主体：人（`sys_user`）和智能体。不同智能体身份不同。
- 每个主体在每个数据源上映射一个数据库账号；多个主体可以映射同一个账号。账号由 DBA 建好并授权，平台只登记和测试连接。
- 智能体映射的账号由 DBA 只授予读权限。
- 没有映射就不能访问，不退回任何默认账号。
- 平台不维护授权表，不解析 SQL 判断表引用。撤销 = 删除映射或在数据库里收回权限，下一次请求即生效。

## 2. 各路径用哪个账号

| 场景 | 账号 |
| --- | --- |
| 人在 Data Studio 查询、写入、结构编辑、浏览目录 | 人映射的账号；能看到什么、能不能写，由该账号决定 |
| 智能体代表人问数 | 人的账号执行 `EXPLAIN <SQL>`；通过后智能体的账号执行该 SQL。两个连接都只读，任一步失败即拒绝 |
| 智能体独立运行（定时任务等，无提问人） | 智能体映射的账号 |
| 给模型的表、字段、注释 | 分别用两个账号列出可见对象，取交集；独立运行只用智能体账号 |
| 元数据同步、运维检查 | 数据源原有账号，只给系统用，不用于任何主体的查询 |

Data Studio 目录用人的账号实时列出可见库表，平台已同步的元数据只用来补充展示信息，不显示该账号看不到的对象。

## 3. 代人问数的 EXPLAIN 检查

EXPLAIN 只生成执行计划、不执行，但会检查 SQL 引用的每个对象的权限。

| 引擎 | 依据 |
| --- | --- |
| PostgreSQL | EXPLAIN 走 ExecutorStart，做关系与列权限检查。[源码](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/executor/execMain.c) |
| MySQL | EXPLAIN 需要执行原语句所需的权限。[文档](https://dev.mysql.com/doc/refman/8.0/en/explain.html) |
| Doris | EXPLAIN 需要对被查询对象有 `Select_priv`；视图只看视图本身的权限，不依赖底表。需在内网实测确认。 |

视图在三种引擎上都是"能引用视图就看视图本身的权限"，EXPLAIN 与执行语义一致，交集成立。DBA 可以只把整理好的视图授予账号，用视图作为授权边界。

已知限制（写明，不做检测）：交集是**对象级**的。行级安全、按当前用户返回不同数据的视图或函数、`SECURITY DEFINER` 函数，在智能体账号下可能返回与人不同的行。DBA 不应让智能体账号访问这类对象。

其他规则：
- EXPLAIN 不带 ANALYZE；两个连接使用相同的 database/schema；SQL 原样检查、原样执行。
- 每次请求新建连接，不缓存"检查通过"。
- 跨库 JOIN：两个账号都能读即可。
- 错误只说明是"你的账号无权访问"还是"智能体账号无权访问"，不返回计划或数据。
- 额外开销是一次建连加一次 EXPLAIN。

## 4. 身份传递

- 平台和 DataAgent 都接入同一个第三方 OAuth 登录。DataAgent 后续独立部署，保留自己的登录和会话。
- 同一个人在第三方的用户 ID 相同；平台用 `sys_user.external_id` 识别他（加唯一约束）。
- DataAgent 调平台（现在是 HTTP agent API，拆分后是平台的 MCP，用同一个契约）时：
  - 现有服务令牌 `X-Agent-Service-Token` 证明调用方是 DataAgent 后端；
  - 请求头带用户 ID（第三方用户 ID）和智能体 ID，由 DataAgent 后端从已验证的登录会话和会话配置中填写，不来自模型或前端；
  - 独立运行必须显式标明"独立模式"，由后端为定时任务设置。普通问数缺用户 ID 直接拒绝，不能自动变成独立模式。
- 平台信任持有服务令牌的 DataAgent 后端，按用户 ID 找到 `sys_user`、再找两个映射。
- 服务令牌和数据库密码不进入模型能接触到的环境（工具参数、工作目录、Bash 环境变量）。
- 问数必须有登录用户；匿名部署或认证关闭时，不提供代人问数。

## 5. 删除与保留

删除：
- `doris_database_users`、`user_database_permissions`、`platform_users` 三张旧表，以及 `UserMappingService`、`/v1/permissions`。不迁移数据。
- SQL 表引用（数据范围）检查、`X-Agent-Data-Scope` 请求头、智能体的"数据范围"配置。
- DataAgent 用平台 Spring 数据源账号查询平台库的分支。

保留：
- SQL 只读检查（拦截写语句和多语句）。
- 连接只读、PostgreSQL `readOnlyMode=always`、行数和超时限制。
- 智能体的"可见范围"（谁能用这个智能体）。

## 6. 数据、界面与审计

- 新表 `datasource_subject_account(source_id, subject_type, subject_id, username, password)`，`(source_id, subject_type, subject_id)` 唯一；`subject_type` 为 `USER`（`sys_user.id`）或 `AGENT`（agent_id）。密码沿用现有数据源密码的存储方式，读接口不回传。
- 数据源页增加"账号映射"：选择人或智能体，填用户名和密码，保存时测试连接。智能体页删除"数据范围"。
- 现有查询历史增加：人、智能体、检查账号、执行账号。只有本人和管理员可看。

## 7. 发布与回滚

一次性上线：备份平台库和 DataAgent 库，停服务，执行迁移，整套发布。前置条件：开启认证；DBA 为每个要用的数据源给人和智能体建好账号，并在平台登记。缺映射就不能用，不做兼容放行。

回滚：停服务，恢复两库备份和旧版本程序。

## 8. 参考

- 飞书区分应用身份 `tenant_access_token` 和用户身份 `user_access_token`；豆包工作用飞书账号登录后继承该用户在飞书中的权限。[飞书接口说明](https://open.feishu.cn/document/server-docs/docs/bitable-v1/app-table-record/create)、[豆包工作](https://www.feishu.cn/content/article/7677519271848610746)
- Databricks Genie 区分计算凭据与最终用户的数据权限。[Genie](https://docs.databricks.com/aws/en/genie-agents/set-up)
- Metabase、Superset 建议使用最小权限的数据库账号，Superset 明确应用层 SQL 检查不能替代数据库授权。[Metabase](https://www.metabase.com/docs/latest/databases/users-roles-privileges)、[Superset](https://superset.apache.org/admin-docs/security/)
