# 登录与认证

- 适用版本: 自 `V44` 迁移（`sys_user` 表）起
- 相关设计: `docs/design/2026-05-30-configurable-oauth-login-design.md`、`docs/design/2026-05-30-odw-auth-module-design.md`
- 相关计划: `docs/plans/2026-05-30-configurable-oauth-login-plan.md`
- 当前状态: **本地管理员密码登录已上线；OAuth 登录尚未实现**（登录页仅提供密码方式）

## 1. 功能概述

平台认证由独立 Maven 模块 `odw-auth/`（包 `com.onedata.auth.*`）提供，`backend` 通过依赖引入并自动装配：

- 本地用户名 + 密码登录（bcrypt 存储，连续失败 5 次锁定 15 分钟）
- 登录成功签发 HS256 会话 JWT，写入 **HttpOnly Cookie `odw_session`**（默认 8h，`SameSite=Lax`）
- Servlet Filter 默认拦截全部请求 + 白名单放行；无有效会话返回 `401`
- 前端路由守卫：未登录访问任意页面跳转 `/login`；任意请求返回 `401` 时统一跳登录页
- `@RequireAuth`（要求已登录）与 `@RequireRole("admin")`（要求角色）方法级注解，读取 `UserContextHolder`

## 2. 初始管理员账号

| 项 | 值 |
|---|---|
| 用户名 | `admin` |
| 初始密码 | `admin123` |
| 角色 | `admin` |

初始账号由 Flyway 迁移 `backend/src/main/resources/db/migration/V44__create_sys_user.sql` 直接写入数据库，无需环境变量。
**部署后请立即通过页面右上角「用户菜单 → 修改密码」更换初始密码**（新密码至少 8 位）。

账号锁定后无需人工干预，15 分钟后自动解锁；如需立即解锁，可将 `sys_user.locked_until` 置空。

## 3. 认证接口

均位于主后端（context-path `/api`），全部在 Filter 白名单内：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 入参 `{username, password}`；成功 `Set-Cookie: odw_session` 并返回用户信息 |
| POST | `/api/auth/logout` | 清除会话 Cookie |
| GET | `/api/auth/me` | 返回当前登录用户（未登录返回 401） |
| POST | `/api/auth/password` | 入参 `{oldPassword, newPassword}`；登录后修改密码 |

响应统一为 `{code, message, data}`；登录失败（口令错误/锁定/停用）返回 HTTP 200 + `code=400` 与原因。

## 4. 配置

`backend/src/main/resources/application.yml` 中 `odw.auth.*`，生产经 `deploy/.env`（模板 `deploy/.env.example`）注入：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `AUTH_JWT_SECRET` | `opendataworks-dev-jwt-secret-change-me` | 会话 JWT 共享密钥，**生产必须改为随机值**，长度不少于 32 字符 |
| `AUTH_JWT_ISSUER` | `opendataworks` | JWT 签发方 |
| `AUTH_JWT_TTL` | `8h` | 会话有效期 |
| `AUTH_COOKIE_SECURE` | `false` | HTTPS 入口时置 `true`，会话 Cookie 加 `Secure` |
| `AUTH_ANONYMOUS_ENABLED` | `false` | **临时回滚开关**：`true` 时无会话请求回退为匿名 admin（恢复登录上线前行为） |

### 白名单

Filter 默认放行以下路径（Ant 风格，匹配去掉 context-path 后的路径，可经 `odw.auth.whitelist` 覆盖）：

- `/auth/**`（登录相关接口）
- `/v1/health`（容器探针）
- `/v1/ai/**`（Agent API，自有 `X-Agent-Service-Token` 服务令牌机制）
- `/actuator/health`、`/error`

dataagent-backend（`/api/v1/nl2sql*`、`/api/v1/dataagent`）当前不经主后端转发，本期不受会话校验影响；widget 的 `X-ODW-*` 机制保持现状。

## 5. 会话令牌契约（为 dataagent 复用预留）

JWT claims（HS256，密钥 `AUTH_JWT_SECRET`）：

| claim | 含义 |
|---|---|
| `sub` | 平台用户 ID（`sys_user.id`） |
| `username` | 用户名 |
| `role` | `admin` / `user` |
| `auth_source` | `local` / `oauth` / `anonymous` |
| `iss` | `opendataworks` |
| `iat` / `exp` | 签发 / 过期时间 |

载体优先 Cookie `odw_session`，`Authorization: Bearer` 兜底。后续 dataagent-backend 按设计文档第 8 节用同密钥校验同一契约。

## 6. 回滚

- 配置回滚：`AUTH_ANONYMOUS_ENABLED=true` 后重启 backend，无会话请求回退为匿名 admin，前端登录页仍可用但不强制。
- 数据回滚：`V44` 仅新增 `sys_user` 表，不影响既有数据。
