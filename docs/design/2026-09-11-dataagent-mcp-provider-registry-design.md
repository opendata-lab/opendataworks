# DataAgent MCP 与模型供应商注册表设计

日期：2026-09-11

## Current state

DataAgent 设置页已经按独立资源调用两组接口，但后端尚未提供对应路由：

- MCP 页面调用 `/api/v1/dataagent/mcp/servers` 的列表、创建、更新、删除和导入接口。
- 模型页调用 `/api/v1/nl2sql-admin/providers` 的列表、创建、更新和删除接口。

模型配置目前聚合在 `da_agent_settings(settings_key='default')` 的列和
`raw_json.provider_settings` 中。服务可以在一段 JSON 里表达多个预置 provider，但没有独立
provider 资源，provider id 也被限制在四个内置值，无法真正创建或删除自定义供应商。
`da_agent_settings` 同时承载数据源、Skill 和 widget 等全局设置，不能直接改成“每个 provider
一行”。

MCP 没有持久化模型。`portal` MCP 由 `DATAAGENT_PORTAL_MCP_*` 环境变量直接拼入
Claude/Pi 运行参数，agent 能力目录也从同一组环境变量推导。Pi wire contract 只包含远程
URL 和 headers，stdio 配置即使由页面创建也无法执行。

## Problem

只补 CRUD 会留下两种假完成：

1. MCP 页面能保存配置，但 agent capability 和实际 Claude/Pi 运行仍读取环境变量。
2. provider 页面能显示多项，但实际运行仍从 `da_agent_settings.raw_json` 的固定四项解析。

此外，升级不能丢失现有部署的默认 provider/model、凭据、模型列表和 portal MCP 配置；
插件提供的 MCP 必须可展示和执行，但不能通过管理接口编辑或删除。

## Scope

本次涉及：

- DataAgent MySQL schema 与 Alembic 迁移；
- FastAPI 管理接口、Pydantic 契约和存储/服务层；
- agent capability、Claude runtime、Pi runtime 的 MCP 解析；
- Pi Cell 的 HTTP、SSE、stdio MCP wire contract；
- DataAgent Vue 设置页的接口 method 对齐和小范围层级收尾；
- 后端、Pi runtime、前端的契约与回归测试，以及可用环境下的真实本地 smoke。

不在本次删除 `da_agent_settings` 的历史 provider 字段。它们继续作为全局当前选择和旧版本
回退数据，但新版本的 provider 运行主路径以 registry 为准。

## Data model

### `da_model_provider`

一行对应一个 provider：

- `provider_id`：稳定主键；允许内置 id 和 `custom_provider_*` 等自定义 id。
- `provider_type`：运行时适配器，取 `anthropic`、`openrouter`、`anyrouter`、
  `anthropic_compatible`；自定义 provider 默认使用 `anthropic_compatible`。
- `display_name`、`provider_group`。
- `base_url`、`api_key`、`auth_token`。
- `provider_enabled`、`supports_partial_messages`。
- `enabled_models_json`、`custom_models_json`、`models_json`、`model_detections_json`。
- `validation_status`、`validation_message`、`validated_at`、创建/更新时间。

`da_agent_settings.provider_id/model_name` 继续表示当前生效选择。删除当前 provider 时，服务在
同一事务/操作中把选择切换到首个可用 provider/model；没有可用项时清空两者。运行时解析先按
请求指定 id 查 registry，未指定时使用这两个指针。

迁移读取现有 `da_agent_settings.raw_json.provider_settings`（兼容旧的 `providers` 数组），逐项
写入新表；若 JSON 中没有当前 provider，则用旧行的 provider/model/credential 列补出一行。
旧行和原始 JSON 不删除、不改写，保证回退时配置仍在。

### `da_mcp_server`

一行对应一个 MCP server：

- `server_id`：稳定主键；名称另有唯一索引。
- `name`、`source`（`configured` 或 `plugin`）、`transport`（`http`、`sse`、`stdio`）。
- `url`、`headers_json`、`command`、`args_json`、`env_json`。
- `enabled`、`oauth_required`、`tool_count`、`description`、创建/更新时间。

管理写接口只允许 `source=configured`。`plugin` 行允许列表和运行时读取，PATCH/DELETE 一律
返回 400，避免页面或调用方把平台能力改坏。

