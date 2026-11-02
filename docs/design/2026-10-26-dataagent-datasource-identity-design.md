# 主体数据库身份与统一表级权限中心设计

日期：2026-10-26。实施验证：2026-10-27，[验证报告](../reports/2026-10-26-dataagent-datasource-identity-verification.md)。历史身份机制状态：**已实施，待复核**。代码基线 `8767838f`。

2026-10-29 补充，2026-10-30 复核状态：第 1～8 节记录已实施的身份机制；第 9 节为本次新增的统一表级权限中心设计，**尚未实施**。不将文档完成视为权限功能已交付。

2026-11-02交付：用户管理与第10节权限中心已实现，本机MySQL／PG、浏览器、MCP与真实模型验收见[报告](../reports/2026-11-02-users-and-permissions-verification.md)。可信用户签名与真实Doris环境验证保留TODO；第1～9节为历史设计，不覆盖当前实现。

2026-11-01实施基准：第10节为当前生产实现契约，覆盖第9节中已标记失效的角色账号一一绑定草案。

2026-11-02验收补充：数据源连接配置的创建、修改、删除、默认源变更与管理连接测试同样仅管理员可调用；普通用户保留目录需要的源列表／描述读取，不显示配置写入口。不能只保护新角色API而允许普通用户改动其依赖的数据源连接。回归同时执行真实权限切面及普通用户HTTP拒绝验证。

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
| 给模型的表、字段、注释 | 分别用两个账号列出可见对象，取交集 |
| 元数据同步、运维检查 | 数据源原有账号，只给系统用，不用于任何主体的查询 |

Data Studio 目录用人的账号实时列出可见库表，平台已同步的元数据只用来补充展示信息，不显示该账号看不到的对象。

## 3. 代人问数的 EXPLAIN 检查

EXPLAIN 不带 ANALYZE，用于数据库对象权限检查；保留数据库原生错误语义，不执行回退查询。

