# odw-auth 独立认证模块设计

- 日期: 2026-05-30
- 状态: 草案 (Draft)
- 配套功能设计: `docs/design/2026-05-30-configurable-oauth-login-design.md`(登录流程/表/接口的业务细节)
- 配套计划: `docs/plans/2026-05-30-configurable-oauth-login-plan.md`
- 本文聚焦: **把认证能力抽成可独立演进、可被多服务复用、可整体替换的库模块**
- 变更规模: 大型(新增 Maven 模块 + Python 包 + 既有代码迁移 + 自动装配契约)

## 1. 目标

把认证从"散落在 `backend` 里的切面 + 头解析"重构为一个**自包含、可插拔、可独立重构**的模块,满足:

- **单一职责**:认证(登录、会话签发/校验、身份上下文、OAuth 对接)全部收敛到一个模块。
- **可复用**:Java 侧任何 Spring Boot 服务(`backend`、`backend-agent-api`、未来新服务)引入即用;Python 侧 `dataagent-backend` 用同源 Python 包校验同一令牌。
- **可替换**:宿主服务通过依赖一行开关接入或移除;所有 Bean 可被覆盖,便于后续整体重构甚至拆成独立认证服务。
- **零侵入业务**:业务代码取身份的方式(`UserContextHolder` / `@RequireAuth`)保持稳定,实现细节在模块内演进。

## 2. 现状与动因

- 根工程已是 Maven Reactor `opendataworks-reactor`(`pom.xml`),聚合 `backend-agent-api` + `backend`,**已具备多模块基础**。
- 现有认证散落在 `backend`:
  - `context/UserContextHolder.java`(ThreadLocal:userId/username/oauthUserId)
  - `aspect/AuthenticationAspect.java`(从 `X-User-Id` 等请求头解析,假设上游网关)
  - `annotation/RequireAuth.java`
  - `application.yml` 的 `auth.anonymous.*` 匿名开关
- 问题:身份来源绑死"外部头注入";无登录/会话;无法被其他服务复用;重构牵动业务代码。

## 3. 模块边界与产物

新增**两个同源产物**,共享同一令牌契约:

| 产物 | 形态 | 服务对象 | 路径 |
|---|---|---|---|
| `odw-auth` | Maven JAR 模块(库,非可执行 Boot App) | 所有 Java Spring Boot 服务 | `odw-auth/` |
| `odw_auth` | Python 包 | `dataagent-backend` 及未来 Python 服务 | `dataagent/odw_auth/` |

> Java Maven 模块无法直接被 Python 复用,因此 Python 侧以**独立包 + 相同令牌契约**实现等价校验,而非跨语言共享代码。两者唯一耦合点是令牌契约(第 7 节),通过契约测试保证一致。

### 3.1 `odw-auth`(Maven 模块)目录

```
odw-auth/
├── pom.xml                         # packaging=jar;parent=spring-boot-starter-parent
└── src/main/
    ├── java/com/onedata/auth/
    │   ├── context/
    │   │   ├── UserContext.java            # userId / username / role
    │   │   └── UserContextHolder.java      # ThreadLocal,从 backend 迁入并简化
    │   ├── annotation/
    │   │   ├── RequireAuth.java            # 重写:基于会话判断
    │   │   └── RequireRole.java            # 新增:角色校验
    │   ├── aspect/
    │   │   └── AuthorizationAspect.java    # 读 UserContextHolder 做强制要求/角色校验
    │   ├── filter/
    │   │   └── AuthenticationFilter.java   # 校验会话 JWT → 填充 UserContextHolder
    │   ├── jwt/
    │   │   ├── JwtService.java             # 接口:issue / verify(自签会话)
    │   │   └── Hs256JwtService.java        # 默认实现(jjwt, HS256)
    │   ├── oauth/
    │   │   ├── OAuthClient.java            # 接口:authorizeUrl / exchangeCode / fetchIdentity
    │   │   └── DefaultOAuthClient.java     # WebClient + nimbus(JWKS)/ userinfo
    │   ├── user/
    │   │   ├── SysUser.java                # 实体
    │   │   ├── SysUserMapper.java
    │   │   └── SysUserService.java         # bcrypt 校验 / 锁定 / upsert OAuth 用户
    │   ├── config/
    │   │   ├── SysOAuthConfig.java         # 实体(client_secret WRITE_ONLY)
    │   │   ├── SysOAuthConfigMapper.java
    │   │   └── OAuthConfigService.java
    │   ├── web/
    │   │   ├── AuthController.java         # /api/auth/**
    │   │   └── OAuthConfigController.java  # /api/admin/oauth/**
    │   ├── spi/                            # 扩展点(见第 6 节)
    │   │   ├── UserResolver.java
    │   │   ├── SessionCookieCustomizer.java
    │   │   └── AuthWhitelistProvider.java
    │   └── autoconfigure/
    │       ├── OdwAuthProperties.java      # @ConfigurationProperties(prefix="odw.auth")
    │       └── OdwAuthAutoConfiguration.java
    └── resources/
        ├── META-INF/spring/
        │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
        └── db/migration/
            ├── V44__create_sys_user.sql
            └── V45__create_sys_oauth_config.sql
```

