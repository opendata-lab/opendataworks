# 认证

::: warning 当前状态
OpenDataWorks 当前默认开启匿名访问模式，本地开发无需登录。面向最终用户的基于角色的访问控制（RBAC）计划在后续版本实现。
:::

## 当前行为

- 前端默认以匿名用户访问后端 API，无需登录即可使用门户功能。
- 后端在开发与体验阶段不强制校验最终用户身份。
- **服务间调用**通过内部容器网络与服务令牌保护，不直接暴露给最终用户。

## 服务间令牌

虽然最终用户访问是匿名的，但内部组件之间的调用使用令牌进行保护。这些令牌在 `deploy/.env` 中配置：

| 变量 | 用途 |
| --- | --- |
| `AGENT_API_SERVICE_TOKEN` | 后端与 DataAgent 之间的服务调用令牌 |
| `AGENT_API_REQUIRE_PRIVATE_NETWORK` | 是否要求 Agent API 仅在私有网络内访问 |
| `PORTAL_MCP_FRONTDOOR_TOKEN` | 访问 Portal MCP 的前置令牌 |
| `PORTAL_MCP_FRONTDOOR_TOKEN_HEADER_NAME` | 携带该令牌的请求头名称（默认 `X-Portal-MCP-Token`） |

调用 Portal MCP 时，需在请求头中携带令牌，例如：

```http
X-Portal-MCP-Token: <PORTAL_MCP_FRONTDOOR_TOKEN>
```

::: tip 生产环境安全
生产部署时务必将上述令牌改为强随机值，并确保 DataAgent、Portal MCP 等服务不直接暴露到公网。详见[配置说明](/guide/configuration)。
:::

## 后续规划

后续版本将提供面向最终用户的 RBAC，届时本页面会补充：

- 登录与会话管理
- 令牌（如 API Key / JWT）的签发与校验
- 角色与权限模型

如对认证方案有需求或建议，欢迎通过 [GitHub Issues](https://github.com/opendata-lab/opendataworks/issues) 反馈。