| 引擎 | 依据 |
| --- | --- |
| PostgreSQL | EXPLAIN 走 ExecutorStart，做关系与列权限检查。[源码](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/executor/execMain.c) |
| MySQL | EXPLAIN 需要执行原语句所需的权限。[文档](https://dev.mysql.com/doc/refman/8.0/en/explain.html) |
| Doris | EXPLAIN 需要对被查询对象有 `Select_priv`；视图只看视图本身的权限，不依赖底表。需在内网实测确认。 |

2026-10-27 真实库复核：PostgreSQL 16 的仅视图 SELECT 可以通过；MySQL 8.0.45 即使授予视图 SELECT、SHOW VIEW，EXPLAIN 仍要求底表权限，因此仅视图授权的代人问数会拒绝。保持同一条 EXPLAIN 主路径，不增加 LIMIT 0 或放宽权限的分支；DBA 需了解该限制。Doris 仍需内网实测。

给模型的元数据：每个账号只查一次 `information_schema.columns`，它只返回该账号有权限的字段（PostgreSQL 16、MySQL 8.0 已实测，含列级授权）；两份结果按 库/表/字段 取交集。Doris 需内网确认同样按权限过滤。

已知限制（写明，不做检测）：交集是**对象级**的。行级安全、按当前用户返回不同数据的视图或函数、`SECURITY DEFINER` 函数，在智能体账号下可能返回与人不同的行。DBA 不应让智能体账号访问这类对象。

其他规则：
- EXPLAIN 不带 ANALYZE；两个连接使用相同的 database/schema；SQL 原样检查、原样执行。
- 每次请求新建连接，不缓存"检查通过"。
- 跨库 JOIN：两个账号都能读即可。
- 错误只说明是"你的账号无权访问"还是"智能体账号无权访问"，不返回计划或数据。
- 额外开销是一次建连加一次 EXPLAIN。

## 4. 身份传递

- 支持本地登录和第三方 OAuth 两种方式。DataAgent 后续独立部署，保留自己的登录和会话。
- 用户标识带来源前缀，沿用 DataAgent 现有的 `auth_user_id` 格式：本地登录为 `local:<用户名>`，第三方 OAuth 为 `<provider>:<sub>`。平台按前缀找 `sys_user`：`local:` 对应 `auth_source = local` 且 `username` 相同的用户；OAuth 对应 `external_id` 相同的 OAuth 用户。找不到就拒绝。
- 本地登录时，平台与 DataAgent 各有本地账号，靠相同的用户名对应同一个人，部署时需保持一致。
- DataAgent 调平台（现在是 HTTP agent API，拆分后是平台的 MCP，用同一个契约）时：
  - 现有服务令牌 `X-Agent-Service-Token` 证明调用方是 DataAgent 后端；
  - 请求头 `X-Agent-User-Id` 带用户标识、`X-Agent-Id` 带智能体 ID，由 DataAgent 后端从已验证的登录会话和会话配置中填写，不来自模型或前端；
  - 缺用户标识直接拒绝。会话里的定时消息以会话所有者的身份执行，和他手动发消息走同一条路径，没有"智能体独立运行"模式。
- 平台信任持有服务令牌的 DataAgent 后端，按用户标识找到 `sys_user`、再找两个映射。
- 服务令牌和数据库密码不进入模型能接触到的环境（工具参数、工作目录、Bash 环境变量）。odw-cli 原本从 Bash 环境读取服务令牌，因此运行期间使用 Python 后端进程内的本地 HTTP 工具通道：服务端固定本次用户/智能体身份并添加服务令牌，子进程只得到本次通道地址，不能通过改请求头切换身份；运行结束关闭通道。MCP 由可信运行器填写身份头，不从模型参数取值。
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

<a id="unified-access-design"></a>

## 9. 统一数据权限：数据源角色绑定数据库账号

### 9.1 最新决策、状态与范围

> **2026-10-30评审纠正，以下角色账号一一绑定草案不再作为实施依据。** 用户补充同一用户需要同时拥有三个Schema角色、并用一个连接访问其权限并集；此前“同源一个角色／角色绑定一个登录账号”无法满足跨Schema查询。角色是权限集合，登录账号是连接凭据，两者不得合并。当前建议为“平台角色映射Doris原生ROLE，用户在该源的一个数据库账号绑定多个ROLE”，用户仍无直接表授权。此为本轮修订建议，尚未认定用户已选定账号自动创建等生命周期方案；不要继续实施下述旧草案。逐引擎动作矩阵、即时授权与可信用户身份等独立规则仍可复用。参考[Doris RBAC](https://doris.apache.org/docs/3.x/admin-manual/auth/authentication-and-authorization/)与[多角色GRANT](https://doris.apache.org/docs/3.x/sql-manual/sql-statements/account-management/GRANT-TO/)。

2026-10-30用户在要求实施后进一步收拢模型：**每个数据源内置数据管理员、只读、读写角色；允许自定义角色；角色绑定数据库账号，用户只加入角色，不对用户单独授权。** 本节按此替换此前“每用户专属执行身份＋跨源角色＋权限域＋策略中心”的设计，配套计划同步收敛。第1～8节为历史实现记录，不代表新模型；此前原型尚未按本模型重做，不能据原型中的个人执行身份表单实施。

状态：**模型与实施计划已更新，应用实现尚未开始。** 用户已授权实施；本次补充先确定最新模型，不对真实数据库账号或授权做修改。

主路径只有一条：`已登录用户 → 该数据源的角色 → 角色绑定的数据库账号 → 数据库原生鉴权`。页面不再出现“执行身份”概念；它原本仅表示实际连接数据库的账号，现在直接显示在角色的“数据库账号”页签。物理连接仍需账号凭据，不再为每个用户保存一套。

技术栈沿用Java8／Spring Boot／MySQL平台库、Vue3／Element Plus；DataAgent保持独立仓库。保留统一中文业务动作、Schema／数据库／表／视图、即时原生授权与平台记录。暂不做用户直接授权、用户组、角色继承、多角色权限合并、独立策略／策略绑定、每用户数据库账号管理、SQL权限改写网关或后台授权同步队列。

### 9.2 角色边界与成员规则

- **数据源内角色**：角色只能属于一个sourceId。相同名称在不同数据源中是不同角色；不新增跨源角色。例如张三在MySQL是读写，在Doris是只读。
- **一个用户／数据源一个角色**：成员表以`(source_id, subject_type, subject_id)`唯一。重新分配角色是替换旧角色，不同时启用多个账号，不在后端猜测选择权限最高的账号。没有成员关系即无权限。
- **只给角色授权**：资源范围与动作仅绑定roleId。用户列表只显示“所在角色”，不提供库、表、动作单独勾选。成员加入／移除只修改平台关系，不为每人执行GRANT或REVOKE。
- **一个角色绑定一个数据库登录账号**：同一角色所有成员共用该账号。同一物理数据库中的同一个账号不能绑定权限不同的两个角色，否则原生权限取并集，角色隔离失效。管理端核实账号的真实身份（包含MySQL／Doris的host）及实例归属；不能仅按页面用户名判定唯一。跨数据源别名指向同一实例的重复绑定也须检查，未能确认时不标为独立隔离。
- **平台管理员与数据管理员分离**：平台admin可管理配置，不自动用采集／管理连接查询业务数据；仍需数据源角色成员关系。数据管理员是业务对象管理角色，不等于数据库root／超级用户，也不自动管理数据库用户或转授权。
- **用户停用**：平台访问立即拒绝。移除一个用户不撤销共享账号权限，否则会影响同角色其他成员；无需改数据库成员关系。
- **智能体**：沿用现有稳定agentId作为角色成员，不新增主体中心。只能分配只读角色；人的角色和Agent角色仍分别判断，不把人的写权限传播给模型。

多人共享账号意味着数据库原生日志通常只能看到角色账号；平台必须记录实际userId、roleId、sourceId、SQL／请求及时间。此模型只承诺平台内的用户权限；角色密码不发给终端用户。若另外发放共享密码供数据库直连，平台移除成员无法阻止其继续直连，需更换密码或另行设计个人账号，这不是平台成员撤销所能解决的。

### 9.3 内置角色与创建数据源

数据源创建成功时，在同一平台事务中初始化三个角色定义；数据库账号未绑定前显示“待配置账号”，不能仅凭角色名称宣称权限已生效。不自动创建数据库登录用户、不自动把源连接的root密码复制给角色。

| 内置角色 | 默认业务动作模板 | 不自动包含 |
| --- | --- | --- |
| 只读 | 查询数据、按需访问Schema／查看视图定义 | 数据写入、结构修改、转授权 |
| 读写 | 查询＋写入数据；PG／MySQL映射INSERT／UPDATE／DELETE，Doris映射Load | 建表、改结构、删表、转授权 |
| 数据管理员 | 查询、写入、创建对象及底层可独立授予的修改结构／删除对象 | root／超级用户、用户管理、Grant；PG所有权不能由角色名称伪造 |

这些是动作模板，不隐含全实例或所有库。管理员仍需选择库／Schema／表／视图范围并预览实际授权；不同数据源按能力禁用不支持项。内置角色保留名称及类型，不允许删掉定义，允许配置账号和资源范围；只读角色不能被改成写角色。自定义角色可从模板复制后修改范围／动作，不继承源角色。

创建源时的连接账号继续用于测试连接和元数据采集。原生授权可使用该连接，但必须单独检查其实际GRANT／REVOKE／回读能力；能力不足时在数据源高级配置填写专用授权管理账号。管理凭据只作管理调用，绝不作为没有成员／角色账号时的查询回退。无需为每个用户维护管理连接。

### 9.4 资源与有效权限

资源复用`doris_cluster`、`data_table`与字段目录，不另建完整资源树。角色授权条目明确资源类型SCHEMA／TABLE／VIEW、确切database／catalog／schema／对象名、统一动作及已确认的原生动作集合。标识符遵循引擎大小写及引用规则。

**库级原生作用域与批量当前对象分开**：MySQL／Doris的db.*可覆盖当前及未来对象，库级ALTER／DROP还可能影响库本身；确认前明示。PG Schema USAGE／CREATE不等于子表SELECT。批量当前表／视图只展开已采集快照；PG按创建者配置未来默认权限暂不做。

目录、搜索、详情、字段、预览、DDL及模型元数据均按当前数据源角色与绑定账号的已核实权限过滤；先过滤再分页／计数。元数据采集账号可看得更多，不扩大角色成员可见范围。自由SQL最终由角色账号原生鉴权，不用隐藏目录代替SQL访问控制。

已有库级、外部原生角色、PUBLIC、所有权或definer视图等可能使账号权限超过表单勾选。绑定／变更先读取实际权限并展示；仅能管理本平台明确接管的授权，不自动回收DBA已有权限，也不能把“平台没有勾选”当作原生拒绝。范围不符时拒绝标为已生效，明确要求调整数据库账号或确认扩大范围。对象删除／重建的原生名称权限可能重新生效，缺可靠代际标识时保留限制，不承诺快照能识别两次采集间的所有DDL。

### 9.5 最小数据模型

| 对象 | 字段与职责 |
| --- | --- |
| 复用`doris_cluster`、`sys_user`、`data_table`／字段 | 数据源、用户、采集目录；不复制主体／资源中心 |
| `datasource_role` | source_id、code、name、builtin_type、username／native_host、凭据引用、status、revision；角色及唯一数据库账号合在一条记录，不再单设execution_identity表 |
| `datasource_role_member` | source_id、role_id、subject_type(USER/AGENT)、subject_id；同源同主体唯一，role必须属于该源 |
| `datasource_role_grant` | role_id、resource_type、确切资源键、business_action、native_actions／映射版本、scope、已核实状态；资源动作直接绑定角色，不引入policy_binding |
| `datasource_permission_operation` | source_id、role_id、operator_id、幂等键、预览版本、批准的前后差异、逐步结果、state、时间；JSON步骤作为小型操作日志，不另建同步任务／账本中心 |

凭据用后端统一认证加密组件保存，读取API只返回账号、configured和掩码状态，不返回密码／密文。密钥由部署环境提供，不进平台库或Git。编辑时空密码保留旧值，换账号必须重新提供密码并真实校验。角色账号换绑后失效旧连接；当前每请求建连保持，将来连接池必须按roleId和revision隔离。

已有`datasource_subject_account`是旧个人／智能体账号绑定。迁移需管理员明确将旧账号归入哪个数据源角色、哪些成员保留；不能自动把个人最宽权限并到共享角色。完成一次迁移后旧写API和旧查询路径停用，不保留“没角色就查旧映射”的回退。账号密码不复制给每个成员。

### 9.6 原生权限 Adapter 与支持矩阵

在现有 Datasource Adapter 旁增加 `NativePermissionAdapter`，不把数据库分支散落在 Controller。核心操作为 `describeActions`、`inspectIdentity`、`readNativeState`、`validateBaseline`、`planChanges`、`applyStep`、`verifyState`。SQL 标识符、账号／host和角色名由对应方言处理，不能用普通 SQL 值转义代替。

**统一常用业务术语，按实际能力映射，不规定第一版只能QUERY，也不把全部数据库伪装成四个等价开关。** Adapter返回统一动作、原生动作集合、资源层级、作用域／未来对象语义、前提／副作用、是否可独立撤销、可下发／回读、观察项及版本限制。前端默认展示统一中文动作，原生细分按需展开；数据库确实不具备该粒度，或适配器尚不能可靠执行／回读时，才返回 `UNSUPPORTED_ACTION` 并说明原因。

| 引擎 | 当前纳入设计的原生动作 | 粒度与实际语义 |
| --- | --- | --- |
| MySQL 8.0 | 表级 SELECT、INSERT、UPDATE、DELETE、ALTER、DROP、INDEX、REFERENCES，以及按对象适用的 SHOW VIEW；CREATE／CREATE VIEW按指定父级资源管理 | 不强制合并成“读写”。需要读取已有列的写语句可能还需 SELECT；TRUNCATE 依赖 DROP，不能承诺仅授予清空而不允许删除。CREATE 等作用域明确列出，不默认给全实例权限 |
| PostgreSQL 16 | 表级 SELECT、INSERT、UPDATE、DELETE、TRUNCATE、REFERENCES、TRIGGER；连接／schema 的 CONNECT、USAGE；指定schema 的 CREATE；相关序列的 USAGE、SELECT、UPDATE | 表权限不等于序列权限。SERIAL/default nextval等按实际依赖列出必要序列权限；不对所有列一律补 USAGE。ALTER／DROP 来自所有权，不能执行不存在的 GRANT ALTER/DROP ON TABLE，也不自动转移所有权 |
| Doris 实际部署版本 | Select_priv、Load_priv、Alter_priv、Create_priv、Drop_priv、Show_view_priv，按实际版本支持的对象层级提供 | 界面显示“查询”“数据写入（Load）”“修改结构”“创建对象”“删除对象”“查看视图定义”等原生语义。Load包含Load、Insert、Delete等操作，不拆成数据库无法分别撤销的假 INSERT／DELETE 开关；具体UPDATE等语句还要核实对应版本和表模型 |

统一主体、角色、资源、流程及常用业务动作；默认“写入数据”作为明确的组合授权：PG／MySQL展开INSERT＋UPDATE＋DELETE，Doris展开Load_priv。PG／MySQL支持展开细分，Doris不提供虚假的独立INSERT／DELETE。策略同时记录业务选择和管理员确认的原生动作清单／映射版本，执行和审计以原生清单为准，保存跨源策略时逐源预览，不能静默扩权。

原型改为显式MySQL／PostgreSQL／Doris切换，以及Schema／数据库、表、视图三个资源页签；不再把Schema授权藏在一个只读说明弹窗里。常用矩阵使用同一套中文名称，不适用项给出原因，PG所有权项只展示来源，不伪造可单独GRANT。

统一动作映射与资源范围详见下表；这是平台方案选择，不是声称所有数据库原生权限相同。

| 统一业务术语 | Doris | PostgreSQL | MySQL |
| --- | --- | --- | --- |
| 查询数据 | Select_priv，按库／表／视图作用域 | 表／视图SELECT；Schema本身无SELECT | SELECT，按库／表／视图作用域 |
| 写入数据 | Load_priv组合权限，包括导入、插入、删除等；语句仍受表模型、版本及其他前提限制 | INSERT＋UPDATE＋DELETE，可展开细分；不包含TRUNCATE | INSERT＋UPDATE＋DELETE，可展开细分；不包含DROP |
| 创建对象 | 主要在数据库授Create_priv，创建表／视图仍需实际前提 | Schema CREATE，包含创建函数等对象，并可能产生创建者所有权 | 指定库CREATE／CREATE VIEW，分别记录实际动作 |
| 修改结构 | Alter_priv；修改视图定义还需查询定义中引用对象的权限 | 所有权控制，不能GRANT ALTER ON TABLE | 表ALTER；修改视图涉及CREATE VIEW和DROP等，不能伪装为ALTER VIEW权限项 |
| 删除对象 | Drop_priv，库级可能允许删库及其对象，不是DELETE行 | 所有权控制，不能GRANT DROP ON TABLE | DROP；库级可能允许删库；TRUNCATE也依赖DROP |
| 查看视图定义 | Show_view_priv | 无独立SHOW VIEW授权，按原生元数据可见性及平台目录权限 | SHOW VIEW |
| 访问Schema | 没有与PG Schema USAGE等价的库表权限项 | USAGE，仍需表／视图动作 | 不用USAGE伪装成可访问库表 |
| 授权管理（高级） | Grant_priv，包含授权／撤权及相关账号角色管理，角色分配要求更高层级能力 | 对象WITH GRANT OPTION、角色ADMIN等机制分别处理 | 对象GRANT OPTION、角色管理能力分别处理 |

`GRANT`是执行授权的SQL语句，`Grant_priv`是Doris授予执行者的权限，两者不能混为一个普通CRUD动作。原型提供Load／Grant说明，普通业务角色和智能体不开放“授权管理”勾选；管理连接单独核实所需原生能力。第一版不把不同引擎的Grant机制强行归成一个可转授权布尔值。

视图单列资源：Doris逻辑视图不提供DML写开关；PG／MySQL只有确认可更新的视图才提供写入，本示例聚合视图只读。授予视图SELECT不等于授予所有底表SELECT，实际definer／invoker、依赖及EXPLAIN限制仍分别验证。物化视图不与普通逻辑视图混用，按引擎独立能力处理。

库级授权来源需要回显：Doris／MySQL某表已经经db.*获得权限时，单表不能用撤销自己的直接授权抵消上层允许；页面显示“库级来源”，去上层修改。当前对象批量授权则生成逐对象条目，可逐项撤销。PG Schema访问与表权限分开，不能因为勾选USAGE就让整个Schema下表变为可读。

前提权限不悄悄扩大：按连接／Schema／序列的实际依赖生成预览，一起由管理员确认并记账；不因为 UPDATE 常需SELECT就自动给全表读权限。触发器、默认表达式、外键及有definer语义的依赖按原生执行，遇到会突破声明范围的路径需明确审查，不能以授予单个动作承诺所有SQL都成功或没有副作用。

PostgreSQL 的现有所有权只作为独立来源回读，页面注明“所有者固有能力”；不把它伪装成可勾选、可单独撤销的 ALTER/DROP。需要移交所有权时由 DBA 处理，不用该中心隐式完成。数据库授权支持与门户操作支持分别返回：当前 PostgreSQL structureEdit=false仍保留；MySQL／Doris已实现的结构操作按实际原生权限放行，不统一关闭。

绑定数据库账号的直接权限、可激活原生角色及继承权限都需核实，Doris User Identity与版本差异分别处理。本版直接管理角色所绑定账号的授权，不额外创建一套平台托管原生角色或逐用户原生成员关系；业务账号不自动获得GRANT／ADMIN能力，管理连接的下发能力单独检查。能力目录注册 `nativePermissionInspect`、`nativePermissionManage` 并给出逐动作列表；未在真实版本验证的能力不声称已交付，本机无Doris不以MySQL测试替代。

智能体仅可拥有已验证的查询／必要连接动作；人的DML／DDL授权不传播为Agent写入能力。数据库写操作验收使用专用可清理资源，权限回读验证不通过对业务表试写来探测。

### 9.7 即时保存与失败处理

**角色成员变更只写平台库。角色资源权限变更才即时写数据源与平台记录。** 不再给每位用户下发数据库角色成员，不需要权限同步按钮。

授权流程：读取角色revision和账号实际权限 → 生成原生变更预览／影响成员数 → 确认 → 保存本次批准意图／幂等键 → 请求内GRANT／REVOKE → 回读验证 → 平台短事务提交授权、revision与操作结果。按绑定的真实数据库账号执行，不创建额外无登录原生角色。管理请求只处理一个数据源角色，源内原生授权写入串行；不增加跨源原子事务或全域待同步门禁。

远端GRANT与平台事务不原子。两边都成功才显示已生效；原生拒绝且确认未变更时失败；已执行部分步骤、回读不明或平台最后提交失败时返回PARTIAL／UNKNOWN和operationId，保留实际步骤，不能声称已回滚。前端超时不自动重发。手动核对只读实际状态；重新执行须按最新状态重新预览，不能自动把已撤宽权限授回。状态未知只暂停该角色的新执行，不影响别的角色／数据源；共享账号意味着这个角色所有成员都受影响，应明确显示人数。

平台内移除成员／停用用户后新请求不再选到角色账号。已运行SQL和已返回结果不保证收回。角色账号改权后，新连接服从原生权限；现有连接缓存与撤权生效行为逐引擎验收。删除自定义角色须先移除／明确重分配成员、处理平台托管的原生授权，再删除平台定义，不删除数据库账号；保留记录。内置角色不允许删除。

### 9.8 查询、数据开发与智能问数

| 入口 | 规则 |
| --- | --- |
| 采集／管理员资产盘点 | 使用源采集连接；采集成功不会自动授权给角色 |
| SQL工作台树、表详情、预览、自由SQL | 从可信登录用户和sourceId解析唯一角色，用角色账号读取目录并执行；元数据目录与执行采用一致角色范围 |
| 写入／结构编辑 | 角色实际原生权限＋门户已有操作能力共同决定；不因新模型统一关闭写功能，PG尚未实现的在线结构编辑也不因有权限就开启 |
| 智能问数元数据 | 用户所属角色与Agent只读角色可查询资源取交集，结果才提供给模型 |
| 智能问数执行 | 保留用户角色账号EXPLAIN（不含ANALYZE）→Agent只读角色账号执行；两边均需当前有效成员及绑定 |
| 定时问数 | 每次重新校验主题所有者、用户状态、用户角色、Agent角色与版本，不复用旧成员快照 |
| Dolphin／同步任务 | 继续使用外部执行系统配置，不自动继承门户角色 |

缺成员、角色未配置、账号不可用、权限结果不明均明确拒绝，不回退到源连接／root／管理账号。前端不能传roleId冒充更高角色，后端从登录用户查成员。Agent只读守卫与现有行数／超时限制保留；JDBC readOnly不等于数据库权限本身。MySQL8.0.45仅视图SELECT／SHOW VIEW的EXPLAIN仍需底表权限这一既有实测限制保留，不扩权或加第二查询回退；PG同场景依原生规则。

<a id="trusted-identity"></a>

### 9.9 可信用户身份是交付前提

> 2026-11-01用户调整：本节可信身份方案延期至TODO；不阻塞本轮沿用现有双身份协议的本机功能验证。生产多用户可信隔离仍未交付。当前验证范围见[9.15](#identity-local-smoke)。下方签名方案为后续设计输入，不是已实现能力。

当前 widget 把浏览器提供的 `X-DataAgent-User-Id` 当用户身份，存在冒充空间，已在 TODO 记录。本设计不能沿用该路径并声称实现了用户隔离；本节作为权限中心的必需接入契约，不借机重写 DataAgent 登录系统。

选定方案为**门户签发短期身份断言，独立 DataAgent 验签**：

1. 门户已认证用户调用 `POST /v1/dataagent/identity-assertion`，后端从 `UserContext` 取稳定用户 ID，校验用户启用和目标 website 配置，不接受浏览器指定 subject。复用 `odw-auth` 的 JJWT 依赖，使用独立 RS256 密钥签发 5 分钟令牌，不向 DataAgent 分享门户现有 HS256 会话密钥。
2. 断言包含 issuer、audience（固定 website）、sub（平台稳定用户 ID）、显示用外部身份键、iat、exp、jti、kid；不含数据库账号密码或业务表权限。DataAgent 仅配置该门户公钥与 issuer/audience，算法固定 RS256；私钥仅在 Java 后端部署。浏览器令牌只用于绑定用户，不是数据库凭据。
3. widget 将断言交给 DataAgent，DataAgent 验签后建立自己的已验证门户身份会话，忽略浏览器填写的用户 ID。过期需通过门户认证重新签发；会话及工具通道固定 issuer+sub，不能让模型或浏览器在同会话内改用户。
4. 主题归属持久化为 issuer+sub，读取、继续、删除和定时执行均检查；已有主题的旧字符串身份不能按同名用户名自动升级成已验证主体，应拒绝访问直至管理员核实归属。DataAgent 独立登录仍走它原有认证，但不能冒用门户 issuer；两个来源需显式绑定才共用门户数据身份。
5. DataAgent→门户继续使用服务器间服务令牌和固定主体／智能体上下文；门户将稳定 subject 解析为已登录用户，再按sourceId解析其数据源角色，校验用户启用、角色成员及权限版本。服务令牌只证明服务，不单独证明某个人。DataAgent 是受信的身份转换边界，不能开放一个仅凭该令牌就接受任意浏览器用户头的入口。

停用用户后门户数据 API 实时拒绝，不能因 5 分钟断言尚未过期继续查数。定时任务不靠过期断言作为长期凭据，使用受信服务身份携带已验证主题所有者，门户每次重新判定。部署需 TLS、允许来源、密钥配对和 kid 轮换；日志及模型环境不记录断言或服务令牌。Nginx 不必承担第二套用户映射，沿用现有流式转发超时，不引入 auth_request 与 JWT 两条并行后备路径。

### 9.10 管理界面、API与审计

入口改为**管理中心 → 数据源 → 权限配置**，保持当前数据源上下文。左侧角色列表默认数据管理员／只读／读写／自定义；右侧三个页签：**数据权限、角色成员、数据库账号**。数据权限沿用统一动作矩阵与Schema／表／视图切换，支持原生细分、预览和即时结果。账号页只配置该角色唯一账号；成员页只选择用户或只读Agent成员，不提供成员单独授权。授权记录在同一源下可查看，不新增用户组／执行身份／独立策略中心。

| API（设计路径） | 行为 |
| --- | --- |
| `GET/POST /v1/doris-clusters/{sourceId}/roles` | 管理员查看内置／自定义角色、新建自定义角色 |
| `PATCH/DELETE /roles/{roleId}` | source归属和expectedRevision校验；内置角色不删 |
| `PUT /roles/{roleId}/members` | 平台事务分配／替换成员；同源同主体只有一个role，无原生授权双写 |
| `PUT /roles/{roleId}/account` | 测试真实账号并核实实际权限，保存密文；不泄漏密码、不保留个人绑定入口 |
| `GET /roles/{roleId}/grants`、`POST /roles/{roleId}/grants/preview`、`POST /roles/{roleId}/grants/apply` | 资源范围／动作、预览、带版本和幂等键即时执行；不保存等待后台同步的策略 |
| `POST /roles/{roleId}/inspect` | 手动核对该角色账号权限，返回来源与时间 |
| `GET /permission-operations`、`GET /permission-operations/{id}` | 读取操作结果／部分成功步骤，不作后台任务轮询 |
| `GET /v1/doris-clusters/{sourceId}/my-role` | 本人当前角色及无秘密能力摘要，role由认证上下文解析 |

管理写入仍要求平台admin。普通用户不能读取其他角色账号配置／成员列表。操作日志包含真实平台操作者、目标源／roleId、资源与原生变化，查询审计同时保存用户与角色，不能把共享账号当作数据库已经提供逐人审计。能力不支持返回UNSUPPORTED_ACTION；过期预览409；无角色403；双写部分失败明确错误与操作ID，不包装成功或排队。

### 9.11 实施与验收范围

已授权的实现按上述模型执行；不实施已移除的组、策略中心、专属身份、跨源角色及逐用户原生成员关系。Java／Vue／Flyway完成后需同时验证SQL工作台、目录、表预览和Agent角色解析，不能只把账号映射弹窗换标题。

真实验收包含：新源内置三角色且无绑定时拒绝；两用户同角色用同账号、无用户单独授权；同源换角色／移除成员后下一请求生效；自定义表范围跨表查询由数据库拒绝；只读／读写／数据管理员语义；管理连接不成为查询回退；原生拒绝／部分成功／平台保存失败；PG Schema／序列／所有权、Doris Load／Grant与版本差异；两类问数角色交集和可信用户身份。

明确延期：多角色合并、用户组、用户单独授权、个人数据库账号、数据库直连的逐人撤权与全量审计、字段／行级权限和脱敏、ABAC、审批、自动创建登录账号／移交所有权、PG未来默认权限、Dolphin身份统一、定时漂移同步。限制统一记录[TODO](../TODO.md#unified-table-access)。

参考依据：[DataWorks数据访问控制](https://help.aliyun.com/zh/dataworks/user-guide/data-access-control/)、[华为DataArts角色](https://support.huaweicloud.com/usermanual-dataartsstudio/dataartsstudio_01_1157.html)、[Metabase数据权限](https://www.metabase.com/learn/metabase-basics/administration/permissions/data-permissions)、[Doris权限](https://doris.apache.org/docs/3.x/admin-manual/auth/authentication-and-authorization/)、[PG权限与所有权](https://www.postgresql.org/docs/16/ddl-priv.html)、[PG GRANT](https://www.postgresql.org/docs/16/sql-grant.html)。本方案是用户选定的轻量角色账号模型，不声称厂商产品均采用同样共享账号执行。

<a id="prototype-member-role-model"></a>

### 9.12 本轮确认的原型交互（2026-10-30）

用户已明确按“角色管理、成员管理、成员数据库账号绑定、内置权限模板”修改独立原型。本节是当前UI依据；9.1～9.11中已标记失效的同源单角色／角色绑定登录账号规则不能复活。本轮仅修改HTML原型及说明／图集，生产账号生命周期、迁移与原生Adapter尚未实施。

- 角色管理保留资源与动作矩阵、Schema／表／视图、权限预览／即时结果；角色显示原生ROLE名称，不持有登录账号或密码。
- 成员管理取消用户组，用户可以勾选多个角色。每个成员在每个数据源只绑定一个数据库登录账号，账号页在成员详情内，不新增执行身份中心。
- 成员详情分“角色分配／数据库账号”；多角色来源及合并后的资源范围可查看，演示张明在同一Doris账号下同时拥有销售、客户、财务三个库的角色。移除一角色后只移除该来源，不切换登录账号。
- 新建角色先选只读／读写／数据管理员模板，再选数据源、Schema／数据库及资源层级，生成明确的动作草稿；预览确认前不生效。不创建三个固定共享账号。PG不可独立GRANT的所有权动作照常显示限制；Agent不能选择含写入／DDL的角色。
- 原型保存仅改内存示例，刷新重置；成员角色变更演示原生角色分配，账号绑定演示连接验证，无后端调用、真实账号或密码持久化。

<a id="prototype-tob-distill"></a>

### 9.13 面向日常管理的原型精简（2026-10-30）

模式：Operate，沿用现有企业控制台视觉。用户明确首页优先“成员分配：先看到谁拥有哪些角色”。本轮只优化原型布局／交互，不改变9.12确认的授权模型，不修改生产程序。

- 成员为默认首页，数据源选择提升到统一页头，在成员／角色／记录之间保持上下文；去掉角色内重复的引擎／数据源切换和权限页中的数据源配置Tab，源配置仍从侧栏数据源进入。
- 成员列表用紧凑表格展示成员、角色、账号、状态；主操作添加成员，行内分配角色／查看权限，账号直接点击绑定或修改。移除教学说明块、重复副标题与每行相同的账号说明。
- 分配角色使用小表单一次保存，不再混用成员详情Tab、下一步和多个保存按钮；账号单独小表单，后端概念不进入日常文案。添加成员可先分配角色、稍后绑定账号。
- 角色编辑保留资源切换、有效范围和必要前提；默认隐藏原生权限名、SQL、Load／Grant解释，不适用整列不显示；细分与映射说明仍可按需打开。权限变更时才出现固定保存区，确认页明示具体范围／副作用。
- 新建角色使用紧凑模板选择、名称与资源范围；模板草稿仍需确认。有效权限使用业务动作及来源表格，原生细节展开查看。
- 验证成员分配／账号绑定／模板／权限保存及失败演示；检查1440宽桌面与窄容器，不以减小字号制造密度，不丢失取消／错误／空状态／键盘操作。

本轮原型已按以上交互完成并验证，数据角色与数据库账号模型没有变化。角色页取消重复成员页签，唯一“管理成员”操作跳转筛选后的成员列表；原生细节保留按需入口。

<a id="platform-user-management-prototype"></a>

### 9.14 用户管理与数据授权入口补齐（2026-10-30）

用户澄清将“添加成员”理解为新建用户，现有系统没有可管理平台用户的入口。只读代码确认：`SysUserService`、`AuthController`有登录／改密／当前用户，账号绑定接口可列用户，但无用户管理页面或创建用户API。前一轮仅删除账号展开区／改成员选择不能解决根因，不实施该批量成员方案。

本轮仅补独立原型：管理中心增加“用户管理”，管理员可新建／编辑平台用户；新用户未分配数据角色时不出现在数据源已授权列表。数据权限页“添加成员”更名“授权用户”，默认选择已有用户，无默认王宁；也可切换“新建用户”在原处填写平台用户名、姓名、初始登录密码并分配角色，一次提交后创建用户并授予当前源角色。平台登录密码不作为数据库密码；数据源查询账号仍由列表独立绑定。

平台用户与数据权限保持边界：创建用户不自动获得数据源角色；给已有用户授权不修改其登录资料。原型只保存示例资料／角色，不存任何密码或访问生产用户API。生产用户管理API、停用／会话失效、初始化口令与安全审计仍需独立落实，不能用原型冒充交付。

<a id="identity-local-smoke"></a>

### 9.15 本轮先验证现有身份链路（2026-11-01）

用户明确将签名身份方案记录为TODO，优先跑通目前已实现功能，不因本次调研引入整套用户委托系统。

- 当前实际协议：DataAgent从会话取`auth_user_id`或widget的`external_user_id`，从智能体快照取`agent_id`，向HTTP MCP注入`X-Agent-User-Id`和`X-Agent-Id`。MCP前门凭证与MCP→Java的服务凭证是两项服务配置，尚不是每个智能体单独签发的Token；不能把新提议的`Authorization`配置当作已经生效。
- Java要求两种身份均存在；按`local:<用户名>`等现有格式解析启用的平台用户，并要求用户与智能体在目标源都有数据库账号映射。给模型的目录取双方交集；人账号EXPLAIN检查、智能体只读账号实际执行。不存在仅智能体直接查询分支。
- 本轮选择复用现有双身份链路。优先使用当前登录用户；定向本机HTTP冒烟可显式携带一个已登记测试用户与实际智能体ID。固定身份只属于测试输入，不写入生产默认配置、浏览器公共常量或缺身份的服务端回退，不把所有用户的数据查询统一改为测试账号。
- 待办参考Aily自定义MCP的签名用户上下文，以及飞书官方OpenAPI MCP的用户OAuth授权模式；它们是两种不同机制，不把身份断言当作用户资源访问Token。后续需要同时解决门户用户来源可信、运行时注入、MCP验签与智能体绑定、Token轮换／撤销、独立登录绑定和会话归属。来源与边界见[TODO](../TODO.md#门户内嵌问数的用户身份)。
- 验证当前服务健康、登录、现有账号映射、用户目录／查询、MCP元数据／SQL和真实模型对话。分别记录当前结果与2026-10-27／29历史结果；原型中的角色管理／用户创建尚未实施，不纳入“现有功能已跑通”的宣称。已有身份可冒充缺口不因冒烟通过而关闭。


<a id="implementation-contract"></a>

## 10. 用户管理与多角色权限实施契约（2026-11-01）

用户要求先完成生产功能再全链路测试；前端委托agy的Gemini Argon，后端和集成验证由主任务完成。本节覆盖第9节失效的单角色／共享角色账号实现方案；9.12～9.14的已确认交互继续采用。Java8／Spring Boot／MySQL平台库和Vue3／Element Plus不变，独立DataAgent协议不改，签名身份继续TODO。

### 10.1 用户与认证

管理中心增加用户管理：列表、搜索、新建、资料／平台角色编辑、启停、重置口令、删除。本地用户名不可修改，第三方账号不设置本地口令；初始／重置密码至少8位、UTF-8最多72字节，bcrypt存储，不返回密码哈希。平台admin/user仅决定管理权限，与数据源角色分开。禁止停用／删除／降级自己，禁止移除最后一个启用管理员。账号停用、删除、角色变更下一请求生效；重置口令／主动改密递增sessionVersion，使旧会话失效。管理操作记录操作者和对象，不记录密码。

`GET /v1/users?keyword=&enabled=`返回用户数组；`POST /v1/users`接收username/nickname/email/password/role/enabled；`PATCH /v1/users/{id}`接收nickname/email/role/enabled；`POST /v1/users/{id}/reset-password`接收password；`DELETE /v1/users/{id}`软删除。全部管理员权限。响应沿用`{code,data,message}`，用户只读字段含id/username/nickname/email/role/enabled/authSource/lastLoginAt/createdAt。

### 10.2 多角色、原生权限与账号

平台角色映射数据库无登录ROLE，成员(USER或AGENT)在每个数据源绑定一个登录账号，账号持有多个ROLE，以同一连接获得并集。只读／读写／数据管理员为新建模板，不自动创建三套共享登录账号。复用datasource_subject_account作为唯一连接凭据来源，不建立执行身份中心。角色增删资源即时执行GRANT／REVOKE并核验；成员分配角色同样即时下发原生成员关系，不能只写平台。

资源SCHEMA（MySQL／Doris的库，PG的schema）／TABLE／VIEW；统一动作QUERY、WRITE、CREATE、ALTER、DROP、SHOW_VIEW，PG／MySQL可细分INSERT／UPDATE／DELETE。Doris WRITE是Load_priv整体，不伪装独立DML；不对业务角色授Grant。PG ALTER／DROP来自所有权，接口明确不支持独立授权；schema QUERY／WRITE只展开当前对象，预览明示未来表不自动授权，USAGE与序列依赖按实际对象补齐。所有对象及账号标识符由方言引用，资源存在性、类型、source归属均验证。MySQL为授权角色设置默认激活，保留已有外部默认角色；PG16成员INHERIT TRUE；Doris按原生多角色生效。

保留既有账号映射数据和查询唯一主路径：原生账号的已有直接权限／外部角色仍由数据库执行，不擅自回收DBA授权，也不将平台未勾选描述成原生无权。界面提供账号实际权限查看并提示外部权限可能扩大范围；删除平台成员必须撤销托管原生角色并移除账号映射，以阻止平台继续访问。新成员可先分配角色再绑定账号，未绑定时不查询。智能体仅允许查询／查看视图定义角色，绑定时核验数据库账号没有写入／管理权限。

授权管理账号在当前源权限页高级配置一次，不作业务连接回退。无单独配置时使用源原有管理／采集连接，原生授权不足明确失败；不自动改源账号或用root兜底。新增管理凭据AES-GCM加密，密钥由ODW_CREDENTIAL_KEY部署环境提供；既有账号存储沿用当前模型，本次不混入全部凭据迁移。

### 10.3 前后端API

统一前缀`/v1/doris-clusters/{sourceId}/permissions`，以下管理API均只允许平台admin。

| 方法／相对路径 | 请求及响应data |
| --- | --- |
| GET 空路径 | `{sourceId,engine,roles,members,management,capabilities}`；role含id/name/nativeName/template/revision/status/grants，member含subjectType/subjectId/name/roleIds/account/status/revision；account仅username/configured，management仅username/configured；capabilities含resourceTypes、actions（key/label/resourceTypes/nativeActions/note） |
| POST /roles | `{name,template:READONLY/READWRITE/ADMIN/CUSTOM}`，创建草稿角色，不带登录账号。返回role |
| PATCH /roles/{id} | `{name}`，改显示名 |
| DELETE /roles/{id} | 无成员才允许；即时删托管原生角色并核验，不删登录账号 |
| POST /roles/{id}/preview | `{expectedRevision,grants:[{resourceType:SCHEMA/TABLE/VIEW,database,tableName,actions:[]}]}`，返回`{previewId,sql:[],warnings:[],memberCount,expiresAt}`；预览不执行授权 |
| POST /roles/{id}/apply | `{previewId}`，请求内执行预览；返回`{operationId,state:SUCCESS/FAILED/PARTIAL/UNKNOWN,message,steps:[]}`；非SUCCESS不可显示保存成功 |
| PUT /members/{subjectType}/{subjectId} | `{roleIds:[],expectedRevision}`，分配多个角色，USER为sys_user.id，AGENT为agent_id；新成员revision为0。无账号时保存待配置；有账号时即时GRANT／REVOKE与核验 |
| POST /members/{subjectType}/{subjectId}/account | `{username,password,expectedRevision}`，测试真实账号，绑定并下发成员角色；空密码只允许原账号保持不变；返回member，密码不回传 |
| DELETE /members/{subjectType}/{subjectId} | 撤销托管角色并删除账号映射及平台成员，不删除平台用户／数据库账号 |
| GET /members/{subjectType}/{subjectId}/inspect | 返回`{username,nativeIdentity,grants:[],notice}`，查看实际原生授权；无密码 |
| PUT /management | `{username,password}`保存并测试管理连接；空密码只允许同账号保留；响应仅username/configured |
| GET /resources?database= | 管理连接盘点，返回`{databases:[],objects:[{database,tableName,resourceType}]}`；用于授权选择，与用户个人目录分开 |
| GET /operations | 最近操作数组id/roleId/subjectType/subjectId/operatorId/state/message/createdAt/steps；无秘密 |

预览保存平台操作记录并绑定source/role/revision、操作者、10分钟过期时间；apply只能由原操作者提交。平台MySQL连接级命名锁按source串行管理写入；远端步骤与平台提交不原子，先记录意图、逐步执行并回读，最后短事务保存平台状态。任何部分失败保存步骤与PARTIAL／UNKNOWN，不自动重试或宣称回滚；不设置全源待同步门禁。失败／不明的同一对象需手动核对再重新操作，其他角色不阻塞。不得用UI成功响应代替原生核验。

### 10.4 交互及验证

数据源行“账号映射”替换“权限配置”，打开源内管理界面，默认成员，角色／授权记录为同级Tab。成员表显示谁拥有哪些角色、绑定账号和状态，操作授权用户／分配角色／绑定账号／查看实际权限／移除；授权用户支持已有用户或新建用户，创建平台用户与授权两步失败必须诚实保留创建结果。角色新建先选模板，资源授权为库／表／视图与统一动作勾选，预览后确认实际SQL和范围才生效；失败显示操作结果。复杂原生名称和SQL按需展开，不另设执行身份中心。

先完成上述前后端功能及定向测试，再启动更新后的服务跑管理员创建普通用户→配置管理连接→新建多schema角色→分配／绑定账号→目录／跨schema SQL→撤权／停用，以及智能体只读交集与真实模型对话。MySQL8、PG16使用专用测试库和账号验证；Doris必须实际环境验证，缺环境记录未验收，不能用MySQL替代。


### 10.5 实测MySQL库名通配符限制（2026-11-01）

本机MySQL8.0.45、partial_revokes=OFF复现[MySQL官方Bug110582](https://bugs.mysql.com/bug.php?id=110582)：库名含下划线时，给原生ROLE授予转义的库级权限，SHOW GRANTS／默认角色均正确，但USE／带数据库的连接被拒；不转义会放宽到相似库名，真实反例已验证。额外给当前表授权仍不能保证未来表可访问，不能伪装为等价修复；不会自动启用全局partial_revokes或扩大授权。

因此该真实配置下，库名含`_`／`%`的SCHEMA授权在预览阶段返回UNSUPPORTED_ACTION及原因，表／视图级原生角色授权照常支持；无通配字符的库级授权保持当前及未来对象语义。partial_revokes=ON的库名按原生字面语义处理，但本机不擅自更改全局设置。验收必须同时覆盖受影响库的库级拒绝、表级精确授权及相似库拒绝，不能只换测试库名掩盖限制。版本修复及ON模式实测列入TODO。


### 10.6 成员移除与账号失效

成员绑定成功时保存已验证的原生user／host身份（V56新增native_identity，不含密码）。撤销成员使用管理连接针对该身份REVOKE，不要求目标成员旧密码仍能登录；数据库账号已被DBA删除时仍移除平台映射。换绑账号撤销原身份的托管角色，包括同名但host变化的情况；不创建、删除或改密原生登录账号。既有无托管角色的旧映射可直接移除，不因查询密码过期阻止撤销平台访问。PG删除角色先撤销托管对象ACL，再DROP ROLE，绝不使用DROP OWNED／CASCADE删除业务对象；所有权或外部依赖阻止删除时明确报告部分结果。

### 10.7 跨数据源并发保存

2026-11-02真实MySQL与PG两个源并发授权发现：原生步骤成功，平台短事务在空角色授权范围DELETE后INSERT发生InnoDB间隙锁死锁，已正确报告PARTIAL。权限服务内的平台短事务改用READ_COMMITTED，依靠已有每源GET_LOCK与版本校验串行同源写入；不修改平台数据库全局隔离级别，不重放原生SQL，不新增后台重试。增加不同源并发apply、平台记录与远端回读一致的真实回归；已失败意图仍需新预览明确恢复。

<a id="service-accounts"></a>

## 11. 平台普通账号与服务账号（2026-11-02）

用户确认两类账号：普通用户可登录，服务账号用Token访问；平台权限管理独立于DataAgent，不读取外部助手目录。此节覆盖第10节AGENT字符串主体与MCP共享前门Token设计，其他原生角色／成员／数据源账号关系保持。依据[Grafana服务账号](https://grafana.com/docs/grafana/latest/administration/service-accounts/)的身份与多Token生命周期分离，参考[Databricks服务主体](https://docs.databricks.com/aws/en/admin/users-groups/service-principals)；本期不实现OAuth服务器或可信人委托。

### 11.1 模型与迁移

sys_user增加account_type=USER/SERVICE（既有用户USER）、description（用途）。服务账号自动生成svc_标识，填写显示名与用途，不设置password_hash、email或平台admin角色；auth_source=service，平台role=user。类型创建后不可变，不能转换人账号／服务账号。服务账号不能密码／OAuth／网页会话登录，只允许服务Token通过MCP／agent只读API访问。停用／软删除使所有Token下一请求失效；重新启用不复活已撤销Token。普通用户会话与管理员保护保持。

service_account_token保存账号ID、名称、SHA256摘要、展示前缀、到期／撤销／最后使用时间与创建者；256位随机Token格式odw_sa_...，明文只在生成响应返回一次，不存明文或可逆密文。默认有效期90天，可选1～365天；支持多Token和独立撤销，方便替换期间重叠使用。账号无Token／未授权／未绑数据库账号均不能访问数据。Token不赋予平台管理权限。Token所有生命周期时间按UTC写入和比较，API以带Z的ISO8601格式返回，前端按浏览器本地时区展示，避免数据库／JDBC会话时区造成显示偏移。

源成员及datasource_subject_account类型改为USER/SERVICE，subject_id均为sys_user.id。服务账号使用现有原生只读角色／受限登录账号，一源一个登录账号持多个ROLE；本期服务账号沿用智能问数只读约束。源内授权从平台账号选择，无手填外部agent_id。管理记录仍保留历史AGENT字样，不重写审计。

V57为已有AGENT绑定按旧ID去重创建SERVICE账号（确定性svc_legacy_哈希标识，名称保留旧ID便于识别），再修改两张当前映射表的类型与主体ID。保留数据库账号、ROLE和原生授权；不自动创建Token，不把旧共享前门Token迁成个人凭据。迁移检测保留标识冲突即失败，不认领同名普通用户。无AGENT映射则不建立无用服务账号。升级必须更新调用方凭据，一次切换，不提供旧ID／旧共享前门Token查询回退。

### 11.2 接口与UI

保留/v1/users API，响应增加accountType/description；list支持accountType筛选；create允许USER（默认）／SERVICE，后者无需username/password而由后台生成标识；update拒绝变更类型，SERVICE不能设置密码、email或admin。重置密码对SERVICE拒绝。

- GET /v1/users/{id}/tokens：仅服务账号，返回id/name/prefix/expiresAt/revokedAt/lastUsedAt/createdAt，不返回摘要或明文。
- POST /v1/users/{id}/tokens：{name,expiresInDays}，仅启用的SERVICE，返回Token元数据和token一次；Cache-Control:no-store。全部管理员权限。
- DELETE /v1/users/{id}/tokens/{tokenId}：撤销、校验归属、幂等，管理员权限；操作审计不含凭据。

导航及页面名改账号管理，列表有类型筛选和类型列；新建先选类型，普通用户沿用登录字段，SERVICE仅名称／用途／状态。创建SERVICE成功后可进入管理Token。服务账号没有重置密码和管理员选项，详情可管理Token（列表、新建、一次展示／复制、过期和撤销反馈）。源内“授权智能体”改“授权服务账号”，选择启用平台SERVICE，复用多角色／绑定数据库账号，不请求nl2sqlApi.listAgents；普通授权只选择USER。

### 11.3 Token执行链路

外部应用→MCP Authorization: Bearer <服务Token>→Java agent API。MCP不查询DataAgent；前门要求格式完整的Bearer凭据，不再比较共享前门Token；每次MCP HTTP请求经Java只读身份核验端点验证有效Token，避免仅初始化鉴权或返回空搜索掩盖失效。MCP转发原Bearer和可选X-Agent-User-Id；忽略外部X-Agent-Id／X-Agent-Operator。MCP→Java仍用独立内部服务Token与私网门禁识别受信转发服务；它只认证传输来源，不能替代服务账号凭据。

agent-api模块定义小型ServiceAccountAuthenticator SPI，主backend实现摘要查询和启停／过期／撤销核验，不使独立模块依赖业务持久层。拦截器由Bearer得到平台服务账号ID并建立AgentIdentityContext，不读取外部agent_id。服务Token不能用于普通网页API；本期仅开放agent元数据GET与只读SQL POST，其他开发写API明确403，不能仅靠客户端工具标签防写。该边界在部署说明中说明，开发写工具的精细服务授权及可信人委托继续TODO，不用既有可伪造operator头绕过。

无用户头是明确的服务账号自行查询，只使用其受限账号和可见目录，不补假用户或管理账号；查询审计user_id为空，agent_id记录SERVICE平台ID。提供X-Agent-User-Id时按既有身份解析启用的普通用户，要求双方映射并取交集，人的EXPLAIN与服务账号执行保持。未知／停用用户不能退回服务模式。签名人委托未实施，不能把未签名用户头宣称可信代人认证。服务账号停用在拦截器及数据源凭据解析时都校验。

### 11.4 验收与发布

覆盖USER登录回归，SERVICE不能密码／会话登录／管理员化；类型不变；生成多Token、摘要存储与一次展示、过期／撤销／停用／删除、Token归属与管理403；旧AGENT迁移保留原生绑定；源授权无DataAgent请求且拒绝伪造类型／主体。Python覆盖Bearer转发、不信agent_id、每请求核验、失效401及读写边界。真实本机服务账号Token→MCP→原生受限库，服务独立查询与可选人交集、跨Token／跨账号拒绝；浏览器核对创建类型与Token管理。更新调用方配置并验证真实模型，不改独立DataAgent源码。升级备份平台库、迁移、匹配Java/MCP/Vue整套发布；回滚需恢复平台备份和旧调用凭据，旧版本不可读取新模型。只读服务边界、缺Doris环境和可信委托剩余项写TODO。

### 11.5 浏览器验收补正：目录连接不调用管理测试

2026-11-02普通用户实测：Data Studio activateDatasource复用数据源管理POST /{id}/test，管理员限制使普通用户无法展开目录。该管理接口使用采集连接，不能通过放宽权限解决。目录激活直接使用已有GET /{id}/databases，由当前普通用户绑定的原生账号连接并枚举可见库；成功结果进入同一schema缓存，首次加载不重复请求，强制刷新重新获取。绑定缺失／原生拒绝明确失败，不使用管理账号。不新增API或双路径回退。浏览器缓存跨账号恢复SQL标签的既有问题独立记录TODO。


## 12. 权限中心前端组件拆分（2026-11-02）

用户授权实施此前记录的3086行组件维护项。本轮仅调整Vue组件与前端状态组织；Java、数据库、API、DataAgent及部署不变，成员优先布局、文案、业务动作和原生权限语义保持。

### 12.1 边界与状态归属

- DatasourcePermissionCenter保留drawer对外props／events、数据源上下文、概览加载与页签协调。概览、平台账号目录和选源只有一份状态，不新增Pinia store／事件总线／隐式inject。
- 权限矩阵模块拥有角色选择、库表盘点、动作能力、草稿／细分选择；角色操作模块拥有新建／改名、预览／执行。成员模块拥有筛选、授权／新建用户、角色分配、账号绑定与实际权限；管理连接凭据独立；授权记录拥有列表、加载和结果详情。
- 展示组件按成员列表、角色权限矩阵、授权成员、成员角色、成员账号绑定、原生权限查看、管理连接、角色编辑、预览、结果／记录拆分。每个视图只接收该功能的响应式view-model以及所需共享标签／上下文，不传入整个中心控制器。表单草稿由对应composable拥有，子视图编辑该明确的draft对象，API调用仅在composable中。
- 可跨功能复用的动作常量与标签函数集中在当前权限模块内，不抽成全站通用运行时。父容器只协调reload、查看角色成员及展示操作结果，避免功能模块互相导入。

### 12.2 交互与样式

外部modelValue、cluster、clusters及update:modelValue／source-change保持；数据源切换后回成员页。成员多角色、SERVICE只读限制、空密码保留／换账号必填、新建用户后授权失败不重复创建、非SUCCESS结果与操作ID等行为不变。盘点和记录保留请求序号隔离；关闭／切源清除敏感草稿和过期弹窗，晚到请求不写回其他源或重新弹出结果，不取消已经提交给服务器的操作，也不自动重试权限写入。

将原scoped样式按视图职责迁移；共用表单／列表规则留模块内共享样式片段，由需要的视图以scoped方式加载。不能因子组件边界使父scoped样式失效，也不增加卡片、外框或改变层级。

### 12.3 验证与取舍

保留现有行为断言，测试改为访问对应功能视图／状态，不给父组件增加仅用于测试的旧方法代理。补组件真实输入／点击事件验证、切源／关闭时的资源、权限回读及预览迟到响应与密码清理。运行权限中心与数据源页面定向单测、前端构建，并在管理员浏览器核对成员、角色、绑定、管理连接和记录的显示。纯前端重构不重复执行真实GRANT或模型问数；本轮不修原有浏览器SQL草稿隔离与无关单测性能问题。组件拆分完成后更新统一TODO和PR。

### 12.4 实施结果

主容器603行，12个功能视图、6个composable与1个常量模块已完成；最大视图为634行的角色矩阵，未把全部逻辑转移到单一控制器。请求上下文按数据源／打开周期失效，资源／记录／成员回读与角色预览保留各自序号。保持显式依赖与功能view-model，不新增全局状态。权限中心18项、相关41项及构建／静态检查通过，浏览器布局和表单通过；既有演示源实际权限回读返回后端连接／权限错误，单独记TODO。详见[拆分报告](../reports/2026-11-02-permission-center-component-extraction.md)。

## 13. 成员原生权限回读修复（2026-11-02）

### 13.1 已复现原因与范围

演示MySQL源1的USER／SERVICE成员连接均可成功，旧inspect随后改用采集连接账号读取mysql.role_edges，因该账号无系统表SELECT权限返回1142。管理连接未单独配置，采集账号权限不足以读取其他用户的原生授权。已有错误提示吞掉了具体失败步骤，不能将此项作为可上线验收。

本轮只修“实际权限”读取链路及诊断，涉及Java权限服务／Adapter、定向测试与真实源冒烟。前端接口形状、原生授权写入、数据库账号和已有业务数据不变；不通过提升采集账号权限或填入root来掩盖错误。

### 13.2 读取边界

管理员请求后，以当前成员已绑定账号建立连接并验证数据库实际身份，在同一连接读取其自己的原生授权，不再为查看授权打开采集／管理连接。MySQL使用SHOW GRANTS FOR CURRENT_USER，按information_schema.ENABLED_ROLES返回的名称及host构造USING以展开新连接实际激活的角色；保留角色host并转义，不假定外部角色均为@%。没有激活的角色不计为当前连接有效权限，不执行SET ROLE ALL。PG／Doris保持既有原生回读输出格式，改在已验证的成员连接读取；本轮不扩展其权限表达模型。

保留username、nativeIdentity、grants和notice响应字段，补充MySQL按新连接激活角色展示的说明。成员连接／身份验证／原生回读失败均明确返回失败步骤、SQLState及错误码；服务日志记录相同诊断，不记录密码、SQL授权结果或连接串。不吞错回空列表，不自动切换其他账号。

依据[MySQL SHOW GRANTS](https://dev.mysql.com/doc/refman/8.0/en/show-grants.html)、[ENABLED_ROLES](https://dev.mysql.com/doc/refman/8.0/en/information-schema-enabled-roles-table.html)及[Doris SHOW GRANTS](https://doris.apache.org/docs/dev/sql-manual/sql-statements/account-management/SHOW-GRANTS/)，普通账号可以查看自身授权；修改原生权限仍使用独立管理连接。

### 13.3 验证与交付

定向测试覆盖只开成员连接、USER／SERVICE只读标识、空／多激活角色、非默认host与引号转义、失败步骤与连接关闭。真实MySQL验证演示源两个成员回读、直接授权、激活角色展开、未激活角色不误报及无系统表读取权限仍可回读，并检查接口与直接SQL一致。管理员浏览器点击实际权限看到真实GRANT结果。测试使用独立临时账号／库／角色，清理后核对业务源权限未变。构建重启为测试脚本之外的本机操作；完成后更新TODO、报告及PR #491。

### 13.4 实施结果

已完成成员连接自查、MySQL激活角色host保留及分阶段诊断；Java定向21项与打包、真实MySQL／HTTP17项和浏览器两位既有成员回读通过，原源配置与授权未变，临时资源清理完成。ENABLED_ROLES只需返回激活顶层角色，嵌套权限由SHOW GRANTS USING展开并以真实查询验证。PG／Doris本轮不声称真实实例验收。见[报告](../reports/2026-11-02-native-permission-inspection-verification.md)。
