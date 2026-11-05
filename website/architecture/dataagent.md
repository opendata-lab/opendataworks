# 智能查询接入

「智能查询」由 DataAgent 提供。DataAgent 是独立仓库 [opendataagent](https://github.com/opendata-lab/opendataagent) 中的通用智能体平台，单独部署，可以和 OpenDataWorks 不在同一台机器上。它的内部架构、会话 API 与 Widget SDK 见：

- [DataAgent 架构](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/architecture.md)
- [DataAgent 会话 API](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/api.md)
- [Widget 嵌入](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/widget.md)
- [DataAgent 部署](https://github.com/opendata-lab/opendataagent/blob/main/deploy/README.md)

## 两边如何连接

```text
浏览器 → Portal（Vue） → DataAgent（独立部署）
                        → Backend /api/mcp/ → 核心领域服务 → 数据源
```

- **门户内嵌**：主前端「智能查询」页加载 DataAgent 的 widget。widget 脚本和 DataAgent API 经门户 nginx 转发，
  转发规则写在 `deploy/docker/nginx/frontend.conf`（默认注释，填 DataAgent 地址后启用）。
- **平台能力**：DataAgent 通过普通 HTTP MCP 连接后端内置 `/api/mcp/`，调用 `odw_*` 工具查询元数据、执行只读 SQL、
  管理任务与工作流。平台专属知识与操作指南在仓库 `odw-skills/` 中，用 DataAgent 的 Skill 导入使用。
- **身份与权限**：MCP 使用 `Authorization: Bearer odw_sa_*` 认证平台 SERVICE 账号，令牌在管理中心签发。可选 `X-Agent-User-Id`（`local:<用户名>` 或 `<provider>:<sub>`）仅收窄只读权限：先用人的账号 `EXPLAIN`，再用 SERVICE 映射账号执行。物理 DDL 始终使用 SERVICE 自身映射账号；没有映射或原生权限即拒绝。智能体 ID 和旧 operator 请求头不参与认证。

具体步骤见仓库 `deploy/README.md` 的「接入 DataAgent」。
