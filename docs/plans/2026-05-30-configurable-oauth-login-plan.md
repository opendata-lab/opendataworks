# 可配置 OAuth 登录与统一身份认证 实施计划

- 日期: 2026-05-30
- 配套设计: `docs/design/2026-05-30-configurable-oauth-login-design.md`
- 涉及栈: `backend/`(主)、`frontend/`、`dataagent/dataagent-backend/`(身份校验落地)、`deploy/`(env + 反代)

## 阶段划分

按风险与依赖分四阶段,可分 PR 交付。阶段 1-2 即可让平台具备登录与保护能力;阶段 3 收口 dataagent;阶段 4 部署收敛。

---

## 阶段 1:后端认证基座(密码登录 + 会话 + 默认拦截)

目标:平台具备本地密码登录、会话签发/校验、默认拦截全部 + 白名单。

### 数据库
- [ ] `backend/src/main/resources/db/migration/V44__create_sys_user.sql`
  - 建 `sys_user` 表(见设计 5.1)。
  - 插入初始 `admin`(占位哈希或留空,配合首启改密)。

### 依赖
- [ ] `backend/pom.xml` 增加 `io.jsonwebtoken:jjwt-api/impl/jackson`、`org.springframework.security:spring-security-crypto`。

### 代码(`backend/src/main/java/com/onedata/portal/`)
- [ ] `entity/SysUser.java`(镜像 `DolphinConfig` 注解范式,`password_hash` 加 `@JsonProperty(WRITE_ONLY)`)。
- [ ] `mapper/SysUserMapper.java`(extends `BaseMapper`,加按 username / external_id 查询)。
- [ ] `service/AuthService.java`:bcrypt 校验、失败计数 + 锁定、签发/解析会话 JWT。
- [ ] `service/JwtService.java`:HS256 签发/校验(读 `AUTH_JWT_SECRET` / `AUTH_JWT_ISSUER` / `AUTH_JWT_TTL`)。
- [ ] `filter/AuthenticationFilter.java`:非白名单校验 `odw_session` Cookie → 填 `UserContextHolder`;失败按 `auth.anonymous.enabled` 决定 401 / 匿名;`finally` 清理。
- [ ] `config/WebConfig.java`:注册 `AuthenticationFilter`(顺序在 CORS 之后);白名单可配置项 `auth.whitelist`。
- [ ] `controller/AuthController.java`:`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`。
- [ ] `aspect/AuthenticationAspect.java`:`@RequireAuth` 改为基于 `UserContextHolder` 判断(不再直接读外部头);新增 `role` 校验支持(`@RequireRole("admin")` 或 `@RequireAuth(role=...)`)。
- [ ] `application.yml`:`auth.anonymous.enabled` 默认 `false`(生产),开发可 `true`;`auth.session.ttl`、`auth.whitelist`。

### 验证
- [ ] 后端编译:`mvn -q -pl backend compile`(或仓库现用构建命令)。
- [ ] 针对 `AuthService` / `JwtService` 的单测(密码校验、锁定、JWT 签发解析、过期/篡改拒绝)。
- [ ] 手测:登录拿 Cookie → 带 Cookie 访问受保护接口 200 → 不带 401。

---

## 阶段 2:OAuth 可配置登录 + 前端

目标:管理员前端配置 OAuth;配置启用后登录页出现 OAuth 入口;授权码全流程打通。

### 数据库
- [ ] `V45__create_sys_oauth_config.sql`:建 `sys_oauth_config`,插入 `enabled=0` 占位行。

### 后端
- [ ] `entity/OAuthConfig.java`(`client_secret` 加 `@JsonProperty(WRITE_ONLY)`)、`mapper/OAuthConfigMapper.java`。
- [ ] `service/OAuthConfigService.java`:读/更新(secret 留空沿用旧值,参考 `DolphinConfigService` 对 token 的处理)、端点可达性测试。
- [ ] `service/OAuthLoginService.java`:拼 authorize URL + `state`、code 换 token、JWT 验签(jwks)或调 userinfo、upsert `sys_user`(`auth_source=oauth`、`external_id`)、签发会话。
- [ ] `controller/AuthController` 增:`GET /api/auth/oauth/config`(公开,仅 enabled+provider_name)、`GET /api/auth/oauth/authorize`、`GET /api/auth/oauth/callback`。
- [ ] `controller/OAuthConfigController.java`:`GET/PUT /api/admin/oauth/config`、`POST /api/admin/oauth/config/test`(`@RequireRole("admin")`)。
- [ ] 白名单加入 `/api/auth/oauth/{config,authorize,callback}`。

