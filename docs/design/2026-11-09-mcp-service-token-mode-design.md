# MCP 服务令牌模式设计

日期：2026-11-09。
范围：后端认证与数据访问（`backend/app` 的 `HttpAuthenticationFilter`，`backend/core` 的 `CurrentPrincipal`、`PrincipalContextHolder`、`DatasourceSubjectAccessService`、`DataQueryService`）、查询历史迁移、部署与手册文档。前端与 MCP 工具契约不变。DataAgent 不改代码，只改文档。
关联：[主体数据库身份与统一表级权限中心设计](2026-10-26-dataagent-datasource-identity-design.md)。本设计取代其第 2–4 节、9.8 与 11.3 中「代人问数」的部分：MCP 访问不再按 `X-Agent-User-Id` 收窄。人在门户中的查询（Data Studio 等）不受影响。

## 1. 现状

MCP 请求（`/api/mcp/`）当前做两层检查：

1. **服务账号**：`HttpAuthenticationFilter` 校验 `Authorization: Bearer odw_sa_...`，无效返回 401；有效则主体为该服务账号。
2. **代用户**：请求带 `X-Agent-User-Id` 时，过滤器把它记为 `delegatedUserKey`，`DatasourceSubjectAccessService` 再按此用户收窄：
   - `resolveUser`：用户必须在平台存在、启用、不是服务账号；
   - `loadVisibleColumns`：可见表字段 = 服务账号 ∩ 用户；
   - `checkReadAccess`：用户的数据库账号执行 `EXPLAIN <sql>`，失败即拒绝；
   - `resolveClusterDatasource`：用户在该数据源必须有账号映射。

   查询本身始终用服务账号的数据库连接执行。不带 `X-Agent-User-Id` 时只按服务账号权限。

DataAgent 对已登录用户和 widget 用户总是发送 `X-Agent-User-Id`。

## 2. 问题

1. **上线阻塞**：问数用户必须先成为平台用户，并在每个数据源上分配角色，才能通过 MCP 问数。旧部署是共享令牌、无需按人配置；新版本上线前需要 用户数 × 数据源数 次授权，widget 宿主上报的外部用户在平台中根本不存在。
2. **用户头不可信**：`X-Agent-User-Id` 是调用方写的未签名请求头（见 [TODO「门户内嵌问数的用户身份」](../TODO.md)）。持有服务令牌的调用方可以声称任何用户，包括管理员。按它收窄权限，看似按人隔离，实际并不能证明是这个人。

## 3. 方案

### 3.1 令牌模式（本次实现）

MCP 只认令牌：

- **认证**：`Authorization: Bearer odw_sa_...`，与现在相同。
- **授权**：只看该服务账号在各数据源上的角色。可见库表、SQL 检查、执行都使用服务账号自己的数据库账号。
- **按智能体区分范围**：每个需要不同数据范围的智能体使用自己的服务账号与令牌。例如「销售问数」用只授予销售库只读的服务账号，「财务问数」用只授予财务库只读的服务账号。
- **谁能用哪个智能体**：由调用方负责。DataAgent 通过智能体「可见范围」与 widget 站点登记控制。

职责划分：DataAgent 决定**谁可以使用哪个智能体**；OpenDataWorks 决定**每个智能体的令牌能看哪些数据**。

### 3.2 用户与智能体标识只做审计

`X-Agent-User-Id` 与 `X-Agent-Id` 仍可由调用方发送，平台不再用于任何权限判断，只原样写入查询历史，方便追查「哪个智能体替谁查了什么」。字段名与界面文案标明为「调用方上报」，不代表经过认证的身份。

### 3.3 后续：OAuth 访问令牌模式（不在本次范围）

需要真正按人隔离时，调用方改为传入用户本人的 OAuth 访问令牌（`Authorization: Bearer <user access token>`），平台验证令牌后以该用户为主体，按用户角色授权。这要求平台与调用方接入统一登录。记入 `docs/TODO.md`，取代原「签名身份断言」的待办。

## 4. 变更

### 4.1 认证过滤器

