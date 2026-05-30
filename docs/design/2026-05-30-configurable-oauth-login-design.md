# 可配置 OAuth 登录与统一身份认证设计

- 日期: 2026-05-30
- 状态: 草案 (Draft)
- 范围: `backend/`、`frontend/`,身份契约预留 `dataagent/dataagent-backend/`
- 变更规模: 中大型(跨前后端 + schema + 部署配置 + 服务间身份契约)

## 1. 背景与现状

OpenDataWorks 当前没有自有的登录与认证体系,身份完全依赖"上游网关注入请求头"的假设,本地实际以匿名方式运行。

现状要点(代码定位):

- 身份载体: `backend/.../context/UserContextHolder.java`,ThreadLocal 持有 `userId` / `username` / `oauthUserId`。
- 鉴权切面: `backend/.../aspect/AuthenticationAspect.java` + 注解 `backend/.../annotation/RequireAuth.java`。
  - `@Around` 拦截 `@RequireAuth` 方法,从请求头读取 `X-User-Id` / `X-Username` / `X-OAuth-User-Id`,写入 `UserContextHolder`,`finally` 中清理。
  - 支持匿名: `auth.anonymous.enabled` / `auth.anonymous.user-id` / `auth.anonymous.username`。
- 用户表: `platform_users`(`id` / `oauth_user_id` / `username` / `email`),实体 `PlatformUser.java`,无密码字段。
- 无 JWT 依赖、无登录端点、无 Spring Security。
- CORS: `backend/.../config/WebConfig.java` 中 `OncePerRequestFilter`,`Access-Control-Allow-Credentials: true` 已开启(Cookie 方案可用)。
- 前端: 无登录页、无路由守卫、无 user store(Pinia 已初始化但为空);`frontend/src/utils/request.js` 的 axios 无 401 处理、无 `withCredentials`。
- 动态配置范式: `dolphin_config` 一套(Entity + Service + Controller + Mapper),敏感字段用 `@JsonProperty(access = WRITE_ONLY)` 不回显;Flyway 迁移最新为 `V43`。

## 2. 问题

1. 平台缺少自有登录,无法在没有外部网关时保护任何接口。
2. 需要同时支持两种登录方式:
   - 本地用户名 + 密码(管理员一等公民,始终可用)。
   - OAuth2.0 单点登录(自研 OAuth 服务,授权码模式)。
3. OAuth 必须像 GitLab 一样**由管理员在前端动态配置**:配置且启用后登录页出现 OAuth 入口;未配置则只能密码登录。配置不能写死在环境变量里。
4. 认证结果需要能低成本扩展到 Java 后端全部接口,并进一步下发到 `dataagent-backend`,做到**一套身份契约多处复用**。

## 3. 目标与非目标

### 目标

- Java 后端成为认证权威:负责登录(密码 / OAuth)、签发会话、校验会话。
- OAuth 配置存库、管理员前端可管理,支持启用/停用开关。
- 默认拦截所有请求 + 小白名单放行(默认安全,新增接口自动受保护)。
- 复用现有 `UserContextHolder` 与 `@RequireAuth` 语义,避免重写下游业务取身份的方式。
- 定义统一身份令牌契约,使同一个令牌可被 Java 后端校验,并可向 `dataagent-backend` 透传校验。

### 非目标

- 本期不实现完整 RBAC 细粒度权限矩阵(仅区分 `admin` / `user` 两个角色)。
- 本期不实现多 OAuth Provider 并存(单 Provider 配置,表结构预留扩展)。
- 本期不在 `dataagent-backend` 落地强制校验,只交付身份契约与透传,在主后端侧完成对接点(详见第 8 节扩展设计)。
- 不实现 MFA、社会化登录聚合、SCIM 用户同步。

## 4. 总体方案

### 4.1 认证权威 + 统一身份令牌

Java 后端登录成功后签发一个 **后端自签 JWT(会话令牌)**,这是整个体系的"单一身份契约":

- 浏览器侧: JWT 放入 **HttpOnly + SameSite Cookie**(命名 `odw_session`),前端不接触令牌内容,天然防 XSS 窃取。
- 服务间: Java 后端调用 `dataagent-backend` 时,透传同一身份(见 8 节),`dataagent-backend` 用**相同密钥/公钥**校验同一令牌契约。

令牌声明(claims)契约:

