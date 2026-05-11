# Portal MCP Design

## Background

智能问数当前已经有一条稳定的动态元数据链路：

- skill 脚本
- `dataagent-nl2sql/bin/odw-cli`
- backend agent metadata API

这条链路已经先完成了 CLI 收敛与敏感连接信息下线；在此基础上，数据门户的 inspect、lineage、datasource、DDL、只读 query 能力进一步切到 MCP-first，更适合作为 OpenDataWorks 内部 DataAgent 的默认工具面，同时保留 skill-local CLI 作为非 MCP 智能体的兼容路径。

## Scope

本次范围：

- 将 OpenDataWorks 内部 DataAgent 主链路切到 MCP-first
- 在 `backend-agent-api` 上补齐 agent-only DDL 与只读 query 接口
- 新增远程 `portal-mcp` 服务，对外暴露数据门户 MCP 工具
- 更新部署、镜像和离线包脚本，并为 DataAgent runtime 注入 portal-mcp client 配置

不在本次范围：

- 用户身份透传与细粒度用户态鉴权
- 写操作、异步查询任务、跨数据源联查

## Architecture

### MCP-First Runtime

保留两条路径并存：

1. OpenDataWorks 内部 DataAgent 默认走 `portal-mcp -> backend /api/v1/ai/metadata/* + /api/v1/ai/query/read`
2. 不支持远程 MCP 的智能体继续走 `script -> odw-cli -> backend /api/v1/ai/*`

这样可以把 MCP 作为默认主链路，同时保留一条单层 fallback，不拆 skill，也不回退到直连数据库。

### Backend Boundary

`backend` / `backend-agent-api` 仍是唯一业务边界：

- metadata inspect / lineage / datasource / export 保持不变
- 新增 `GET /api/v1/ai/metadata/ddl`
- 新增 `POST /api/v1/ai/query/read`

`portal-mcp` 不直接访问任何数据库，不复制业务规则，只做：

- MCP tool schema
- front-door token 校验
- 调 backend agent API
- 输出结构化结果和错误映射

### Query Contract

`POST /api/v1/ai/query/read`

请求体：

- `database` 必填
- `sql` 必填
- `preferredEngine` 可选
- `limit` 可选，默认 `1000`，最大 `10000`
- `timeoutSeconds` 可选，默认 `30`，最大 `120`

服务端约束：

- 仅允许单条 SQL
- 仅允许 `SELECT` / `WITH` / `SHOW` / `DESC` / `DESCRIBE` / `EXPLAIN`
- 使用 backend 解析后的 datasource 连接信息执行
- 统一在 backend 侧处理结果截断、超时和错误

响应体：

- `kind`
- `database`
- `engine`
- `sql`
- `limit`
- `row_count`
- `has_more`
- `duration_ms`
- `rows`

### DDL Contract

`GET /api/v1/ai/metadata/ddl`

入参：

- `database` 可选
- `table` 可选
- `tableId` 可选

约束：

- `tableId` 与 `database + table` 至少提供一组
- 若能命中 `data_table`，返回平台元数据中的表注释、字段摘要和定位信息
- live DDL 统一通过 agent 解析后的 datasource 读取 `SHOW CREATE TABLE`

响应体：

- `kind`
- `database`
- `table_name`
- `table_id`
- `cluster_id`
- `cluster_name`
- `engine`
- `source_type`
- `resolved_by`
- `table_comment`
- `fields`
- `ddl`

## Auth and Deployment

### Backend Agent API

- 继续使用 `X-Agent-Service-Token`
- 继续默认要求私网来源
- 鉴权范围从 `/v1/ai/metadata/**` 扩到 `/v1/ai/query/**`

### Portal MCP Front Door

- 新增独立 front-door token
- 默认 header：`X-Portal-MCP-Token`
- `health` 检查不要求 token
- MCP streamable HTTP 入口默认挂载到 `/mcp`

### Deployment Topology

- 新增独立容器 `portal-mcp`
- `portal-mcp` 通过内网访问 `backend:8080/api`
- DataAgent Compose 增加 `DATAAGENT_PORTAL_MCP_*` 运行时配置，由 runtime 动态注入当前 run，不依赖 repo 内静态 `.claude/settings.json`

## Tradeoffs

- 选择 MCP-first + CLI fallback，既能让 OpenDataWorks 内部 DataAgent 直接受益于远程 MCP，也能兼容不支持 MCP 的其他智能体
- 选择远程 MCP 而不是本地 MCP，提升了多客户端复用性，也让版本和 token 集中管理
- query 放在 backend 侧执行，能统一权限、超时和数据源解析；代价是 backend 需要承担更多 agent-only 只读查询逻辑
- v1 使用服务身份，不做用户透传，优先保证稳定接入与统一部署