### 3.2 `odw_auth`(Python 包)目录

```
dataagent/odw_auth/
├── __init__.py
├── claims.py        # AuthClaims dataclass + 契约常量(iss/字段名)
├── verify.py        # decode_session(token, secret) -> AuthClaims;PyJWT
└── fastapi.py       # verify_identity / require_admin(FastAPI Depends)
```

## 4. 依赖与装配

### 4.1 Reactor 注册

`pom.xml`(根)`<modules>` 增加 `odw-auth`,置于 `backend` 之前:

```xml
<modules>
  <module>odw-auth</module>
  <module>backend-agent-api</module>
  <module>backend</module>
</modules>
```

### 4.2 宿主服务接入(以 backend 为例)

`backend/pom.xml` 增一条依赖即接入全部能力:

```xml
<dependency>
  <groupId>com.onedata</groupId>
  <artifactId>odw-auth</artifactId>
  <version>${project.version}</version>
</dependency>
```

### 4.3 Spring Boot 2.7 自动装配

`resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.onedata.auth.autoconfigure.OdwAuthAutoConfiguration
```

`OdwAuthAutoConfiguration`:
- `@EnableConfigurationProperties(OdwAuthProperties.class)`
- `@ComponentScan("com.onedata.auth")`(或显式 `@Bean` 注册,避免误扫)
- `@MapperScan("com.onedata.auth.user, com.onedata.auth.config")`
- 每个 Bean 加 `@ConditionalOnMissingBean` → **宿主可覆盖任意实现**
- `@ConditionalOnProperty("odw.auth.enabled", matchIfMissing=true)` → 总开关

### 4.4 Flyway 多路径

宿主 `application.yml`:

```yaml
spring:
  flyway:
    locations: classpath:db/migration   # backend 自身 + odw-auth 的迁移同一 classpath 路径合并扫描
```

> `odw-auth` 的迁移版本号 `V44/V45` 接在 `backend` 现有 `V43` 之后,确保全局单调。约定:**认证相关迁移版本段由 odw-auth 占用**,在模块文档登记,避免与 backend 后续迁移撞号(见第 10 节风险)。

## 5. 配置契约(`odw.auth.*`)

```yaml
odw:
  auth:
    enabled: true
    jwt:
      secret: ${AUTH_JWT_SECRET}        # 三服务同值
      issuer: opendataworks
      ttl: 8h
      algorithm: HS256                  # 预留 RS256
    session:
      cookie-name: odw_session
      same-site: Lax
      secure: true                      # 生产
    anonymous:
      enabled: false                    # 替代旧 auth.anonymous.*
    whitelist:                          # 默认拦截 + 白名单
      - /api/auth/**
      - /api/health
      - /actuator/health
```

环境变量(`deploy/`):`AUTH_JWT_SECRET` / `AUTH_JWT_ISSUER` / `AUTH_JWT_TTL`,Java 与 Python 服务共享。

## 6. 扩展点(SPI)—— 为后续重构预留

模块对外暴露接口,宿主或未来重构按需替换,**不改模块内部**:

| SPI | 用途 | 默认实现 | 重构场景 |
|---|---|---|---|
| `JwtService` | 会话签发/校验 | `Hs256JwtService` | 换 RS256 / 外部 KMS 签名 |
| `OAuthClient` | OAuth 协议交互 | `DefaultOAuthClient` | 换 OIDC discovery / 多 provider |
| `UserResolver` | OAuth 身份 → 平台用户 | 默认 upsert `sys_user` | 对接外部用户中心 / LDAP |
| `SessionCookieCustomizer` | Cookie 属性定制 | 默认 HttpOnly+Lax | 跨子域 / 自定义安全策略 |
| `AuthWhitelistProvider` | 动态白名单 | 读 `odw.auth.whitelist` | 运行时可变白名单 |

所有 SPI 在 `OdwAuthAutoConfiguration` 中以 `@ConditionalOnMissingBean` 注册,宿主声明同类型 Bean 即覆盖。

## 7. 令牌契约(Java ↔ Python 唯一耦合点)

会话 JWT claims(两侧实现必须一致):

| claim | 类型 | 含义 |
|---|---|---|
| `sub` | string | 平台用户 ID(`sys_user.id`) |
| `username` | string | 用户名 |
| `role` | string | `admin` / `user` |
| `auth_source` | string | `local` / `oauth` |
| `iss` | string | `opendataworks` |
| `iat` / `exp` | number | 签发 / 过期 |

- 算法:`HS256`,密钥 `AUTH_JWT_SECRET`。
- 载体:HttpOnly Cookie `odw_session`;`Authorization: Bearer` 作兜底。
- **一致性保证**:`odw-auth` 与 `odw_auth` 各自维护一份契约测试(同一 token 双向 encode/decode),CI 中用固定向量校验,任一侧改契约必须同步另一侧(对应 AGENTS.md "改契约同改测试")。

