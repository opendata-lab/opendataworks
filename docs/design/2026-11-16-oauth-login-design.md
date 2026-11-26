# OAuth 登录（Spring Security OAuth2 Client 标准方式）

- 日期：2026-11-16。状态：已确认（2026-11-16 用户决定：采用 Java 标准方式，不自定义配置格式）
- 范围：`backend/`（core、api、app）、`frontend/`、`deploy/`
- 规模：中（跨前后端 + 新依赖 + 部署配置 + 一个删列迁移）
- 评审：2026-11-16 Gemini 设计评审，结论见 `~/.agents/handoffs/2026-11-16-gemini-odw-oauth-design-review/notes.md`；采纳的修改已并入本文
- 取代：[2026-05-30 可配置 OAuth 登录设计](2026-05-30-configurable-oauth-login-design.md) 中的 OAuth 部分（`sys_oauth_config` 表、管理员界面配置、自定义字段）。本地密码登录、会话 JWT、Filter 仍以原设计与[认证手册](../handbook/authentication.md)为准。
- 实施见[计划](../plans/2026-11-16-oauth-login-plan.md)。

## 1. 现状

- 本地密码登录已上线：`sys_user`（bcrypt、失败锁定）、HS256 会话 JWT 写入 HttpOnly Cookie `odw_session`，`HttpAuthenticationFilter`（最高优先级）默认拦截，`AuthenticationRoutePolicy` 决定公开路径。
- OAuth 未实现。`sys_user` 有 `auth_source` 与从未被写入的 `external_id`（`uk_sys_user_external` 唯一）；`external_id` 只被 `AuthController.agentUserId` 读取。`agentUserId` 由 `/auth/me` 返回，门户内嵌 DataAgent widget 时作为 `userId` 上报。
- DataAgent、OntoFoundry 用外置 Python 配置与钩子；本仓库后端是 Java，不运行 Python。

## 2. 决定

| 编号 | 决定 |
|---|---|
| OA-01 | 使用 Spring Security OAuth2 Client（`spring-boot-starter-oauth2-client`，版本由 Spring Boot 3.5.7 管理），配置为 Spring Boot 标准属性 `spring.security.oauth2.client.registration.*` / `provider.*`，不定义 ODW 自有配置键。授权码流程、`state`、OIDC 发现、`nonce` 与 `id_token` 验签均由框架完成。PKCE 按框架默认：仅 `client-authentication-method: none` 时启用，机密客户端不加（Spring Security 6.5 `DefaultOAuth2AuthorizationRequestResolver`）。 |
| OA-02 | 配置写在宿主机 `deploy/docker/backend/application.yml`，只读挂载到 `/app/config/application.yml`（后端工作目录 `/app`，Spring Boot 默认加载 `./config/application.yml`）。不新增环境变量。修改需重启后端。 |
| OA-03 | 用户标识取框架给出的主体名：OIDC 为 `sub`；纯 OAuth2 为 `user-name-attribute` 指定的顶层属性。不支持嵌套或包装结构的用户信息（标准 `user-name-attribute` 只取顶层键）。 |
| OA-04 | 本地账号保持 `sys_user`。首次 OAuth 登录自动建用户，角色 `user`；管理员在用户管理中提升角色。不做角色映射。 |
| OA-05 | 支持框架允许的多个 registration；registration id 即新建 OAuth 账号的 `auth_source`，并与 `sub` 一起记在 `sys_user_identity`（OA-08）。 |
| OA-06 | 登录成功后由 ODW 签发现有 `odw_session` 会话 JWT；Spring Security 只负责 OAuth2 登录两个端点，不接管其他请求的鉴权。 |
| OA-07 | 删除后端 `agentUserId`（2026-11-16 用户决定）：`/auth/me` 不再返回该字段。门户内嵌 DataAgent widget 直接上报 `/auth/me` 的 `username`（2026-11-25 起不再拼 `local:` 前缀），DataAgent 把它对应到同名账号。 |
| OA-08 | **OAuth 用户名与 DataAgent 对齐（2026-11-25 用户决定）**：用户名改为普通用户名，取 IdP 的 `preferred_username`，缺省为 `sub`，规则与 DataAgent 相同（1-128 位英文、数字或 `. _ @ + -`，以字母或数字开头，否则登录失败 `oauth_failed`）。外部身份 `(registration id, sub)` 记在新表 `sys_user_identity`（迁移 `V61`）。首次登录时已有同名账号（含本地账号，不含服务账号）即绑定该账号，否则新建。门户 widget 因此对 SSO 用户也上报与 DataAgent 相同的用户名，两边会话共用。**已接受的风险**：IdP 的 `preferred_username` 决定绑定到哪个已有账号，与本地账号同名（如 `admin`）即获得该账号，只能接入可信 IdP（用户名不可由用户自改）。 |

