# 登录与认证

- 适用版本: 自 `V44` 迁移（`sys_user` 表）起，经 `V55`/`V57`（会话版本与服务账号）与 `V60`（删除 `external_id`）演进
- 相关设计: [2026-11-16 OAuth 登录设计](../design/2026-11-16-oauth-login-design.md)、[2026-11-03 后端收敛与认证设计](../design/2026-11-03-backend-mcp-auth-consolidation-design.md)（[2026-05-30 原 OAuth 设计](../design/2026-05-30-configurable-oauth-login-design.md) 中的 OAuth 部分已被取代）
- 相关计划: [2026-11-16 OAuth 登录实施计划](../plans/2026-11-16-oauth-login-plan.md)
- 当前状态: **本地密码登录与 Spring Security OAuth2 Client 登录均已上线**（`deploy/docker/backend/application.yml` 默认全部注释，即仅开启密码登录；按该文件取消注释配置并重启后端后，登录页展示对应 OAuth 登录按钮）

## 1. 功能概述

平台认证逻辑收敛在后端三层模块中：

- `backend/core`（包 `com.onedata.auth.*`）：用户实体/Mapper（`SysUser`、`SysUserMapper`）、密码登录与会话校验（`SysUserService`）、OAuth 首次建号与资料刷新（`OAuthUserProvisioner`）、HS256 会话 JWT 与 Cookie 构造（`Hs256JwtService`、`SessionCookieIssuer`）、线程上下文（`PrincipalContextHolder`）及 `@RequireAuth`/`@RequireRole` 切面（`AuthorizationAspect`）。
- `backend/api`（`com.onedata.auth.web.AuthController`）：本地登录、登出、当前用户信息、修改密码接口。
- `backend/app`（`com.onedata.portal.security.*` 与 `AuthOptionsController`）：`HttpAuthenticationFilter`（`Ordered.HIGHEST_PRECEDENCE` 最高优先级）、`AuthenticationRoutePolicy`（按路径划分为 `PUBLIC`、`USER`、`SERVICE`）、`OAuthLoginSecurityConfiguration`（仅接管两个 OAuth2 端点）、`GET /auth/options`。

认证与会话特性：

- 本地用户名 + 密码登录（bcrypt 存储，连续失败 5 次锁定 15 分钟）。
- OAuth2 / OIDC 授权码登录（基于标准 `spring-boot-starter-oauth2-client`），登录成功后由 ODW 统一签发 `odw_session` Cookie 并立即使临时 Spring Security HTTP 会话失效。
- `HttpAuthenticationFilter` 先于 Spring Security 执行，按 `AuthenticationRoutePolicy` 决定公开路径、用户会话路径与 MCP 服务令牌路径；用户路径无有效会话时返回 `401`。
- 前端路由守卫：未登录访问受保护页面跳转 `/login`；任意请求返回 `401` 时统一跳登录页并保留站内回跳路径。
- `@RequireAuth`（要求已登录）与 `@RequireRole("admin")`（要求管理员角色）方法级注解，从 `PrincipalContextHolder` 读取当前主体。

## 2. 初始管理员账号

| 项 | 值 |
|---|---|
| 用户名 | `admin` |
| 初始密码 | `admin123` |
| 角色 | `admin` |

初始账号由 Flyway 迁移 `backend/app/src/main/resources/db/migration/V44__create_sys_user.sql` 直接写入数据库，无需环境变量。
**部署后请立即通过页面右上角「用户菜单 → 修改密码」更换初始密码**（新密码至少 8 位，UTF-8 长度不超过 72 字节）。

账号锁定后无需人工干预，15 分钟后自动解锁；如需立即解锁，可将 `sys_user.locked_until` 置空。

## 3. 认证接口

均位于主后端（`server.servlet.context-path: /api`）：

| 方法 | 路径 | 路由策略 | 说明 |
|---|---|---|---|
| GET | `/api/auth/options` | PUBLIC | 返回 `{"localLogin":true,"oauth":[{"id":"...","name":"..."}]}`（未配置 OAuth 时为 `[]`） |
| POST | `/api/auth/login` | PUBLIC | 入参 `{username, password}`；成功 `Set-Cookie: odw_session` 并返回用户信息 |
| POST | `/api/auth/logout` | PUBLIC | 清除会话 Cookie（`Max-Age=0`） |
| GET | `/api/auth/me` | USER | 返回 `{userId, username, nickname, role, authSource}`（未登录返回 401；不再返回 `agentUserId`） |
| POST | `/api/auth/password` | USER | 入参 `{oldPassword, newPassword}`；仅本地账号可修改密码 |
| GET | `/api/oauth2/authorization/{id}` | PUBLIC | 发起 OAuth2/OIDC 授权，生成 `state`（OIDC 含 `nonce`）并 302 跳转到 IdP |
| GET | `/api/login/oauth2/code/{id}` | PUBLIC | IdP 回调端点：换取令牌与用户信息、完成建号/刷新、签发 `odw_session`，302 到 `/login?oauth=success` 或 `/login?error=<代码>` |