现有 portal MCP 无法在纯 SQL Alembic 迁移中读取进程环境，因此由应用启动 bootstrap
执行一次 `insert-if-absent`：把 `DATAAGENT_PORTAL_MCP_*` 转成 `server_id='portal'` 的
`plugin/http` 行。写入成功后，agent capability 和运行时只读 `da_mcp_server`，不再把环境变量
作为运行时 fallback。环境变量只承担一次性升级导入，数据库里显式禁用或修改后的值不会在
重启时被覆盖。

## Interfaces

### MCP

- `GET /api/v1/dataagent/mcp/servers` → `{configured: [...], plugin: [...]}`
- `POST /api/v1/dataagent/mcp/servers` → `{server_id}`
- `PATCH /api/v1/dataagent/mcp/servers/{server_id}` → `{ok: true}`
- `DELETE /api/v1/dataagent/mcp/servers/{server_id}` → `{ok: true}`
- `POST /api/v1/dataagent/mcp/servers/import` → `{imported: n}`

导入接受 `{ "name": {...} }` 和 `{ "mcpServers": { "name": {...} } }`。同一 payload 中任一
server 非法时整批失败；名称重复按稳定 `server_id` 更新 configured 行，不覆盖 plugin 行。

### Providers

- `GET /api/v1/nl2sql-admin/providers` → `{providers: [...]}`
- `POST /api/v1/nl2sql-admin/providers` → `{provider_id}`
- `PUT /api/v1/nl2sql-admin/providers/{provider_id}` → `{ok: true}`
- `DELETE /api/v1/nl2sql-admin/providers/{provider_id}` → `{ok: true}`

`getSettings`/`updateSettings`/`detectModel` 保持现有 URL。`getSettings.providers` 从 registry
生成；`updateSettings.providers` 作为现有客户端的过渡写入口继续 upsert registry，但不再写
`raw_json.provider_settings`。provider 响应不返回明文 credential，只返回 `api_key_set` 与
`auth_token_set`。

## Runtime flow

```text
admin API -> MySQL registries
                 |-> provider selection -> task execution -> provider env
                 `-> agent mcp_server_ids -> enabled MCP rows -> Claude/Pi config
```

MCP 构建规则：

- 只挂载 agent snapshot 选中的、数据库中存在且 enabled 的 server。
- 未提供 agent snapshot（`mcp_server_ids is None`）时保留历史默认值 `portal`；snapshot
  显式给出空列表时表示不挂载任何 MCP，不能再回退到 `portal`。
- `portal` server 额外注入 `X-Agent-Data-Scope`；其它 server headers 原样使用。
- HTTP URL 只对 portal 的 Starlette mount 补尾斜杠，其它远程 URL 不擅自改写。
- Claude SDK 使用原生 http/sse/stdio 配置。
- Pi Cell wire contract 同时传递 URL/headers 与 command/args/env，并按 transport 创建
  `StreamableHTTPClientTransport`、`SSEClientTransport` 或 `StdioClientTransport`。

## Validation and error semantics

- provider/server id、名称、URL、transport 和 command 在服务层统一校验。
- HTTP/SSE 必须有 URL；stdio 必须有 command。
- JSON 字段只接受对应 object/list，避免字符串在运行时晚失败。
- 不存在资源返回 404；重复 id/name、试图修改 plugin、删除当前唯一可用 provider 等业务错误
  返回 400，并给出明确 detail。
- 存储失败不回落到环境变量或旧 JSON；运行时得到空 MCP/无 provider 时按现有显式失败语义处理。

## Migration, rollout and backout

上线顺序为：先执行 Alembic 建表和迁移 provider，再启动新应用完成 portal bootstrap。新应用在
registry 不可用时不会伪装成功。发布后检查当前 provider/model 指针可在 registry 中解析，并
检查 `portal` 行存在、enabled 状态和 headers 已正确导入。

回退应用版本时旧 `da_agent_settings` 数据仍可被旧代码读取。新增表可以保留；若执行 downgrade，
只删除新增表，不改动旧设置行。新版本运行期间新增的自定义 provider/MCP 无法被旧版本消费，
回退前需导出留档。

## Tradeoffs

本方案保留旧 provider JSON，短期存在历史数据副本，但运行主路径只有 registry 一个来源，避免
双写和优先级歧义。portal 环境导入是一次性兼容步骤，不是 runtime fallback。stdio 支持扩大了
Pi contract，但这是让已公开 MCP 管理契约真实可执行所必需的最小跨层改动。
