# MCP 可用性检测设计

日期：2026-09-27

## Current state

DataAgent MCP 管理页可以创建、编辑、启停和删除 HTTP、SSE、stdio 服务，运行时由 Pi Cell
使用官方 MCP TypeScript SDK 建立连接并发现工具。管理页目前只展示静态配置，管理员无法在把服务
挂到智能体之前确认 URL、Header、命令、协议握手和工具发现是否正常。

## Problem

仅检查端口或 HTTP 状态码不能证明 MCP 可用：服务可能能连通，但认证失败、MCP initialize 失败，
或 `tools/list` 不可用。直接发起一次完整智能问答又会引入模型配置、任务调度和提示词等无关变量，
无法快速定位 MCP 配置问题。

## Scope

- 为已保存的 configured/plugin MCP 服务提供管理员可执行的即时检测。
- 使用与生产 Pi runtime 相同的 MCP SDK transport，覆盖 HTTP、SSE 和 stdio。
- 检测执行完整的 connect/initialize 与 `tools/list`，返回状态、工具数、工具名、耗时和检查时间。
- 在 MCP 列表显示“检测/重新检测”入口及本次页面会话的结果。

本次不新增数据库字段，不持久化检测历史，也不在检测时调用任何业务工具。

## Interfaces

新增管理员接口：

`POST /api/v1/dataagent/mcp/servers/{server_id}/detections`

成功建立 MCP 会话并完成工具发现时返回：

```json
{
  "server_id": "portal",
  "status": "verified",
  "message": "MCP 检测通过，发现 6 个工具",
  "tool_count": 6,
  "tool_names": ["portal_search_tables"],
  "latency_ms": 123,
  "checked_at": "2026-09-27T12:00:00Z"
}
```

连接、认证、协议或超时失败仍返回 200，`status=failed` 并携带经过截断的可读错误；资源不存在返回
404。这样 UI 可以稳定展示检测结论，而网络/API 本身的失败仍按异常处理。

## Runtime flow

FastAPI 从 registry 读取完整配置，通过 stdin 把单个服务配置交给
`dataagent-runtime-pi/dist/src/mcp/probe-cli.js`。CLI 使用现有 `createMcpTransport` 和官方 MCP SDK
Client 执行 connect + `tools/list`，完成后关闭连接并只向 stdout 输出一条 JSON 结果。Header/env
不进入命令行参数和日志。

检测总时限为 10 秒，Python 外层另设稍长的进程兜底时限；超时后终止 probe 进程，防止失联的
SSE 或 stdio 服务占住后端请求。

## Security and tradeoffs

- 接口沿用 `skills_router` 的管理员鉴权，因为检测会使用保存的密钥，并可能启动 stdio 命令。
- stdio 命令本就是管理员可配置、生产运行时会执行的能力；检测不扩大非管理员权限。
- 使用生产同款 SDK 比自写 HTTP/SSE 握手更可靠，并避免检测通过而实际 Pi runtime 不可用。
- 不持久化检测状态可避免“很久以前检测成功”被误认为当前健康；刷新后回到待检测状态。

## Rollout and backout

无 schema 迁移。发布时后端镜像照常构建 Pi runtime，新 CLI 会随 `dist` 和 Node runtime 一起进入
镜像。回退只需回退前后端代码，不影响已保存的 MCP 配置。