普通接口响应统一为 `{code, message, data}`；密码登录失败（口令错误/锁定/停用）返回 HTTP 200 + `code=400` 与原因。

## 4. 基础配置与路径策略

`backend/app/src/main/resources/application.yml` 中的 `odw.auth.*` 与 `odw.http.*`，生产经 `deploy/.env`（模板 `deploy/.env.example`）注入：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AUTH_JWT_SECRET` | `opendataworks-dev-jwt-secret-change-me` | 会话 JWT 共享密钥，**生产必须改为随机值**，长度不少于 32 字节 |
| `AUTH_JWT_ISSUER` | `opendataworks` | JWT 签发方 |
| `AUTH_JWT_TTL` | `8h` | 会话有效期 |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 入口时置 `true`，会话 Cookie 加 `Secure` |
| `ODW_HTTP_ALLOWED_ORIGINS` | 空 | 允许的额外浏览器来源 |
| `ODW_HTTP_REQUIRE_PRIVATE_NETWORK` | `true` | 是否限制 `/mcp` 仅私网访问 |
| `ODW_HTTP_TRUSTED_PROXIES` | 空 | 受信反向代理 IP 列表（用于 `/mcp` 解析 `X-Forwarded-For`） |

### 路径分类（`AuthenticationRoutePolicy`）

Filter 匹配去掉 context-path `/api` 后的 servlet path：

- `PUBLIC`：`/auth/login`、`/auth/logout`、`/auth/options`、`/oauth2/authorization/**`、`/login/oauth2/code/**`、`/v1/health`、`/v1/health/**`、`/actuator/health`、`/error`。
- `SERVICE`：`/mcp`、`/mcp/**`、`/mcp;*`（使用账号管理生成的服务账号 Bearer Token `odw_sa_*`，与门户用户会话隔离）。
- `USER`：其余全部路径，必须携带有效用户会话 Cookie `odw_session` 或 `Authorization: Bearer <jwt>`，且每次请求经 `SysUserService.verifySession` 校验启用状态、`auth_source` 与 `session_version`。

## 5. OAuth2 / OIDC 登录

### 配置方式与示例

OAuth 提供方直接写在宿主机 `deploy/docker/backend/application.yml`，Compose 将其只读挂载到容器 `/app/config/application.yml`（后端工作目录 `/app`，Spring Boot 自动加载 `./config/application.yml`），不通过环境变量注入；修改后需重启后端生效。默认全部注释即仅开启密码登录：

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          sso:                                   # registration id：同时作为 auth_source 与用户名前缀
            client-name: SSO                     # 登录页按钮显示“通过 SSO 登录”（缺省时用 id）
            client-id: your-client-id
            client-secret: your-client-secret
            client-authentication-method: client_secret_basic   # 或 client_secret_post
            authorization-grant-type: authorization_code
            redirect-uri: https://odw.example.com/api/login/oauth2/code/sso   # 必须写绝对地址，不要用 {baseUrl}
            scope: openid,profile,email
        provider:
          sso:
            issuer-uri: https://sso.example.com          # OIDC：启动时自动发现端点
            # 非 OIDC（纯 OAuth2）改为显式端点：
            # authorization-uri: https://sso.example.com/oauth/authorize
            # token-uri: https://sso.example.com/oauth/token
            # user-info-uri: https://sso.example.com/oauth/userinfo
            # user-info-authentication-method: header    # header | form | query
            # user-name-attribute: sub
```

配置约束与注意事项：

- **必须填写绝对 `redirect-uri`**：形式为 `https://<浏览器访问的域名>/api/login/oauth2/code/<registration id>`，并在 IdP 原样登记。不要使用 `{baseUrl}`，也**禁止启用 `server.forward-headers-strategy`**（启用转发头策略会无条件信任 `X-Forwarded-For`，从而绕过 `/mcp` 的私网来源校验）。
- **`registration id` 校验规则（启动即校验，失败阻止启动）**：长度 1～32 位（匹配 `sys_user.auth_source` 列长 `VARCHAR(32)`），必须以字母或数字开头，仅允许字母、数字、`.`、`_`、`-`（不含 `:`）；不区分大小写不可为 `local`、`service`、`anonymous`。
- **`issuer-uri` 启动依赖**：配置 `issuer-uri` 时 Spring Boot 会在启动阶段请求 IdP 的 OIDC 发现文档；如果 IdP 此时不可达，整个后端（包括本地密码登录）都会启动失败。IdP 启动期可用性无法保证时，请改为显式配置 `authorization-uri`、`token-uri`、`user-info-uri` 与 `user-name-attribute`。
- **与 DataAgent 的用户名一致**：OAuth 用户名与 DataAgent 规则相同（取 `preferred_username`，缺省为 `sub`）。门户内嵌 DataAgent widget 直接上报 `/auth/me` 的 `username`，DataAgent 对应同名账号，两边会话共用。
- **修改 `registration id` 的影响**：身份按 `(registration id, sub)` 绑定；改名后原 OAuth 用户再次登录会按用户名重新绑定到原账号。
- **`client-secret` 安全**：仅留在 `deploy/docker/backend/application.yml`，不进日志，也不经 `/api/auth/options` 等接口返回。

### 用户建号与刷新规则

- 用户名 `sys_user.username` 取顶层属性 `preferred_username`，缺省为 `sub`，须为 1-128 位英文、数字或 `. _ @ + -` 且以字母或数字开头（与 DataAgent 相同），否则登录失败 `oauth_failed`。外部身份 `(registration id, sub)` 记在 `sys_user_identity`（`V61`）。首次登录时已有同名普通账号（含本地账号）即绑定，否则新建；服务账号不绑定。`V61` 之前创建的 `<registration id>:<sub>` 账号在下次登录时改为新用户名（新名被占用时保留旧名）。**只接入用户名不可由用户自改的可信 IdP**：`preferred_username` 与已有账号同名即登录该账号。
- 新建账号的 `auth_source` 为 `<registration id>`（绑定到已有本地账号时不变）；`account_type` 为 `USER`；`password_hash` 为 `NULL`（本地密码登录、修改密码与管理员重置密码接口均拒绝 OAuth 账号）。
- `role` 仅在首次登录新建时写入 `user`，之后每次登录保持数据库内现有角色不变（由管理员在「账号管理」提升）。
- `nickname` 只在账号昵称为空时填写，已有昵称不被 IdP 覆盖（与 DataAgent 一致）；取值依次为顶层属性 `preferred_username`、`name`、`nickname`，均无有效非空字符串时回退到 `sub`，超过 128 字符截断。
- `email` 每次登录刷新：仅当顶层属性 `email` 为非空字符串且长度不超过 128 字符时写入，否则置空（`NULL`）。
- 已停用（`enabled=0`）或已软删除（`deleted=1`）账号拒绝登录为 `oauth_account_disabled`。并发首次登录撞唯一键时整体重来一次。成功登录后更新 `last_login_at`。

### 错误代码与提示

OAuth 端点失败时不保留临时 HTTP 会话，统一 302 跳转 `/login?error=<代码>`：

| 错误代码 | 触发原因 | 前端提示 |
|---|---|---|
| `oauth_denied` | IdP 返回 `access_denied`（用户取消授权） | 已取消授权 |
| `oauth_account_disabled` | 对应用户已停用（`enabled=0`）或已软删除（`deleted=1`） | 账号已停用，请联系管理员 |
| `oauth_failed` | 其余 OAuth 失败（包括 `state` 无效、换令牌/取用户信息失败、未知 registration id、`username` 超长、账号类型/来源/大小写冲突、成功回调异常、以及 `oauth=success` 后前端 `fetchMe()` 未获取到有效会话） | 第三方登录失败，请重试或联系管理员 |

### 不支持范围

- 不支持包装或嵌套结构的 userinfo 响应（仅支持标准顶层属性与 `user-name-attribute`）。
- 不支持从令牌响应直接提取用户标识或根据声明做角色映射。
- 不支持刷新令牌、单点登出（SLO）、以及无粘滞会话的多实例授权请求共享。
- 机密客户端按 Spring Security 6.5 默认不附加 PKCE（仅 `client-authentication-method: none` 时启用）。

## 6. 会话令牌契约

JWT claims（HS256，密钥 `AUTH_JWT_SECRET`）：

| claim | 含义 |
|---|---|
| `sub` | 平台用户 ID（`sys_user.id`） |
| `username` | 用户名（本地用户名，或 OAuth 用户的 `preferred_username` / `sub`） |
| `role` | `admin` / `user` |
| `auth_source` | `local` 或 OAuth `<registration id>` |
| `session_version` | 会话版本号（改密、停用、软删除时递增，立即使旧 Cookie 失效） |
| `iss` | 签发方（默认 `opendataworks`） |
| `iat` / `exp` | 签发 / 过期时间 |

载体优先 `Authorization: Bearer <jwt>`（存在该请求头时即使无效也不再回退 Cookie），无该头时读取 Cookie `odw_session`（`odw_sa_*` 前缀仅用于 `/mcp` 服务账号鉴权，不作为用户会话）。

## 7. 回退

- OAuth 配置回退：注释掉 `deploy/docker/backend/application.yml` 中的 `spring.security.oauth2.client` 并重启后端，即恢复仅密码登录；已建 OAuth 用户保留在 `sys_user`，其记录可继续由管理员查看或停用。
- 数据层说明：`V60` 删除从未写入生产数据的 `sys_user.external_id` 列及其索引 `uk_sys_user_external`、`idx_external_id`。