## 3. 配置

```yaml
# deploy/docker/backend/application.yml（宿主机管理，只读挂载；默认全部注释 = 仅密码登录）
spring:
  security:
    oauth2:
      client:
        registration:
          sso:                                   # registration id：auth_source 与用户标识前缀
            client-name: SSO                     # 登录按钮“通过 SSO 登录”
            client-id: your-client-id
            client-secret: your-client-secret
            client-authentication-method: client_secret_basic   # 或 client_secret_post
            authorization-grant-type: authorization_code
            redirect-uri: https://odw.example.com/api/login/oauth2/code/sso   # 写绝对地址，不用 {baseUrl}
            scope: openid,profile,email
        provider:
          sso:
            issuer-uri: https://sso.example.com          # OIDC：其余端点自动发现
            # 非 OIDC 时改为显式端点：
            # authorization-uri: https://sso.example.com/oauth/authorize
            # token-uri: https://sso.example.com/oauth/token
            # user-info-uri: https://sso.example.com/oauth/userinfo
            # user-info-authentication-method: header    # header | form | query
            # user-name-attribute: sub
```

- 回调地址（在 IdP 登记，并原样写入 `redirect-uri`）：`https://<浏览器访问的域名>/api/login/oauth2/code/<registration id>`。写绝对地址而不用 `{baseUrl}`：后端在反向代理之后，`{baseUrl}` 需要信任转发头，而 `server.forward-headers-strategy` 会无条件信任 `X-Forwarded-For`，绕过 MCP 私网校验（2026-11-16 设计评审发现），故不启用。
- 发起地址：`/api/oauth2/authorization/<registration id>`（框架默认）。
- registration id 启动校验，失败即启动失败：1～32 位（`sys_user.auth_source` 为 `VARCHAR(32)`），以字母或数字开头，仅含字母、数字、`.`、`_`、`-`（不含 `:`）；不区分大小写不能为 `local`、`service`、`anonymous`（已被本地账号、服务账号、匿名主体占用）。
- 使用 `issuer-uri` 时 Spring Boot 在启动时请求 IdP 的发现文档，IdP 不可达则后端（含密码登录）无法启动；IdP 可用性无法保证时改写显式端点。
- `client-secret` 只在该文件，不进日志、不经接口返回。

## 4. 流程

1. 登录页 `GET /api/auth/options` → `{"localLogin":true,"oauth":[{"id":"sso","name":"SSO"}]}`（未配置为 `[]`），公开。
2. 用户点按钮：前端把登录后要回到的站内路径存入 `sessionStorage`（键 `odw_login_redirect`），浏览器跳 `/api/oauth2/authorization/sso`。
3. 框架生成 `state`（OIDC 另含 `nonce`、PKCE 按框架默认），授权请求暂存于 HTTP 会话（框架默认 `HttpSessionOAuth2AuthorizationRequestRepository`），302 到 IdP。
4. IdP 回调 `/api/login/oauth2/code/sso`，框架校验 `state`、换令牌、取用户信息（OIDC 校验 `id_token`）。
5. 用户开通在认证过程中完成：ODW 包装框架默认的 `OidcUserService` / `DefaultOAuth2UserService`（`oauth2Login().userInfoEndpoint()` 的标准扩展点），在其返回主体后按第 5 节建立或更新用户；停用、已删除或数据不合法时抛 `OAuth2AuthenticationException`（错误码见第 7 步），走框架的失败处理。成功处理器（ODW）只做：签发 `odw_session`（与密码登录同一方法与 Cookie 属性）→ 使 HTTP 会话失效（不保留 Spring 的登录态）→ 302 到 `/login?oauth=success`。
6. 前端登录页看到 `oauth=success`：`fetchMe()`；若仍未登录（例如 `Secure` Cookie 在 HTTP 下被丢弃）显示“第三方登录失败”而不是静默停留；已登录则读取并清除 `odw_login_redirect`（只接受以单个 `/` 开头的站内路径，否则 `/`），跳转。
7. 失败处理器：使 HTTP 会话失效，302 到 `/login?error=<代码>`：`oauth_account_disabled`（停用或已删除）、`oauth_denied`（IdP 返回 `access_denied`）、其余 `oauth_failed`。未知 registration id（`/oauth2/authorization/<未知>` 抛出的 `InvalidClientRegistrationIdException`）也重定向为 `oauth_failed`，不返回 500。成功处理器出现非认证异常时同样使会话失效并重定向 `oauth_failed`。日志记录 registration id 与异常类型和 OAuth 错误码，不记录令牌与 `code`。

多实例部署时，授权请求存于单实例 HTTP 会话，需会话粘滞；当前 Compose 为单后端实例。

