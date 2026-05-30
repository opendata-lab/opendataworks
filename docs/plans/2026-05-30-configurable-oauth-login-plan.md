# 可配置 OAuth 登录与统一身份认证 实施计划

- 日期: 2026-05-30
- 配套设计:
  - `docs/design/2026-05-30-configurable-oauth-login-design.md`(登录流程/表/接口业务细节)
  - `docs/design/2026-05-30-odw-auth-module-design.md`(独立 `odw-auth` 模块架构)
- 涉及栈: `odw-auth/`(新模块)、`backend/`、`frontend/`、`dataagent/`(`odw_auth` 包 + dataagent-backend 接入)、`deploy/`(env + 反代)

## 阶段划分

按风险与依赖分五阶段,可分 PR 交付。阶段 0 立模块骨架;阶段 1-2 让平台具备登录与保护能力;阶段 3 收口 dataagent;阶段 4 部署收敛。

---

## 阶段 0:建立 odw-auth 独立模块骨架

目标:Reactor 注册新模块,自动装配可空跑接入。

- [ ] `odw-auth/pom.xml`:`packaging=jar`,parent=`spring-boot-starter-parent`,依赖 jjwt / nimbus-jose-jwt / spring-security-crypto / mybatis-plus / spring-boot-starter-web+aop+webflux。
- [ ] 根 `pom.xml` `<modules>` 增加 `odw-auth`(置于 `backend` 前)。
- [ ] `autoconfigure/OdwAuthProperties.java`(`@ConfigurationProperties("odw.auth")`)+ `OdwAuthAutoConfiguration.java`。
- [ ] `resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- [ ] 迁移旧代码进模块:`UserContext` / `UserContextHolder`(简化去 oauthUserId,加 role)。
- [ ] `backend/pom.xml` 增 `odw-auth` 依赖;删除 backend 旧 `context/UserContextHolder`、`aspect/AuthenticationAspect`、`annotation/RequireAuth`、`auth.anonymous.*`;批量改 import。
- [ ] 验证:`mvn -q -pl odw-auth,backend -am compile` 通过;backend 启动不报装配错误。

---

## 阶段 1:认证核心(密码登录 + 会话 + 默认拦截)

目标:平台具备本地密码登录、会话签发/校验、默认拦截全部 + 白名单。所有代码落在 `odw-auth`。

### 数据库
- [ ] `backend/src/main/resources/db/migration/V44__create_sys_user.sql`
  - 建 `sys_user` 表(见设计 5.1)。
  - 直接插入初始 `admin`:`role=admin`、`auth_source=local`、`password_hash` = `admin123` 的 bcrypt 哈希(写死在迁移里)。

### 依赖
- [ ] `backend/pom.xml` 增加 `io.jsonwebtoken:jjwt-api/impl/jackson`、`org.springframework.security:spring-security-crypto`。

### 代码(`backend/src/main/java/com/onedata/portal/`)
(以下类均在 `odw-auth` 模块 `com.onedata.auth.*` 包内)

- [ ] `user/SysUser.java`(镜像 `DolphinConfig` 注解范式,`password_hash` 加 `@JsonProperty(WRITE_ONLY)`)+ `user/SysUserMapper.java`(按 username / external_id 查询)。
- [ ] `user/SysUserService.java`:bcrypt 校验、失败计数 + 锁定、upsert OAuth 用户。
- [ ] `jwt/JwtService.java`(接口)+ `jwt/Hs256JwtService.java`:HS256 签发/校验(读 `odw.auth.jwt.*`)。
- [ ] `filter/AuthenticationFilter.java`:非白名单校验 `odw_session` Cookie → 填 `UserContextHolder`;失败按 `odw.auth.anonymous.enabled` 决定 401 / 匿名;`finally` 清理。
- [ ] `web/AuthController.java`:`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me`、`POST /api/auth/password`(登录后改密)。
- [ ] `annotation/RequireAuth`(重写,基于 `UserContextHolder`)+ `annotation/RequireRole` + `aspect/AuthorizationAspect`。
- [ ] `autoconfigure/OdwAuthAutoConfiguration`:注册 Filter(顺序在 CORS 之后)、各 Bean 加 `@ConditionalOnMissingBean`;白名单来自 `OdwAuthProperties`。
- [ ] `db/migration/V44__create_sys_user.sql`(建表 + 写死 `admin123` 的 bcrypt 哈希初始 admin)。

### 验证
- [ ] 编译:`mvn -q -pl odw-auth,backend -am compile`。
- [ ] `odw-auth` 单测:`Hs256JwtService` / `SysUserService` / `AuthenticationFilter`(密码校验、锁定、JWT 签发解析、过期/篡改拒绝、白名单/401)。
- [ ] 手测:backend 引入模块后,登录拿 Cookie → 带 Cookie 访问受保护接口 200 → 不带 401。

---

## 阶段 2:OAuth 可配置登录 + 前端

目标:管理员前端配置 OAuth;配置启用后登录页出现 OAuth 入口;授权码全流程打通。

### 数据库(odw-auth 模块)
- [ ] `db/migration/V45__create_sys_oauth_config.sql`:建 `sys_oauth_config`,插入 `enabled=0` 占位行。

### 后端(odw-auth 模块 `com.onedata.auth.*`)
- [ ] `config/SysOAuthConfig.java`(`client_secret` 加 `@JsonProperty(WRITE_ONLY)`)、`config/SysOAuthConfigMapper.java`。
- [ ] `config/OAuthConfigService.java`:读/更新(secret 留空沿用旧值,参考 `DolphinConfigService` 对 token 的处理)、端点可达性测试。
- [ ] `oauth/OAuthClient.java`(接口)+ `oauth/DefaultOAuthClient.java`:拼 authorize URL + `state`、code 换 token、JWKS 验签(nimbus)或调 userinfo;`user/UserResolver` upsert `sys_user`(`auth_source=oauth`、`external_id`)。
- [ ] `web/AuthController` 增:`GET /api/auth/oauth/config`(公开,仅 enabled+provider_name)、`GET /api/auth/oauth/authorize`、`GET /api/auth/oauth/callback`。
- [ ] `web/OAuthConfigController.java`:`GET/PUT /api/admin/oauth/config`、`POST /api/admin/oauth/config/test`(`@RequireRole("admin")`)。
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

### 代码(`dataagent/odw_auth/` 包 + `dataagent/dataagent-backend/`)
- [ ] 新增 Python 包 `dataagent/odw_auth/`:`claims.py`(契约)、`verify.py`(PyJWT 校验,与 Java 同契约/向量)、`fastapi.py`(`verify_identity` / `require_admin` 的 Depends)。
- [ ] `dataagent-backend/requirements.txt` 增 `PyJWT`,并依赖本地 `odw_auth` 包。
- [ ] `api/admin_routes.py`:为 `/settings` 等 admin 端点加 `Depends(require_admin)`(立即收口高危缺口),从 `odw_auth.fastapi` 导入。
- [ ] `api/routes.py`:业务 NL2SQL 路由按开关 `DATAAGENT_REQUIRE_AUTH` 接 `Depends(verify_identity)`;`X-ODW-Client=widget` 分支保留匿名 widget 逻辑不变。
- [ ] `main.py`:CORS `allow_origins` 由 `*` 收敛为前端域(环境变量驱动)。
- [ ] 不改 `core/nl2sql_agent.py` 等通用运行时(遵循 skill-agnostic 约束)。

### 验证
- [ ] `pytest` 针对 `verify_identity` / `require_admin`(有效令牌通过、无/过期/篡改拒绝、admin 角色校验)。
- [ ] 冒烟(按 AGENTS.md 本地 smoke):无令牌调 admin → 401/403;带 admin 会话 Cookie → 通过;widget 头路径不受影响。

---

## 阶段 4:部署与配置收敛

- [ ] `deploy/.env.example` 增:`AUTH_JWT_SECRET`、`AUTH_JWT_ISSUER=opendataworks`、`AUTH_JWT_TTL=8h`、`DATAAGENT_REQUIRE_AUTH`、前端域(CORS)。(无需 `ADMIN_INIT_PASSWORD`,初始口令已存库)
- [ ] `deploy/docker-compose.prod.yml`:为 `backend` 与 `dataagent-backend` 注入同一 `AUTH_JWT_SECRET`。
- [ ] 反向代理:**已就绪**。`frontend/nginx.conf` 已把 `/`、`/api/`、`/api/v1/{dataagent,nl2sql,nl2sql-admin}/` 收敛到单域,`odw_session` Cookie 自动同域可见,无需改动路由;仅需确认 cookie 透传不被 nginx 剥离。
- [ ] 生产 Cookie `Secure` 开启;dataagent CORS `Allow-Origin` 由 `*` 改为具体域。
- [ ] 文档:更新 `docs/handbook/` 登录与认证说明(含默认 admin 口令 `admin123` 与首登改密提示);若改动公共 API/部署行为,同步相关文档。

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

- 同域 Cookie 前提已由 `frontend/nginx.conf` 单域反代满足,风险消除;仅需确认 nginx 不剥离 cookie。
- 现有大量 `@RequireAuth` 接口语义切换(外部头 → 平台会话)→ 阶段 1 用单测 + 手测覆盖代表性接口。
- 一次性开启 dataagent 业务校验可能阻断现有前端 → 用 `DATAAGENT_REQUIRE_AUTH` 灰度。
