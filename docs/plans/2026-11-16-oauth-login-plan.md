# OAuth 登录实施计划

设计见 [OAuth 登录](../design/2026-11-16-oauth-login-design.md)。涉及后端 core/api/app、前端、部署。新增依赖 `spring-boot-starter-oauth2-client`；不改数据库结构。

## 任务

1. **依赖**：`backend/app/pom.xml` 加 `spring-boot-starter-oauth2-client`（版本由 Boot BOM 管理）。本机 Maven 缓存没有该依赖，首次构建需联网（不加 `-o`）。
2. **用户开通**（core，`com.onedata.auth.user`）：`SysUserMapper` 加含软删除行的 `(auth_source, external_id)` 查询；`OAuthUserProvisioner.provision(registrationId, sub, nickname, email)` → `SysUser`，按设计第 5 节（新建、刷新、停用/删除拒绝、唯一键冲突重查一次、长度校验）。只依赖 mapper，不依赖 Spring Security。
3. **Spring Security 装配**（app，`com.onedata.portal.security`）：
   - `OAuthLoginSecurityConfiguration`：`SecurityFilterChain`（设计第 6 节），`ObjectProvider<ClientRegistrationRepository>` 存在时启用 `oauth2Login`；启动校验 registration id 不为 `local`。
   - 成功处理器：从 `OAuth2AuthenticationToken` 取 registration id 与主体（`OidcUser.getSubject()` / `OAuth2User.getName()`，属性取显示名与邮箱），调用开通，签发会话 Cookie，`session.invalidate()`，302 `/login?oauth=success`。
   - 失败处理器：错误代码映射（设计第 4 节第 7 步），`session.invalidate()`，302。
   - 应用主类或配置排除 `UserDetailsServiceAutoConfiguration`。
   - `application.yml`（jar 内）加 `server.forward-headers-strategy: framework`。
   - `AuthenticationRoutePolicy` 加三类公开路径。
4. **会话 Cookie 复用**：把 `AuthController.buildSessionCookie` 与签发逻辑抽到 api/app 均可用的位置（如 core 的 `SessionCookieIssuer`），密码登录与 OAuth 成功处理器共用，行为不变。
5. **接口**：`AuthController` 加 `GET /auth/options`（从 `ObjectProvider<ClientRegistrationRepository>` 迭代 `InMemoryClientRegistrationRepository`，返回 id 与 `clientName`）。
6. **前端**：`api/auth.js` 加 `options()`；`LoginView.vue`（按钮、`sessionStorage` 存取 redirect、`oauth=success` 处理、错误提示）；`Layout.vue` 修改密码菜单按 `authSource`；demo mock。
7. **部署**：`deploy/docker/backend/application.yml`（全部注释的示例，见设计第 3 节）；`docker-compose.dev.yml`、`docker-compose.prod.yml` 后端挂载 `./docker/backend/application.yml:/app/config/application.yml:ro`；`scripts/create-offline-package.sh` 打包该文件；`tests/test_deploy_config_contract.py` 断言挂载；确认 `deploy/docker/nginx/frontend.conf` 的 `/api/` 转发带 `X-Forwarded-Proto` 与 `Host`。
8. **文档**：`docs/handbook/authentication.md` 增 OAuth 一节（配置示例、回调地址、用户规则、错误代码、registration id 与 DataAgent 对齐、修改 id 的影响、不支持包装结构），更新“当前状态”；`deploy/README.md` 配置说明；2026-05-30 设计与计划顶部注明 OAuth 部分被本设计取代。

## 验证

- 后端（`backend/` 下）：
  - `OAuthUserProvisionerTest`（Mockito mapper）：新建、刷新昵称与邮箱、停用拒绝、已删除拒绝、唯一键冲突重查、超长拒绝、显示名回退。
  - `OAuthLoginSecurityTest`（`@WebMvcTest` 或最小 `SpringBootTest` 切片，不连 MySQL）：用 `spring-security-test` 的 `oauth2Login()` / 直接调用成功处理器验证写 `odw_session` 且 302 `/login?oauth=success`；`/oauth2/authorization/sso` 302 到 IdP 且带 `state`；未配置 registration 时 404；`local` 作为 registration id 启动失败；其他路径不受 Spring Security 影响（不返回 Spring 的 401/403）。
  - `AuthenticationRoutePolicy` 公开路径测试；`AuthController` options 测试；密码登录相关既有测试仍通过。
  - 命令：`mvn -q -pl core,api,app -am test -Dtest='*OAuth*,*AuthController*,*RoutePolicy*,*JwtService*,SysUserServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`，再 `mvn -q -pl app -am compile`。
- 前端：`nvm use` 后运行 LoginView、Layout 相关 vitest 与 `npm run build`。
- 部署：`pytest tests/test_deploy_config_contract.py`。
- 端到端：本地 MySQL + 后端 + 本地 OIDC IdP 走完登录 → `/api/auth/me`，写入 `docs/reports/`；跑不了则在 `docs/TODO.md` 记录。

## 发布与回退

发布说明：配置 OAuth 需编辑 `deploy/docker/backend/application.yml` 并重启后端，在 IdP 登记回调 `https://<域名>/api/login/oauth2/code/<registration id>`。回退：注释掉 `spring.security.oauth2.client` 重启即恢复仅密码登录；已建 OAuth 用户保留在 `sys_user`。