| claim | 含义 | 示例 |
|---|---|---|
| `sub` | 平台用户 ID(对应 `sys_user.id`) | `"1024"` |
| `username` | 用户名 | `"alice"` |
| `role` | 角色 | `"admin"` / `"user"` |
| `auth_source` | 认证来源 | `"local"` / `"oauth"` |
| `oauth_user_id` | OAuth sub(本地用户为空) | `"sso-abc"` |
| `iss` | 签发方 | `"opendataworks"` |
| `iat` / `exp` | 签发/过期时间 | 默认有效期 8h,可配置 |

签名算法: 默认 `HS256` + 共享密钥 `AUTH_JWT_SECRET`(便于内部服务间同密钥校验)。预留 `RS256`(后端持私钥签发,各服务持公钥校验)作为后续更强隔离选项,不在本期实现。

### 4.2 两种登录方式

```
                         ┌────────────────────────────────────┐
                         │            Java 后端(认证权威)        │
  浏览器                  │                                      │
   │  POST /api/auth/login│  本地密码:bcrypt 校验 sys_user        │
   ├─────────────────────►│  ─────────────────────────────────► │
   │                      │  成功 → 签发会话 JWT → Set-Cookie     │
   │                      │                                      │
   │ GET /api/auth/oauth/authorize                               │
   ├─────────────────────►│  读 sys_oauth_config(enabled?)       │
   │  302 → 授权页          │  拼 authorize_url + state            │
   │◄─────────────────────┤                                      │
   │  (在自研 OAuth 服务登录) │                                     │
   │ GET /api/auth/oauth/callback?code&state                     │
   ├─────────────────────►│  code 换 token(调一次 OAuth)         │
   │                      │  验 JWT(jwks)或 调 userinfo          │
   │                      │  upsert sys_user(auth_source=oauth)  │
   │  302 → 前端 + Cookie   │  签发会话 JWT → Set-Cookie            │
   │◄─────────────────────┤                                      │
```

- 本地密码登录始终可用,与 OAuth 配置无关。
- OAuth 登录仅在 `sys_oauth_config.enabled = 1` 时可用;前端据 `GET /api/auth/oauth/config` 决定是否渲染 OAuth 按钮。

### 4.3 默认拦截 + 白名单

新增一个 servlet `Filter`(`AuthenticationFilter`,注册在 `WebConfig`,顺序在 CORS 之后):

```
请求进入
  → 命中白名单? → 放行
  → 取 odw_session Cookie(或 Authorization: Bearer 兜底)
  → 校验 JWT(签名/过期/issuer)
      → 通过: 解析 claims,写入 UserContextHolder(沿用现有字段)
      → 失败/缺失: 若 auth.anonymous.enabled 则匿名;否则返回 401 + 登录入口信息
```

白名单(可配置,默认):

```
/api/auth/login
/api/auth/oauth/authorize
/api/auth/oauth/callback
/api/auth/oauth/config        # 登录页判断是否显示 OAuth 按钮
/api/health, /actuator/health
# widget / agent 已有自有 token 机制的入口按现状保留(见 7.4)
```

与现有 `AuthenticationAspect` 的关系:Filter 负责"会话校验 + 填充 `UserContextHolder`";`@RequireAuth` 切面保留为"方法级强制要求已登录"的语义(从 `UserContextHolder` 而非外部请求头判断)。两者职责不重叠,详见 7.2。

## 5. 数据模型

### 5.1 `sys_user`(本地账号体系,管理员一等公民)

