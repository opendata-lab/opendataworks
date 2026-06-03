# 智能问数结果体积守卫（避免 SDK JSON 缓冲溢出）— 设计

- 日期：2026-06-03
- 范围：`backend`（agentapi 只读查询与元数据导出）、`dataagent`（portal-mcp 透传、skill 脚本 `run_sql.py` 与运行时桥接）
- 类型：中型（跨后端 / portal-mcp / skill 脚本，工具结果契约新增字段）
- 关联：`config.py` 已先行将 `agent_max_buffer_size_bytes` 从 1MB 提升到 10MB（缓解阈值，非根因修复）

## 现状

智能问数运行时通过 `claude-agent-sdk` 拉起 Claude CLI 子进程，按行缓冲其 stdout 上的 JSON 消息帧。SDK 在 `_internal/transport/subprocess_cli.py` 中对单条 JSON 帧设有上限（默认 1MB，可经 `ClaudeAgentOptions.max_buffer_size` 配置），单帧超限即 `raise SDKJSONDecodeError`。

工具结果（tool_result）会被 CLI 包成**单条** stdout JSON 帧。NL2SQL 的结果有两条产出路径：

- **MCP 路径（主路径）**：模型调 `mcp__portal__portal_query_readonly`，portal-mcp（`dataagent/portal-mcp`）透传后端结果，无任何截断。
- **脚本回退路径**：skill 脚本 `run_sql.py` → `_opendataworks_runtime.query_readonly()` → `odw-cli query-readonly`。

经核实，两条路径最终都 curl 同一个后端端点 `POST /v1/ai/query/read`（`AgentQueryController` → `BackendAgentQueryService.readQuery`）。该服务当前只有**行数上限**（`DEFAULT_LIMIT=1000`、`MAX_LIMIT=10000`），**没有字节上限**：1000 行宽表或含大文本字段的结果可轻松超过 1MB 乃至 10MB。

此外 `BackendAgentMetadataService` 的 `exportTables/exportLineage/exportDatasource` 完全无行数上限，属于次要但真实的溢出来源。

## 问题

1. 一旦单条工具结果帧超过缓冲上限，SDK 从读取 stdout 的异步生成器内部抛错，`claude_query` 整个流不可恢复地终止；`task_executor` 把它当作普通异常归到 `error`，且不走超时类的 partial 恢复路径。结果是：**一次本应成功的问数因一条超大结果帧整体失败**。
2. 仅提升 `max_buffer_size` 只是把阈值抬高，治标不治本——更大的结果仍会再次触发，且把超大 payload 灌进对话也会拖慢与污染上下文。

## 范围

- 目标：在**产出结果的源头**（`readQuery`）对返回行做**字节预算守卫**；超限时**截断为前 N 行**并通过工具结果显式告知模型“结果过大、已截断、请缩小范围”，使本次 run 继续、由模型自行调整查询参数（加过滤 / 聚合 / 降低 LIMIT）。
- 同步给无界的 `export*` 元数据接口补**行数安全上限**。
- 让两条消费路径都能向模型透出截断信号：MCP 路径天然透传新字段；脚本路径在 `_opendataworks_runtime.query_readonly()` 与 `run_sql.py` 中补透传与提示。
- 不在本期范围：分页 / 游标式拉取、按列裁剪、export 接口的逐字段截断提示、前端展示改造。
- 决策（已与需求方确认）：
  - 超限行为＝**截断 + 提示**（非硬报错）。
  - 落点＝**统一在 Java 后端源头** `readQuery`，一处覆盖 MCP 与脚本两条路径。

## 方案

### 1. 后端字节预算守卫（核心）

`BackendAgentQueryService.readQuery` 在拿到 `execution.getRows()` 后、写入响应前：

- 逐行用 Jackson 估算紧凑 JSON 字节数并累加；累计超过预算 `maxResultBytes` 时在该行截断。
- 至少保留 1 行（即便首行已超预算，仍返回该行，使模型有上下文；预算远低于缓冲上限，单行安全）。
- 截断发生时：`rows` 置为截断后子集，`rowCount` 反映返回行数，`hasMore=true`，新增 `truncatedBySize=true`、`notice=<面向模型的中文引导语>`。

预算来源：新增可配置项 `agent.query.max-result-bytes`，默认 `524288`（512KB）。该默认值选取原则：

- 显著低于 SDK 默认 1MB，确保即便未启用 `max_buffer_size=10MB` 也安全；
- 为脚本路径 `run_sql.py` 的 `indent=2` 美化与外层信封预留余量；
- 与 `agent_max_buffer_size_bytes`（dataagent 侧，默认 10MB）构成两级关系：源头预算 < 传输缓冲上限。

### 2. 响应契约新增字段

`AgentReadQueryResponse` 增加：

- `@JsonProperty("truncated_by_size") Boolean truncatedBySize`
- `String notice`

仅在截断时置值；未截断时为 `null`，对既有消费者向后兼容。

### 3. export 元数据行数安全上限

`BackendAgentMetadataService` 新增 `EXPORT_MAX_ROWS`（默认 5000）常量，`exportTables/exportLineage/exportDatasource` 返回前截断至上限并 `log.warn`。这是防溢出安全阀，不改变返回结构。

### 4. 脚本路径透出

- `_opendataworks_runtime.query_readonly()`：返回字典补 `truncated_by_size`、`notice` 两个键的透传。
- `run_sql.py`：成功分支读取上述字段；截断时在输出中带上 `truncated_by_size`/`notice`，并将 `stop_reason` 设为“结果过大已截断，请缩小范围……”，与现有 `classify_sql_execution_failure` 的引导风格一致，使脚本路径下模型也能继续并调整。

## 接口与契约影响

- `POST /v1/ai/query/read` 响应新增两个可选字段，向后兼容。
- `mcp__portal__portal_query_readonly` 结果新增两个可选字段，模型据此识别截断。
- skill 结果 schema（`sql_execution`）新增 `truncated_by_size`/`notice`，并复用 `stop_reason` 语义。

## 取舍

- 选“截断+提示”而非“硬报错”：保证 run 必然继续且模型拿到样本数据，同时明确告知不完整，避免模型在无法缩小时反复试探；代价是模型需自行判断是否需要更精确口径。
- 选“统一在 Java 源头”而非“agent 面向层各加一份”：一处覆盖两条路径、避免重复字节守卫逻辑；代价是脚本路径需少量透传管线（不复制守卫逻辑本身）。
- 字节预算固定为单一保守默认值并可配置，符合“一条稳定主路径 + 最小回退”的仓库准则。
