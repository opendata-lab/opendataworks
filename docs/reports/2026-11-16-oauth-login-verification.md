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

## 未覆盖

- 真实 OIDC 提供方（`issuer-uri` 发现、`id_token` 验签）与真实 SSO：本机无 OIDC IdP，记入 `docs/TODO.md`。
- 浏览器内完整点击流程（登录页按钮、`sessionStorage` 回跳、错误提示）只由组件测试覆盖；Compose 实际挂载 `/app/config/application.yml` 未在容器中运行。记入 `docs/TODO.md`。
- OAuth 用户直接调用 `/api/auth/password` 返回“用户不存在”（既有逻辑，界面已隐藏入口），文案不准确，未改。
