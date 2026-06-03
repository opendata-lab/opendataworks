# 智能问数 SQL 结果导出文件能力 — 设计

- 日期：2026-06-03
- 范围：`backend`（agentapi 只读查询新增导出模式）、`dataagent`（odw-cli、运行时桥接、新增 skill 导出脚本与文档）
- 类型：中型（跨后端 / odw-cli / skill 脚本，新增工具能力与请求字段）
- 关联：承接 `2026-06-03-nl2sql-result-size-guard-design.md`（源头字节守卫）；本设计解决「字节守卫挡住了批量导出场景」的缺口

## 现状与问题

源头字节守卫（默认 512KB）已能防止大结果撑爆运行时 JSON 缓冲，但它对**批量导出到文件**场景是反作用的：真实案例中用户要把「全部未解决架构风险（约 600+ 条、14 个宽文本列）」导出为 Excel，守卫会把结果截断、并提示模型缩小范围——与用户目标相悖。

根因是反模式：数据走了 `DB → 工具结果 → 模型上下文 → 模型再转写进 Python`。大批量数据**不应穿过模型上下文**。

关键事实：skill 这条路**不依赖 MCP 查询**。`run_sql.py` 经 `odw-cli query-readonly` → 后端 `/v1/ai/query/read`，与 `mcp__portal__portal_query_readonly` 是通往同一后端端点的两条平行路径。因此一个 **skill 本地脚本可以「查询 + 落盘」一步到位**：脚本在 agent 工作区内运行，把全量结果取回脚本进程并直接写工作区文件，只向 stdout 打印「路径 + 列结构 + 行数 + 少量预览」。全量数据只在「脚本进程 ↔ 磁盘文件」之间流动，odw-cli 输出被脚本子进程直接读取，**不经过 SDK 的 stdout 缓冲**，因此不会触发溢出；模型随后用 Python 读该文件生成 Excel。

不采用「MCP 新增存储文件接口」：portal-mcp 是独立 HTTP 服务，其写入的文件落在自身文件系统，未必与 agent 工作区共享卷，返回的路径模型未必可读；且会把导出逻辑复制进通用 MCP 服务。仅当存在「纯 MCP、无本地执行能力」的 agent 且保证共享卷时才考虑。

## 范围

- 目标：新增 skill 本地导出脚本，基于只读 SQL 把**全量结果**（受 `MAX_LIMIT=10000` 行上限保护）物化为**工作区 CSV 文件**，只回路径+列+行数+预览给模型。
- 后端补一个**导出模式**：本地导出调用可显式跳过字节守卫（因为结果写盘、不进上下文）。
- 更新 skill 路由规则：大结果/要落盘 → 用导出脚本，不要用 `portal_query_readonly` / `run_sql.py` 把全量拉进上下文。
- 不在本期范围：xlsx 直出（由模型 Python 读 CSV 生成）、超过 10000 行的分页导出、parquet、MCP store-file 接口。
- 决策（已确认）：架构＝skill 本地导出脚本；格式＝CSV 数据文件。

## 方案

### 1. 后端导出模式

- `AgentReadQueryRequest` 增 `for_export`（Boolean，默认 null/false）。
- `BackendAgentQueryService.readQuery`：当 `for_export=true` 时跳过字节守卫，直接返回（受 `normalizeLimit` 的 `MAX_LIMIT=10000` 行上限保护）；否则维持现有字节守卫。

### 2. odw-cli

- `parse_query_readonly` 增 `--for-export` 开关，为真时在请求体加 `"forExport":true`。

### 3. 运行时桥接

- `_opendataworks_runtime.query_readonly(..., for_export=False)`：为真时向 odw-cli 传 `for_export="true"`。默认行为不变。

### 4. skill 导出脚本 `export_query.py`

- 参数：`--database`、`--sql`、`--output`、`--engine`、`--limit`（默认 10000）、`--preview-rows`（默认取 `DATAAGENT_RESULT_PREVIEW_ROWS`，回退 20）。
- 流程：`ensure_read_only` → `query_readonly(for_export=True)` → 写 CSV 到工作区（自动建父目录）→ `print_json` 输出 `sql_export` 契约（绝对路径、列、行数、`has_more`、前 N 行预览、`result_state`、`stop_reason`）。
- 失败时复用 `error_payload` + `classify_sql_execution_failure` 风格收口。

### 5. skill 文档

- SKILL.md 工具清单与路由规则补导出脚本；`reference/50-tool-output-contract.md` 增 `sql_export` 契约；`reference/30-tool-recipes.md` 增「大结果导出」配方。
- 执行引用使用完整形式：`"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_SKILL_ROOT}/scripts/export_query.py" ...`（遵循仓库 invocation 契约）。

## 接口与契约影响

- `POST /v1/ai/query/read` 请求新增可选字段 `for_export`，向后兼容（默认不变）。
- 新增 skill 工具输出 `kind=sql_export`。
- 字节守卫默认行为不变；仅导出这条显式开关绕过。

## 取舍

- skill 本地脚本 vs MCP store-file：选前者，避免共享卷耦合与逻辑重复，且查询+导出可在一个本地脚本内完成。
- CSV vs xlsx 直出：选 CSV，脚本职责单一、通用；Excel 表现层交给模型 Python。
- 导出绕过字节守卫的安全性：仅本地脚本可触发，结果写盘、只回预览，不进模型上下文，故安全；行数仍受 `MAX_LIMIT=10000` 硬上限约束。