## 8. 宿主消费方式

### 8.1 backend(Java)

- 删除旧 `context/UserContextHolder`、`aspect/AuthenticationAspect`、`annotation/RequireAuth`、`auth.anonymous.*`。
- 业务代码 `import com.onedata.auth.context.UserContextHolder` / `com.onedata.auth.annotation.RequireAuth`(包名变更,做一次性 import 替换)。
- 取身份方式不变:`UserContextHolder.getCurrentUserId()`。
- `@RequireAuth` 语义升级为"要求已登录会话";`@RequireRole("admin")` 用于管理端点。

### 8.2 dataagent-backend(Python)

```python
from odw_auth.fastapi import verify_identity, require_admin

@admin_router.put("/settings", dependencies=[Depends(require_admin)])
async def update_admin_settings(...): ...

@router.post("/nl2sql/...", )
async def ask(claims = Depends(verify_identity)): ...  # 按开关 DATAAGENT_REQUIRE_AUTH 灰度
```

- 同域 Cookie(`frontend/nginx.conf` 单域)→ `odw_session` 自动随请求到达,`verify.py` 用同密钥校验。
- widget 路径(`X-ODW-Client=widget`)保留匿名分支,不强制会话。

### 8.3 backend-agent-api(Java)

暂不改;未来需要保护时,加 `odw-auth` 依赖即可,零额外代码。

## 9. 既有代码迁移

| 旧位置(backend) | 处置 | 新位置(odw-auth) |
|---|---|---|
| `context/UserContextHolder` | 迁移 + 简化(去 oauthUserId) | `com.onedata.auth.context.UserContextHolder` |
| `context/UserContext` | 迁移 + 加 role | 同包 |
| `aspect/AuthenticationAspect` | 重写(读会话非外部头) | `aspect/AuthorizationAspect` |
| `annotation/RequireAuth` | 重写 | `annotation/RequireAuth` |
| `application.yml auth.anonymous.*` | 删除,迁到 `odw.auth.anonymous` | `OdwAuthProperties` |
| 业务里 `import ...portal.context...` | 全量替换 import | `...auth.context...` |

迁移用一次性脚本批量改 import,编译驱动找残留。

## 10. 测试与验证

- `odw-auth` 单测:`JwtService`(签发/过期/篡改)、`SysUserService`(bcrypt/锁定)、`OAuthClient`(mock token/jwks/userinfo)、`AuthenticationFilter`(白名单/401/匿名)。
- `odw_auth` 单测:`verify.py` 用与 Java 相同的固定 token 向量。
- **跨语言契约测试**:Java 签一个 token,Python 解出相同 claims(固定向量入仓)。
- 接入冒烟:`backend` 引入模块后启动、登录、受保护接口 200/401;`dataagent` admin 端点收口。

## 11. 风险与缓解

- **Flyway 版本撞号**:`odw-auth` 与 `backend` 共享 classpath 迁移路径,版本号需全局协调。缓解:在模块文档登记认证占用的版本段,新增迁移前先查最大版本。
- **`@ComponentScan` 误扫**:模块包名独立 `com.onedata.auth.*`,与宿主 `com.onedata.portal.*` 隔离,避免重复扫描;优先显式 `@Bean` 注册降低风险。
- **import 大面积变更**:一次性脚本 + 编译校验;在独立分支 `claude/oauth-login` 上完成,降低对其他分支干扰。
- **跨语言契约漂移**:固定向量契约测试 + 改契约同改两侧的硬约束。
- **MyBatis-Plus / Flyway 版本一致性**:模块继承同一 `spring-boot-starter-parent` 与 BOM,版本随 reactor 统一。

## 12. 重构友好性(本设计的核心收益)

- 换算法 / 密钥源 → 仅替换 `JwtService` Bean。
- 换 OAuth 协议栈(OIDC discovery、多 provider)→ 仅替换 `OAuthClient` / `OAuthConfigService`。
- 对接外部用户中心 → 实现 `UserResolver`。
- 抽成独立认证微服务 → `odw-auth` 加 `@SpringBootApplication` 独立启动,契约不变,其他服务改为远程校验。
- 整体替换认证方案 → 移除 `odw-auth` 依赖换新库,业务仅需重配 Bean。

## 13. 取舍

- **JAR 库 + 自动装配** 而非"复制代码到各服务":单一真源,升级一处生效。
- **Java 模块 + Python 包双产物** 而非强行跨语言共享:尊重技术栈边界,仅用令牌契约耦合(AGENTS.md:保持模块边界清晰)。
- **SPI + ConditionalOnMissingBean** 而非硬编码实现:为重构预留替换点,不牺牲开箱即用。
- **不引入完整 Spring Security**:贴合现有 `UserContextHolder`/`@RequireAuth`,只借 `BCryptPasswordEncoder`,改动面可控。
