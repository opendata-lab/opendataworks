# 智能查询接入

「智能查询」由 DataAgent 提供。DataAgent 是独立仓库 [opendataagent](https://github.com/opendata-lab/opendataagent) 中的通用智能体平台，单独部署，可以和 OpenDataWorks 不在同一台机器上。它的内部架构、会话 API 与 Widget SDK 见：

- [DataAgent 架构](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/architecture.md)
- [DataAgent 会话 API](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/api.md)
- [Widget 嵌入](https://github.com/opendata-lab/opendataagent/blob/main/docs/handbook/widget.md)
- [DataAgent 部署](https://github.com/opendata-lab/opendataagent/blob/main/deploy/README.md)

## 两边如何连接

```
浏览器 ──► OpenDataWorks 门户 nginx ──► DataAgent（/dataagent/widget/、/api/v1/nl2sql/ 等）
                                           │
                                           │ HTTP MCP：X-ODW-MCP-Token + X-Agent-User-Id / X-Agent-Id
                                           ▼
                                   odw-mcp-server :8801 ──► backend-agent-api ──► 数据源
```

- **门户内嵌**：主前端「智能查询」页加载 DataAgent 的 widget。widget 脚本和 DataAgent API 经门户 nginx 转发，
  转发规则写在 `deploy/docker/nginx/frontend.conf`（默认注释，填 DataAgent 地址后启用）。
- **平台能力**：DataAgent 通过普通 HTTP MCP 连接 `odw-mcp-server`，调用 `odw_*` 工具查询元数据、执行只读 SQL、
  管理任务与工作流。平台专属知识与操作指南在仓库 `odw-skills/` 中，用 DataAgent 的 Skill 导入使用。
- **身份与权限**：DataAgent 在每次 MCP 调用中带上登录用户（`local:<用户名>` 或 `<provider>:<sub>`）和智能体 ID。
  平台把人和智能体分别映射到数据源上的数据库账号：先用人的账号 `EXPLAIN`，再用智能体的账号执行，
  能访问的数据是两者权限的交集，没有映射即拒绝。

具体步骤见仓库 `deploy/README.md` 的「接入 DataAgent」。