### 前端(`frontend/src/`)
- [ ] `views/LoginView.vue` + 路由 `/login`(置于 `Layout` 外公开路由);密码表单 + 条件 OAuth 按钮(读 `/api/auth/oauth/config`)。
- [ ] `router/index.js`:`beforeEach` 守卫,无会话跳 `/login`;`/auth/callback` 落地处理。
- [ ] `stores/auth.js`:`useAuthStore`(`currentUser`、`fetchMe`、`logout`)。
- [ ] `utils/request.js`:`withCredentials: true`;响应拦截 401 → 跳 `/login`。`api/nl2sql.js` / `api/dataagent.js` 同步加 `withCredentials`。
- [ ] `views/Layout.vue`:头部右侧用户区 + 退出登录。
- [ ] `views/settings/OAuthConfig.vue` + 在 `views/settings/ConfigurationManagement.vue` 增「OAuth 配置」Tab(镜像 `DolphinConfig.vue`);`api/settings.js` 增 OAuth 配置接口。

### 验证
- [ ] 前端:`nvm use` 后 `npm --prefix frontend run build`(或 lint)。
- [ ] 手测:未配置 OAuth → 登录页只有密码;配置并启用 → 出现 OAuth 按钮;走完 authorize→callback→落 Cookie→进首页。

---

## 阶段 3:dataagent-backend 身份校验落地

目标:收口 dataagent admin 裸奔缺口;业务路由灰度启用同一令牌校验。

### 代码(`dataagent/dataagent-backend/`)
- [ ] `requirements.txt` 增 `PyJWT`。
- [ ] 新增 `api/auth.py`:`verify_identity()` FastAPI 依赖,从 `odw_session` Cookie 或 `Authorization: Bearer` 取 JWT,用 `AUTH_JWT_SECRET` 校验(算法/issuer 与 Java 端一致),返回 `{user_id, username, role}`;`require_admin()` 依赖。
- [ ] `api/admin_routes.py`:为 `/settings` 等 admin 端点加 `Depends(require_admin)`(立即收口高危缺口)。
- [ ] `api/routes.py`:业务 NL2SQL 路由按开关 `DATAAGENT_REQUIRE_AUTH` 接 `Depends(verify_identity)`;`X-ODW-Client=widget` 分支保留匿名 widget 逻辑不变。
- [ ] `main.py`:CORS `allow_origins` 由 `*` 收敛为前端域(环境变量驱动)。
- [ ] 不改 `core/nl2sql_agent.py` 等通用运行时(遵循 skill-agnostic 约束)。

### 验证
- [ ] `pytest` 针对 `verify_identity` / `require_admin`(有效令牌通过、无/过期/篡改拒绝、admin 角色校验)。
- [ ] 冒烟(按 AGENTS.md 本地 smoke):无令牌调 admin → 401/403;带 admin 会话 Cookie → 通过;widget 头路径不受影响。

---

## 阶段 4:部署与配置收敛

- [ ] `deploy/.env.example` 增:`AUTH_JWT_SECRET`、`AUTH_JWT_ISSUER=opendataworks`、`AUTH_JWT_TTL=8h`、`ADMIN_INIT_PASSWORD`、`DATAAGENT_REQUIRE_AUTH`、前端域(CORS)。
- [ ] `deploy/` compose:三服务注入同一 `AUTH_JWT_SECRET`。
- [ ] 反向代理:前端 / Java 后端(`/api`)/ dataagent(`/api/v1/dataagent` 等)收敛到同一站点域,使 `odw_session` Cookie 同域可见。
- [ ] 生产 Cookie `Secure` 开启;CORS `Allow-Origin` 为具体域。
- [ ] 文档:更新 `docs/handbook/` 登录与认证说明;若改动公共 API/部署行为,同步相关文档。

---

## 端到端冒烟(完成判据)

- [ ] 密码登录全流程:登录 → Cookie → 访问受保护接口 → 退出 → 401。
- [ ] OAuth 全流程:配置启用 → authorize → 自研 OAuth 登录 → callback → 落 `sys_user` + Cookie → 进首页。
- [ ] 未配置 OAuth:登录页无 OAuth 按钮,仅密码可用。
- [ ] dataagent admin 端点:无令牌拒绝,admin 通过。
- [ ] dataagent 业务路由(开关开启):带会话通过;widget 头路径仍匿名可用。
- [ ] 失败锁定:连续错误口令触发锁定。

## 回滚

- 阶段 1-2 回滚:`auth.anonymous.enabled=true` + Filter 直通白名单可临时恢复匿名;DB 迁移为加表,不破坏既有数据。
- 阶段 3 回滚:`DATAAGENT_REQUIRE_AUTH=false` 关闭业务路由校验;admin 收口建议保留(安全修复)。
- 阶段 4 回滚:反代/CORS 配置可独立回退。

## 风险与缓解

- 直连拓扑下同域 Cookie 是关键前提 → 阶段 4 反代收敛先行验证;不满足则切 Bearer 注入。
- 现有大量 `@RequireAuth` 接口语义切换(外部头 → 平台会话)→ 阶段 1 用单测 + 手测覆盖代表性接口。
- 一次性开启 dataagent 业务校验可能阻断现有前端 → 用 `DATAAGENT_REQUIRE_AUTH` 灰度。