`HttpAuthenticationFilter` 的服务分支不再读取 `X-Agent-User-Id` 构造委托主体：`CurrentPrincipal.service(id)` 只带服务账号身份。两个上报头读入请求范围的审计上下文（见 4.3）。

### 4.2 删除代用户收窄

- `CurrentPrincipal` 删除 `delegatedUserKey`；`PrincipalContextHolder` 的缓存键与相等判断去掉该字段。
- `DatasourceSubjectAccessService`：
  - `loadVisibleColumns` 只用当前主体的账号列出可见对象；
  - `checkReadAccess` 删除（服务账号执行前不再需要另一个账号的 EXPLAIN），只读查询与参考 DDL（`fetchTableDdl`）都直接用当前主体的连接，由数据库按其授权判定；
  - `resolveClusterDatasource` 只要求当前主体的映射；
  - `resolveUser` 若无其他调用方则删除。
- `DataQueryService` 的只读查询不再调用 `checkReadAccess`，`checkIdentity` 相关记录随之删除。

删除后，MCP 与 Data Studio 走同一条规则：**当前主体用自己的账号**。

### 4.3 查询历史

迁移 `V59__query_history_reported_caller.sql`：

```sql
ALTER TABLE data_query_history
    ADD COLUMN reported_agent_id VARCHAR(191) NULL COMMENT '调用方上报的 X-Agent-Id，未认证，仅审计',
    ADD COLUMN reported_user_id VARCHAR(255) NULL COMMENT '调用方上报的 X-Agent-User-Id，未认证，仅审计';
```

- `agent_id` 继续记录服务账号 ID（已认证）。
- `executed_by` 与 `check_account` 对服务账号查询不再填写（没有代用户检查）；历史数据保留。
- 头部值截断到列长度，只存原样字符串，不解析、不查用户。

### 4.4 不变的部分

- MCP 工具名、参数与返回不变；私网门禁、令牌过期与吊销、服务账号停用校验不变。
- 人在门户登录后的查询、目录、写入与结构编辑：仍用该用户自己的映射。
- 服务账号的角色分配仍在数据源权限页完成，首次分配自动创建受限数据库账号。

## 5. 影响

| 对象 | 影响 |
|---|---|
| DataAgent 问数用户、widget 用户 | 不再需要平台账号或数据源角色；能看到的范围等于所用智能体的服务账号范围 |
| 管理员 | 为每类问数范围建一个服务账号并分配只读角色；不再为每个问数用户授权 |
| 门户内嵌问数（「智能问数助手」） | 登录门户的所有用户共享该智能体服务账号的范围，不再按门户用户收窄。需要按人区分时，用不同智能体或等待 OAuth 模式 |
| 已有的人员角色映射 | 保留，继续用于门户内的查询；对 MCP 不再起作用 |
| DataAgent | 无代码变更。文档中「`X-Agent-User-Id` 作为执行身份」改为「供审计」，并说明每个数据范围配置一个 MCP 记录与令牌 |

## 6. 取舍

- **安全性实际上更清楚**：此前按未签名用户头收窄，持令牌者可冒充任何人；现在范围由令牌决定，泄露的令牌最多暴露该服务账号的范围，可单独吊销。
- **失去按人隔离**：同一智能体的所有使用者看到相同范围。用「每个范围一个智能体」加 DataAgent 可见范围弥补，彻底的按人隔离留给 OAuth 模式。
- **一条规则**：删除代用户分支后，平台只有「当前主体用自己的账号」一条路径，代码与排错都更简单。
- **没有可选开关**：不保留「代用户模式」作为配置项，避免两套语义并存；需要按人时直接实现 OAuth 模式。

## 7. 风险

| 风险 | 应对 |
|---|---|
| 服务账号范围过大，所有使用者都能看到 | 部署说明要求问数服务账号只分配只读角色、按智能体拆分；令牌限私网、设有效期 |
| 依赖代用户收窄的现有部署升级后范围变宽 | 发布说明明确行为变化；升级前检查各服务账号的角色，必要时先按智能体拆分 |
| 审计字段被误读为已认证身份 | 列名与界面文案均标注「上报」 |
| 与 2026-10-26 身份设计的既有结论冲突 | 在该设计相关小节顶部加「已由本设计取代」的说明与链接 |