```sql
CREATE TABLE `sys_user` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username`        VARCHAR(128) NOT NULL COMMENT '登录用户名',
  `password_hash`   VARCHAR(255) NULL     COMMENT 'bcrypt 口令哈希;OAuth-only 用户可空',
  `nickname`        VARCHAR(128) NULL     COMMENT '显示名',
  `email`           VARCHAR(128) NULL     COMMENT '邮箱',
  `role`            VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT '角色 admin/user',
  `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  `auth_source`     VARCHAR(32)  NOT NULL DEFAULT 'local' COMMENT 'local/oauth',
  `external_id`     VARCHAR(255) NULL     COMMENT 'OAuth sub,本地账号为空',
  `failed_attempts` INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `locked_until`    DATETIME     NULL     COMMENT '锁定截止时间',
  `last_login_at`   DATETIME     NULL     COMMENT '最近登录时间',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_external_id` (`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台本地用户表';
```

- 迁移文件: `V44__create_sys_user.sql`。
- 与现有 `platform_users` 的关系:`platform_users` 偏 OAuth 画像(`oauth_user_id`),`sys_user` 是登录/密码/角色的权威。OAuth 用户登录时,`sys_user.external_id` 即对应 `platform_users.oauth_user_id`,二者通过 `external_id` 关联,本期不合并表(降风险)。
- 初始管理员:迁移中插入一条 `admin` 账号(口令哈希由部署初始化注入或首启强制改密,见 7.5),`role=admin`,`auth_source=local`。

### 5.2 `sys_oauth_config`(OAuth Provider 配置,管理员可管理)

```sql
CREATE TABLE `sys_oauth_config` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `enabled`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否启用 OAuth 登录',
  `provider_name`  VARCHAR(64)  NOT NULL DEFAULT 'SSO' COMMENT '登录按钮显示名',
  `client_id`      VARCHAR(255) NULL     COMMENT 'OAuth client_id',
  `client_secret`  VARCHAR(512) NULL     COMMENT 'OAuth client_secret(响应不回显)',
  `authorize_url`  VARCHAR(512) NULL     COMMENT '授权端点',
  `token_url`      VARCHAR(512) NULL     COMMENT '令牌端点',
  `userinfo_url`   VARCHAR(512) NULL     COMMENT '用户信息端点(opaque token 用)',
  `jwks_url`       VARCHAR(512) NULL     COMMENT 'JWKS 公钥端点(JWT 验签用)',
  `scopes`         VARCHAR(255) NOT NULL DEFAULT 'openid profile' COMMENT '请求 scope',
  `redirect_uri`   VARCHAR(512) NULL     COMMENT '回调地址',
  `user_id_field`  VARCHAR(64)  NOT NULL DEFAULT 'sub' COMMENT '用户唯一标识字段',
  `username_field` VARCHAR(64)  NOT NULL DEFAULT 'preferred_username' COMMENT '用户名字段',
  `default_role`   VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT 'OAuth 新用户默认角色',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OAuth Provider 配置表';
```

- 迁移文件: `V45__create_sys_oauth_config.sql`,插入一行 `enabled=0` 的占位配置。
- 单 Provider:本期固定取 `enabled=1` 的首行;表结构本身支持后续多行扩展(加 `provider_key` 唯一键即可)。
- `client_secret` 实体上加 `@JsonProperty(access = WRITE_ONLY)`,沿用 `DolphinConfig.token` 的隐藏范式。

## 6. 后端接口设计

### 6.1 认证接口 `AuthController`(前缀 `/api/auth`,全部白名单)

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 本地密码登录;bcrypt 校验 + 失败锁定;成功 `Set-Cookie odw_session` |
| POST | `/api/auth/logout` | 已登录 | 清 Cookie(可选维护服务端黑名单) |
| GET | `/api/auth/me` | 已登录 | 返回当前用户(id/username/role/auth_source) |
| GET | `/api/auth/oauth/config` | 公开 | 仅返回 `enabled` + `provider_name`(不含 secret),供登录页判断 |
| GET | `/api/auth/oauth/authorize` | 公开 | 读配置拼授权 URL + 下发 `state`,302 跳转 |
| GET | `/api/auth/oauth/callback` | 公开 | code 换 token → 验身份 → upsert 用户 → 签发会话 → 302 回前端 |

`state` 防 CSRF:`authorize` 生成随机 `state` 写入短时 Cookie(或服务端缓存),`callback` 校验一致。

### 6.2 OAuth 配置管理 `OAuthConfigController`(前缀 `/api/admin/oauth`,需 admin)

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/oauth/config` | 读当前配置(secret 不回显) |
| PUT | `/api/admin/oauth/config` | 更新配置;secret 留空表示沿用旧值(参考 `DolphinConfigService` 对 token 的保留逻辑) |
| POST | `/api/admin/oauth/config/test` | 校验 authorize/token/jwks 端点可达性 |

完整复刻 `DolphinConfig` 四件套:`OAuthConfig`(Entity)+ `OAuthConfigMapper` + `OAuthConfigService` + `OAuthConfigController`。

### 6.3 角色校验

- 新增注解 `@RequireRole("admin")` 或在现有 `@RequireAuth` 上加 `role` 属性,由切面读取 `UserContextHolder` 的 role 判定。OAuth 配置管理接口使用之。

## 7. 关键实现点

### 7.1 新增依赖

`backend/pom.xml` 增加:

- `io.jsonwebtoken:jjwt-api/impl/jackson`(签发与校验会话 JWT)。
- `org.springframework.security:spring-security-crypto`(仅取 `BCryptPasswordEncoder`,不引入完整 Security 过滤链,避免大改)。
- OAuth 端点调用复用现有 `WebClient`/`RestTemplate`(后端已有 WebFlux)。

### 7.2 Filter 与 Aspect 职责划分

- `AuthenticationFilter`(新增,servlet 层):对所有非白名单请求校验会话 JWT,成功则填充 `UserContextHolder`,失败按匿名开关决定 401 或匿名。`finally` 清理 ThreadLocal。
- `AuthenticationAspect`(保留,方法层):`@RequireAuth` 改为基于 `UserContextHolder` 现有内容判断"是否已认证",不再直接读外部请求头(请求头注入路径仅保留给受信任的内部服务间调用,见 7.4)。
- 迁移注意:现有大量 `@RequireAuth` 标注的接口语义不变(仍要求已登录),只是身份来源从"外部网关头"变为"本平台会话"。

### 7.3 会话 Cookie 策略

- `HttpOnly`、`SameSite=Lax`(同站前后端)、生产 `Secure`。
- 有效期默认 8h(`auth.session.ttl` 可配),可选滑动续期。
- 前端 axios 加 `withCredentials: true`;CORS 已 `Allow-Credentials: true`,需将 `Allow-Origin` 收敛为具体域(凭证模式不可用 `*`)——`WebConfig` 现已按请求 Origin 回填,符合要求。

### 7.4 既有内部 token 机制的兼容

- widget / agent 入口已有自有令牌(`X-ODW-*` / service token)。这些路径加入白名单或在 Filter 中前置识别,保持现状,不被会话校验影响。
- 服务间(Java → dataagent)身份透传是"受信任内部头"路径,与外部浏览器请求区分对待。

### 7.5 初始管理员与口令安全

- bcrypt 存储,登录失败计数 + `locked_until` 锁定(如 5 次锁 15 分钟)。
- 初始 admin 口令不硬编码进迁移:迁移插入占位 + 首次启动校验 `ADMIN_INIT_PASSWORD` 环境变量并强制改密;或部署脚本写入哈希。具体取部署侧约定(见计划文档)。

## 8. 扩展到 backend 与 dataagent-backend(核心扩展性设计)

### 8.1 实际调用拓扑(探查确认)

- 前端 **直连 dataagent-backend**(开发态经 `frontend/vite.config.js` 代理 `/api/v1/dataagent`、`/api/v1/nl2sql`、`/api/v1/nl2sql-admin` → `:8900`),**不经 Java 后端转发**;`/api` 才走 Java 后端 `:8080`。
- dataagent-backend 当前**完全无鉴权**:`main.py` CORS `allow_origins=["*"]`,`/api/v1/nl2sql-admin/settings` 等 admin 端点无任何校验(可读写 LLM provider、DB 凭证、skill 配置)——这是必须收口的安全缺口,也是本设计扩展到 dataagent 的直接动因。
- dataagent 现有 `X-ODW-*` 头仅用于 widget 分析画像(`source`/`website_id`/`visitor_id`),不是认证身份。

### 8.2 一套契约,三处复用

统一身份令牌契约(4.1)是扩展性的关键。因为前端直连两套后端,且两套在同站点下(开发经 vite 代理同源,生产经反代同域),**HttpOnly Cookie `odw_session` 会天然随请求带到 dataagent 路径**。由此:

1. **Java 后端自身**:`AuthenticationFilter` 校验会话 JWT → `UserContextHolder`。全部 `@RequireAuth` 接口零改造受益(白名单外默认受保护)。
2. **dataagent-backend(同密钥校验同一令牌)**:新增 FastAPI 依赖 `verify_identity()`,从 `odw_session` Cookie(或 `Authorization: Bearer` 兜底)取 JWT,用**同一 `AUTH_JWT_SECRET`** 校验同一 claims 契约(`PyJWT`),解析 `user_id` / `username` / `role` 挂到请求上下文。
   - 业务 NL2SQL 路由:启用身份校验(可经开关灰度)。
   - admin 路由(`/api/v1/nl2sql-admin/**`):强制 `role=admin`,直接关闭当前裸奔缺口。
   - widget 路径:保留现有 `X-ODW-*` 机制,与会话校验并行(widget 是匿名外嵌场景,不要求平台登录)。
3. **可选的服务间直调**:若未来出现 Java 后端 → dataagent 的服务端直调,透传 `Authorization: Bearer <会话JWT>` 即可,无需新机制。

> 约束(遵循 AGENTS.md):不在 `core/nl2sql_agent.py` 等通用运行时硬编码鉴权;`verify_identity()` 作为独立 FastAPI 依赖/中间件存在,保持通用运行时 skill-agnostic。dataagent 侧落地可分阶段:先收口 admin 端点(高危),再对业务路由灰度启用。

### 8.3 生产部署前提

- 生产环境需经反向代理把前端、Java 后端、dataagent-backend 收敛到**同一站点域**,使 `odw_session` Cookie 对三者同域可见;否则需改用 `Authorization: Bearer`(前端从受信渠道获取并注入,牺牲部分 HttpOnly 优势)。本设计默认同域 Cookie 方案,`deploy/` 反代配置需相应调整(见计划文档)。
- dataagent CORS 由 `*` 收敛为具体前端域(凭证模式要求)。

共享密钥配置(三处一致):

```
AUTH_JWT_SECRET   # Java 后端签发+校验、dataagent-backend 校验,同一值
AUTH_JWT_ISSUER=opendataworks
AUTH_JWT_TTL=8h
```

`deploy/` 的 env 模板与 compose 需新增以上变量(医疗级别隔离时再切 RS256 公私钥)。

## 9. 前端设计

- 新增 `LoginView.vue` + 路由 `/login`(置于 `Layout` 之外的公开路由)。
  - 用户名/密码表单;条件渲染"通过 {provider_name} 登录"按钮(读 `/api/auth/oauth/config`)。
  - OAuth 按钮跳 `/api/auth/oauth/authorize`。
- `router/index.js` 增 `beforeEach` 守卫:无会话(`/api/auth/me` 401)→ 跳 `/login`;处理 `/auth/callback` 回跳落地。
- Pinia 新增 `useAuthStore`:`currentUser`、`fetchMe()`、`logout()`。
- `utils/request.js`:加 `withCredentials: true`;响应拦截器对 401 统一跳 `/login`。
- `Layout.vue` 头部右侧加用户区(昵称 + 下拉「退出登录」)。
- 设置页 `ConfigurationManagement.vue` 新增 Tab「OAuth 配置」→ `views/settings/OAuthConfig.vue`(镜像 `DolphinConfig.vue` 表单/校验/API 调用范式,仅 admin 可见)。

## 10. 安全考量

- HttpOnly Cookie 防 XSS 窃取令牌;`SameSite=Lax` + `state` 防 CSRF。
- `client_secret` 永不回显(WRITE_ONLY);更新时留空沿用旧值。
- 默认拦截全部 + 小白名单,杜绝漏保护。
- 失败锁定防暴力破解;bcrypt 防拖库口令还原。
- 凭证模式下 CORS `Allow-Origin` 必须为具体域,不可 `*`。
- 共享密钥 `AUTH_JWT_SECRET` 走环境变量/密钥管理,不入库不入仓。

## 11. 待确认 / 风险

- 生产部署是否能把前端 / Java 后端 / dataagent 收敛到同一站点域(同域 Cookie 方案的前提)。若不能,dataagent 侧改用 Bearer 注入。
- dataagent 业务路由启用身份校验的节奏:admin 端点立即收口为高优先级;业务路由建议灰度开关,避免一次性阻断现有直连前端。
- 初始 admin 口令注入方式(env 强制改密 vs 部署脚本写哈希)——计划文档定。
- `platform_users` 与 `sys_user` 是否近期合并(本期不合并,保留风险点)。
- 会话失效与单点登出在多服务下的传播(本期 JWT 自然过期为主,主动登出黑名单为可选)。
- widget 匿名场景与平台登录场景在 dataagent 同一路由上的区分逻辑需明确(按 `X-ODW-Client=widget` 走 widget 分支,否则要求会话)。

## 12. 取舍

- 选 HttpOnly Cookie + 后端自签 JWT,而非前端持有 OAuth token:更安全、前端零令牌管理、服务间易透传;代价是后端需维护会话签发/校验,但 jjwt 成本低。
- 选共享密钥 HS256 而非 RS256:内部服务间最简,密钥同步一处;隔离要求升级时再换 RS256,契约不变。
- 复用 `UserContextHolder` + `@RequireAuth` 而非引入完整 Spring Security:最小改动、不破坏现有取身份方式;仅借 `BCryptPasswordEncoder` 一个工具类。
- OAuth 配置存库 + 前端管理而非环境变量:满足 GitLab 式动态配置诉求,运维无需重启改配置。
