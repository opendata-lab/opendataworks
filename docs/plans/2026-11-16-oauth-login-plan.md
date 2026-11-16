# OAuth 登录实施计划

设计见 [OAuth 登录](../design/2026-11-16-oauth-login-design.md)。涉及后端 core/api/app、前端、部署。新增依赖 `spring-boot-starter-oauth2-client`；一个删列迁移 `V60`。

## 任务

1. **依赖与迁移**：`backend/app/pom.xml` 加 `spring-boot-starter-oauth2-client` 与测试范围 `spring-security-test`（版本由 Boot BOM 管理）。`backend/app/src/main/resources/db/migration/V60__drop_sys_user_external_id.sql`：删索引 `uk_sys_user_external`、`idx_external_id`，删列 `external_id`（设计第 5 节）。`SysUser` 去掉 `externalId`；`AuthController.agentUserId` 对非本地用户返回 `username`，更新 `AuthControllerAgentUserIdTest`；`frontend/src/utils/dataagentWidgetLoader.js:61` 注释改为 `<来源>:<sub>`。本机 Maven 缓存没有该依赖，首次构建需联网（不加 `-o`）。
2. **用户开通**（core，`com.onedata.auth.user`）：`SysUserMapper` 加含软删除行的按 `username` 查询；`OAuthUserProvisioner.provision(registrationId, sub, nickname, email)` → `SysUser`，按设计第 5 节（新建、刷新、停用/删除拒绝、`auth_source` 不符或用户名大小写不同则拒绝、唯一键冲突重查一次、长度校验、昵称截断与邮箱置空）；拒绝时抛带错误码的领域异常。只依赖 mapper，不依赖 Spring Security。
3. **Spring Security 装配**（app，`com.onedata.portal.security`）：
   - `OAuthLoginSecurityConfiguration`：始终存在的 `SecurityFilterChain`（设计第 6 节）；`ObjectProvider<ClientRegistrationRepository>` 存在时启用 `oauth2Login`；启动校验 registration id（设计第 3 节规则）。
   - 用户服务包装：委托框架默认 `OidcUserService` / `DefaultOAuth2UserService`，取 registration id 与主体（`OidcUser.getSubject()` / `OAuth2User.getName()`，属性取显示名与邮箱）调用开通；领域拒绝转为 `OAuth2AuthenticationException`（错误码 `oauth_account_disabled` / `oauth_failed`）。开通后的 `sys_user.id` 放进返回主体的属性，供成功处理器签发会话。
   - 成功处理器：签发会话 Cookie，`session.invalidate()`，302 `/login?oauth=success`；任何异常同样失效会话并 302 `oauth_failed`。
   - 失败处理器：错误代码映射（设计第 4 节第 7 步，含 `InvalidClientRegistrationIdException`），`session.invalidate()`，302。
   - 排除 `UserDetailsServiceAutoConfiguration`。不启用 `server.forward-headers-strategy`。
   - `AuthenticationRoutePolicy` 加三类公开路径。
4. **会话 Cookie 复用**：把 `AuthController.buildSessionCookie` 与签发逻辑抽到 api/app 均可用的位置（如 core 的 `SessionCookieIssuer`），密码登录与 OAuth 成功处理器共用，行为不变。
5. **接口**：app 模块新增控制器 `GET /auth/options`，从 Spring Boot `OAuth2ClientProperties` 读取 registration id 与 `client-name`（缺省时用 id），返回 `{localLogin, oauth:[{id,name}]}`。api 模块不依赖 OAuth2 Client。
6. **前端**：`api/auth.js` 加 `options()`；`LoginView.vue`（按钮、`sessionStorage` 存取 redirect、`oauth=success` 处理且 `fetchMe()` 后检查 `isLoggedIn`、错误提示；密码登录与 OAuth 回跳共用同一 redirect 净化：单个 `/` 开头，拒绝 `//`、`/\\`、`/login`）；`Layout.vue` 修改密码菜单按 `authSource`；demo mock。
7. **部署**：`deploy/docker/backend/application.yml`（全部注释的示例，见设计第 3 节）；`docker-compose.dev.yml`、`docker-compose.prod.yml` 后端挂载 `./docker/backend/application.yml:/app/config/application.yml:ro`；`scripts/create-offline-package.sh` 打包该文件；`tests/test_deploy_config_contract.py` 断言挂载。
8. **文档**：`docs/handbook/authentication.md` 增 OAuth 一节（配置示例、绝对回调地址、registration id 规则、`issuer-uri` 启动依赖、用户规则、错误代码、与 DataAgent 对齐、修改 id 的影响、不支持包装结构），更新“当前状态”，并修正已过时的内容（`odw-auth/` 模块、`UserContextHolder`、`AuthenticationFilter`、`AUTH_ANONYMOUS_ENABLED` 等，按当前代码改写）；`deploy/README.md` 配置说明；2026-05-30 设计与计划顶部注明 OAuth 部分被本设计取代。

## 验证

- 后端（`backend/` 下）：
  - `OAuthUserProvisionerTest`（Mockito mapper）：新建、刷新昵称与邮箱、停用拒绝、已删除拒绝、唯一键冲突重查、超长拒绝、显示名回退。
  - `OAuthLoginSecurityTest`：不用 `@WebMvcTest`（主类带 `@MapperScan`，`HttpAuthenticationConfiguration` 依赖数据库服务），也不用连 MySQL 的 `@SpringBootTest`；用嵌套 `@SpringBootConfiguration` 只装配 `OAuthLoginSecurityConfiguration`、Security 与 OAuth2 Client 自动配置、`HttpAuthenticationFilter`（依赖用 Mockito 替身），用 MockMvc 走真实过滤器：`/oauth2/authorization/sso` 302 到 IdP 且带 `state`；回调用本地 JDK `HttpServer` 假 IdP（显式端点，非 OIDC）完成换令牌与 userinfo，验证写 `odw_session`、会话失效、302 `/login?oauth=success`；停用用户 302 `oauth_account_disabled`；未知 id 302 `oauth_failed`；未配置 registration 时 404；registration id 规则启动失败；其他路径不受 Spring Security 影响。
  - `AuthenticationRoutePolicy` 公开路径测试；`AuthController` options 测试；密码登录相关既有测试仍通过。
  - 命令：`mvn -q -pl core,api,app -am test -Dtest='*OAuth*,*AuthController*,*RoutePolicy*,*JwtService*,SysUserServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`，再 `mvn -q -pl app -am compile`。
- 迁移：在本机 `mysql:8` 容器按顺序跑 `V44`、`V54`、`V60` 中涉及 `sys_user` 的语句，确认删列与删索引成功。
- 前端：`nvm use` 后运行 LoginView、Layout 相关 vitest 与 `npm run build`。
- 部署：`pytest tests/test_deploy_config_contract.py`。
- 端到端：本地 MySQL + 后端 + 本地 OIDC IdP 走完登录 → `/api/auth/me`，写入 `docs/reports/`；跑不了则在 `docs/TODO.md` 记录。

## 发布与回退

发布说明：配置 OAuth 需编辑 `deploy/docker/backend/application.yml` 并重启后端，在 IdP 登记回调 `https://<域名>/api/login/oauth2/code/<registration id>`。回退：注释掉 `spring.security.oauth2.client` 重启即恢复仅密码登录；已建 OAuth 用户保留在 `sys_user`。