## 5. 用户

| 列 | OAuth 用户 |
|---|---|
| `username` | 普通用户名：`preferred_username`，缺省为 `sub`（OA-08）。`sub` 为 OIDC `getSubject()` 或 OAuth2 `OAuth2User.getName()` |
| `auth_source` | 新建时为 registration id；绑定到已有本地账号时保持原值 |
| `nickname` | 只在账号昵称为空或等于用户名时填写（与 DataAgent 一致，2026-11-26 起；绑定本地账号时不覆盖其昵称）：依次取属性 `name`、`nickname`、`preferred_username`（用户名取 `preferred_username`，显示名优先取中文名 `name`），均无则 `sub`；超过 128 字符截断 |
| `email` | 每次登录刷新：属性 `email`（字符串且不超过 128 字符时，否则置空） |
| `role` | 新建时 `user`，此后不改 |
| `password_hash` | 新建时 `NULL`（现有逻辑已拒绝其密码登录、改密与重置） |
| `account_type` | `USER` |

外部身份表 `sys_user_identity(registration_id, subject, user_id)`，`(registration_id, subject)` 唯一。登录顺序（`OAuthUserProvisioner`，与 DataAgent `link_or_register` 相同）：

1. 按 `(registration id, sub)` 找到绑定账号：刷新资料后登录。账号用户名仍是旧格式 `<registration id>:<sub>` 且新用户名空闲时，顺带改为新用户名。
2. 未绑定：按新用户名查 `sys_user`（含已软删除行）。存在且为普通账号即写入绑定；服务账号拒绝 `oauth_failed`。
3. 不存在则新建账号并写入绑定。并发首登撞唯一键时整体重来一次。

已删除或 `enabled=0` 拒绝登录 `oauth_account_disabled`。成功后更新 `last_login_at`。MySQL 默认排序规则不区分大小写，`preferred_username` 与已有账号仅大小写不同也视为同名（与 DataAgent 一致）。

迁移 `V61` 建表，并为已有 `<registration id>:<sub>` 账号补登绑定；用户名在其下次 SSO 登录时改为新用户名（新名被占用则保留旧名）。账号 ID 不变，数据源授权按 ID 关联不受影响；按用户名记录的历史字段（如创建人）保留旧值。

不使用 `external_id`（2026-11-16 用户决定，`V60` 已删除）。修改已有部署的 registration id 后，原 OAuth 用户再次登录会按用户名重新绑定到原账号。

## 6. 与现有鉴权的关系

- `HttpAuthenticationFilter` 先于 Spring Security 执行；`AuthenticationRoutePolicy` 把 `/auth/options`、`/oauth2/authorization/**`、`/login/oauth2/code/**` 设为 PUBLIC。
- ODW 始终定义唯一的 `SecurityFilterChain`（否则 Spring Boot 会启用默认链保护全部接口）：只匹配上述两个 OAuth2 端点（`securityMatcher`），`anyRequest().permitAll()`；关闭 CSRF、表单登录、HTTP Basic、登出、请求缓存；安全上下文用 `RequestAttributeSecurityContextRepository`，不写入 HTTP 会话。其余请求不进入 Spring Security，鉴权行为不变。
- `ClientRegistrationRepository` 存在时才启用 `oauth2Login`（配置用户服务包装、成功与失败处理器）。未配置任何 registration 时 Spring Boot 不创建该 bean，两个端点落到 DispatcherServlet 返回 404。
- `/auth/options` 放在 app 模块（依赖所在），从 Spring Boot 的 `OAuth2ClientProperties` 读取 registration id 与 `client-name`，不依赖 `ClientRegistrationRepository` 的具体实现。
- 排除 `UserDetailsServiceAutoConfiguration`，避免生成默认用户与密码日志。

## 7. 前端

- 登录页按 `/auth/options` 渲染：密码表单始终显示；有 OAuth 时显示分隔线“或”与每个 registration 一个按钮“通过 {name} 登录”。
- `route.query.error` 存在时在表单上方显示 `el-alert`（error）：`oauth_denied` 已取消授权；`oauth_account_disabled` 账号已停用，请联系管理员；其余“第三方登录失败，请重试或联系管理员”。
- 用户菜单“修改密码”仅对 `authSource === 'local'` 显示。
- 演示模式 mock：`/auth/options` 返回 `oauth: []`。

## 8. 不包含

- 包装或嵌套结构的用户信息、从令牌响应取用户标识、角色映射（均需自定义扩展，按用户决定不做）。
- 刷新令牌、单点登出、Python 钩子、DataAgent 侧改动。
- 多实例无粘滞会话下的授权请求存储。
- 机密客户端强制 PKCE（框架默认不加；需要时另行设计）。
