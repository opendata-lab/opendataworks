# OAuth 登录验证（2026-11-16）

关联：[设计](../design/2026-11-16-oauth-login-design.md)、[计划](../plans/2026-11-16-oauth-login-plan.md)。实现由 Gemini（gemini-4-hydrogen-xhigh）按计划完成，Claude 审阅并复跑。

## 自动化测试（Claude 复跑）

| 范围 | 命令 | 结果 |
|---|---|---|
| 后端认证与 OAuth | `mvn -o -pl core,api,app -am test -Dtest='*OAuth*,*AuthController*,*RoutePolicy*,*JwtService*,SysUserServiceTest,*UserManagement*'` | 49 通过 |
| 前端 | `vitest run` LoginView、Layout、Layout.navigation、IntelligentQueryRemoteEmbed、authRedirect、dataagentWidgetLoader、UserManagement | 7 文件 47 通过 |
| 部署契约 | `pytest tests/test_deploy_config_contract.py` | 11 通过 |

## 本地端到端

环境：`mysql:8.0` 容器（`utf8mb4_unicode_ci`，与生产 Compose 相同）、打包后的后端 jar（工作目录下 `config/application.yml`，模拟 `/app/config` 挂载）、本地纯 OAuth2 假 IdP（显式端点，`client_secret_basic`，非 OIDC）。

| 场景 | 结果 |
|---|---|
| Flyway 从空库迁移到 V60 | 60 个迁移全部成功；`sys_user.external_id` 已删除 |
| `/api/auth/options` | `{"localLogin":true,"oauth":[{"id":"sso","name":"SSO"}]}` |
| 发起 → IdP → 回调 | 302 到 IdP（带 `state`、绝对 `redirect_uri`）；回调 302 `/login?oauth=success`，写入 `odw_session`（HttpOnly、SameSite=Lax、8h） |
| `/api/auth/me` | `username=sso:u-1001`、`nickname=Alice Zhang`、`role=user`、`authSource=sso`；邮箱写入，`password_hash` 为空 |
| 再次登录 | 同一行，未重复建号 |
| IdP 拒绝授权 | `/login?error=oauth_denied` |
| 另一浏览器（无发起会话）带回调 | `/login?error=oauth_failed`（`authorization_request_not_found`） |
| 未知 registration | `/login?error=oauth_failed`（不再 500） |
| 用户停用 | `/login?error=oauth_account_disabled`；旧会话 `/auth/me` 401 |
| `sub` 仅大小写不同（`U-1001`） | `/login?error=oauth_failed`，未登录为已有用户 |
| 密码登录、受保护接口、MCP 无令牌 | 行为不变（200 / 401 / 401） |
| 未配置 OAuth | options `oauth: []`；`/api/oauth2/authorization/sso` 404；密码登录正常；无 Spring 默认生成密码日志 |
| 日志 | 不含授权码、访问令牌、`client_secret` |

## 真实 OIDC 与浏览器（Keycloak 26.0.7 + Playwright Chromium）

环境：本地 Keycloak（realm `odw`，机密客户端 `client_secret_basic`），后端用 `issuer-uri: http://localhost:18180/realms/odw` 启动（发现文档在启动时取到），前端 Vite 开发服务器（`/api` 代理到后端），`redirect-uri` 为浏览器地址 `http://localhost:3000/api/login/oauth2/code/keycloak`。

| 场景 | 结果 |
|---|---|
| 未登录打开 `/workflows` | 跳到 `/login?redirect=/workflows`，显示“或”与“通过 Keycloak 登录” |
| 点击按钮 → Keycloak 登录 alice | 回到 `/workflows`；页头显示 `alice`；`/auth/me` 为 `keycloak:<Keycloak sub>`、`role=user`；邮箱写入（OIDC `id_token` 验签路径） |
| OAuth 用户的用户菜单 | 不显示“修改密码” |
| bob 首次登录后被停用，再次登录 | 回到 `/login?error=oauth_account_disabled`，提示“账号已停用，请联系管理员” |
| 浏览器密码登录 admin，打开 `/lineage` | 回到 `/lineage`，页头“管理员” |
| 页面 JS 错误 | 无 |

## 未覆盖

- 真实 SSO：未用现场身份提供方验证。按设计只支持顶层用户标识（OIDC `sub` 或顶层 `user-name-attribute`），包装结构的自研用户信息接口不能登录。
- Compose 容器内 `/app/config/application.yml` 挂载未实际运行（jar 在工作目录 `config/` 下加载同一文件已验证）。
- OAuth 用户直接调用 `/api/auth/password` 返回“用户不存在”（既有逻辑，界面已隐藏入口），文案不准确，未改。
