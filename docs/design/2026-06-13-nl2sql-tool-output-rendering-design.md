# 智能问数工具输出渲染优化设计（图表导出 / 表格交互 / SQL 执行）

**Date:** 2026-06-13
**Goal:** 将智能问数聊天界面中的工具输出（图表、结果表格、SQL）从纯展示升级为标准问数产品级交互：图表可下载图片/导出数据，表格可排序/筛选/搜索/分页/导出，SQL 可复制/编辑/重新执行。
**Tech Stack:**

- 前端：`dataagent/dataagent-frontend`（Vue 3 + Vite 5、Element Plus、ECharts 5、CodeMirror 6、marked、vitest）
- 后端：`dataagent/dataagent-backend`（FastAPI + httpx）
- 上游依赖：Java `backend-agent-api` 既有只读查询接口 `POST /v1/ai/query/read`（不改动）
- 基础设施：`deploy/docker-compose.{dev,prod}.yml` 已为 `dataagent-backend` 注入 `ODW_BACKEND_BASE_URL` 与 `ODW_AGENT_SERVICE_TOKEN`，无部署接线改动

## Scope

### 改动范围

- `dataagent/dataagent-frontend/src/views/intelligence/`：工具输出渲染组件增强与共享组件抽取
- `dataagent/dataagent-frontend/src/api/nl2sql.js`：新增 SQL 执行 API 客户端
- `dataagent/dataagent-backend/api/`：新增只读 SQL 执行代理端点
- `dataagent/dataagent-backend/core/sql_executor.py`：删除（被禁用的 PyMySQL 直连执行器，详见 Problem）

### 明确不做

- **不在 dataagent-backend 直连 Doris/MySQL 执行交互式查询**。本功能不使用、不扩展 `core/tool_runtime.py` 的 PyMySQL 路径；交互式执行与 Agent 执行统一走 Java 受治理查询通道，保证只读校验、限额守卫、数据范围隔离、凭据管理单点一致。
- 不动 `opendataagent/`、主 `frontend/`、Java `backend/`（仅消费其既有接口）、skill bundle 输出契约（`sql_execution` / `chart_spec` / `sql_export` 契约保持不变，本设计是纯消费端增强加一个独立代理端点）。
- 不引入 xlsx/exceljs 等新依赖，导出仅 CSV / TSV / Markdown，零新增 npm 依赖。
- 不做 SQL 格式化（需引入 sql-formatter，列为可选后续项）。
- 不做异步大结果导出。交互式执行沿用 limit 钳制；超限场景引导走 Agent 既有的 `sql_export` 路径（写盘 + 文件下载）。

## Current State

### 渲染层（dataagent-frontend）

`src/views/intelligence/` 下：

- `ToolOutputRenderer.vue` 按 `kind` 分支渲染：
  - `sql_execution`：SQL 用 `<pre><code>` 纯文本展示，结果用手写 HTML `<table class="tool-table">` 展示
  - `chart_spec`：经 `buildChartRenderModel()` 渲染 ECharts canvas 或纯 HTML 表格
  - `python_execution` / raw / trace：`<pre>` 或 markdown
- `ChartSpecView.vue`：结论区图表组件，与 `ToolOutputRenderer.vue` 内的图表渲染逻辑（init/resize/option 修饰）基本重复
- `chartSpec.js`：chart_spec 解析、校验、ECharts option 构建的单一事实源（已有 `chartSpec.spec.js` 测试）
- `useChatMessageActions.js`：已有 `copyTextToClipboard()`（Clipboard API + execCommand 回退），目前仅用于复制整条消息
- 依赖已就绪：`@codemirror/lang-sql`、`codemirror`、`echarts`、`element-plus`，vitest 测试体系完整

所有工具输出均为**纯展示**：无复制、无导出、无排序筛选、无法编辑重跑 SQL。

### 数据契约（skill bundle，保持不变）

`dataagent/.claude/skills/opendataworks-platform-tools/reference/50-tool-output-contract.md`：

- `sql_execution`：`{ sql, columns, rows, row_count, has_more, truncated_by_size, duration_ms, engine, database, result_state, error_code, ... }`
- `chart_spec` v1：`{ chart_type: table|bar|line|pie, title, x_field, series, dataset, columns, unit, colors, ... }`，由前端 ECharts 渲染，无静态图片
- `sql_export`：`{ file_path, file_format, preview_rows, row_count, ... }`，文件经 `GET /api/v1/nl2sql/topics/{topic_id}/files/{rel_path}` 下载

### SQL 执行通道（现状）

- 权威通道：skill 脚本经 `odw-cli`（`ODW_BACKEND_BASE_URL` + `X-Agent-Service-Token`）调用 Java `backend-agent-api` 的 `POST /v1/ai/query/read`。该接口具备 JSQLParser 只读校验（词法回退）、limit 默认 1000/上限 10000、512KB 字节守卫、data-scope 隔离、数据源路由，数据库凭据仅存在于 Java 侧。
- 旁路（待删除）：`dataagent-backend/core/sql_executor.py` 经 `core/tool_runtime.run_query` 用 PyMySQL 直连 MySQL/Doris。该路径未暴露任何 HTTP 路由、无业务调用方（仅 `tests/test_sql_executor.py` 引用），且绕过平台治理。

## Problem

1. 工具输出是"只能看"的：用户无法把图表存为图片、无法把结果表格带走（CSV）、无法对几十行结果做排序/筛选/搜索，也无法复制或微调 SQL 后立即重跑——这些都是标准问数产品的基线能力，缺失导致用户被迫截图、手抄数据或重新向 Agent 提问（慢且消耗模型 token）。
2. 图表渲染逻辑在 `ToolOutputRenderer.vue` 与 `ChartSpecView.vue` 重复实现，新增图表交互能力若不先收敛会进一步发散。
3. `core/sql_executor.py` 的 PyMySQL 直连执行器是治理旁路：凭据分散、守卫与 Java 侧不一致、无统一审计。即使当前无调用方，留着就有被接线的风险——**已确认禁止直连，本设计将其删除**，交互式执行统一收敛到 Java 受治理通道。

## Design

### 总体思路

抽取三个可复用前端组件（增强表格、SQL 面板、图表工具栏），让 `sql_execution`、`chart_spec`、结论区图表、`sql_export` 预览共用同一套交互能力；后端只加一个**纯 HTTP 代理**端点，把交互式 SQL 执行转发到 Java 受治理通道，dataagent-backend 全程不接触数据库连接与凭据。

### 1. 共享组件（dataagent-frontend）

新增 `src/views/intelligence/components/`：

#### `ResultDataTable.vue` — 增强结果表格

自研轻量组件，复用现有 `.tool-table` 视觉风格（不引入 el-table，理由见 Risks/Alternatives）。

- 列排序：表头点击三态（升/降/无）；数值列按数值比较，null 置底
- 全局搜索：工具栏关键字输入，跨列包含匹配（防抖）
- 列筛选：列头漏斗按钮 → distinct 值多选；distinct 值超过 50 时降级为该列文本过滤
- 分页：行数 > 20 时启用（每页 20/50/100），否则隐藏分页器
- 导出 CSV：UTF-8 BOM + 标准 CSV 转义；导出**当前筛选后的全部行**（非仅当前页）；文件名 `{title|export}_{yyyyMMddHHmmss}.csv`
- 复制：复制为 Markdown 表格 / TSV（TSV 可直接粘贴进 Excel）
- 状态条：行数、耗时、`has_more` / `truncated_by_size` 截断提示（提示「结果被截断，完整数据请使用导出或让助手生成导出文件」）
- sticky 表头、行号列、null 值灰显为 `NULL`
- 所有筛选/排序/分页均为纯前端内存操作（行规模已被上游 512KB / limit 守卫约束）

Props 草案：`{ columns: string[], rows: object[], title?: string, meta?: { rowCount, durationMs, hasMore, truncatedBySize, notice } }`

#### `SqlCodePanel.vue` — SQL 面板

- CodeMirror 6 只读高亮展示（`@codemirror/lang-sql`，MySQL dialect；依赖已存在）
- 工具栏：
  - 复制 SQL（复用 `copyTextToClipboard`）
  - 编辑：切换 CodeMirror 为可编辑态；提供「还原」恢复原始 SQL
  - 执行：调用新端点 `POST /api/v1/nl2sql/query/execute`，loading/错误态内联展示
  - limit 选择：100（默认）/ 500 / 1000
- 执行结果原位刷新到面板下方的 `ResultDataTable`（替换原静态结果），并展示新一次执行的行数/耗时
- Props 草案：`{ sql: string, database: string, engine?: string, executable?: boolean }`；`executable` 默认依据 `database` 是否存在

#### 图表工具栏（融入 `ChartSpecView.vue`）

先收敛重复：`ToolOutputRenderer.vue` 的 `chart_spec` 内嵌渲染改为直接使用 `ChartSpecView.vue`，图表逻辑单点化；再在 `ChartSpecView.vue` 上加 hover 工具栏：

- 下载 PNG：`chartInstance.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })`，文件名取 `spec.title`
- 复制图片：canvas → blob → `ClipboardItem`；运行环境不支持（非 secure context / 无 ClipboardItem）时隐藏按钮
- 导出 CSV：来自 `spec.dataset` + `spec.columns`（复用同一 CSV 工具）
- 图表 ↔ 数据表切换：「查看数据」切到 `ResultDataTable` 展示 dataset，可切回
- 柱状 ↔ 折线切换：仅当 `chart_type ∈ {bar, line}` 时提供（pie/table 不提供）

#### `src/utils/tableExport.js` — 导出工具

CSV（UTF-8 BOM、转义）/ TSV / Markdown 表格构建 + Blob 触发下载。参照主 frontend `src/views/datastudio/csvExport.js` 的成熟模式在本模块内复刻（不跨模块 import，保持模块边界）。

### 2. 渲染入口改造

- `ToolOutputRenderer.vue`：
  - `sql_execution`：`<pre>` → `SqlCodePanel`（从 payload 透传 `sql/database/engine`）；HTML 表格 → `ResultDataTable`（透传 `row_count/duration_ms/has_more/truncated_by_size/notice`）
  - `chart_spec`：内嵌图表/表格统一改用 `ChartSpecView`
  - `sql_export`：存在 `file_path` 时显示「下载文件」按钮（拼接既有 `GET /topics/{topic_id}/files/{rel_path}`，需新增 `topicId` prop 由消息渲染层透传）；`preview_rows` 用 `ResultDataTable` 展示
  - `python_execution` / raw 的 `<pre>` 代码块：右上角悬浮「复制」按钮
- 结论区：`NL2SqlChatV2.vue` 经 `splitChartSpecText` 渲染的 chart 段落因共用 `ChartSpecView`，图表工具栏与「查看数据」自动生效，无需单独实现

### 3. 后端只读执行代理端点（dataagent-backend，零数据库连接）

`POST /api/v1/nl2sql/query/execute`

- 实现：纯 HTTP 代理（httpx，async），转发到 `POST {ODW_BACKEND_BASE_URL}/query/read`，携带：
  - 服务令牌头：`{ODW_AGENT_SERVICE_TOKEN_HEADER_NAME:-X-Agent-Service-Token}: {ODW_AGENT_SERVICE_TOKEN}`
  - data-scope 头：与 skill 运行时 `runtime_data_scope_header()` 同一生成逻辑（`ODW_AGENT_DATA_SCOPE_HEADER` 优先，否则由 `DATAAGENT_DATA_SCOPE_JSON` 规范化后 base64url）
- dataagent-backend 仅做：入参非空校验、limit/timeout 钳制、错误归一透传。只读校验、字节守卫、数据源路由、scope 隔离全部由 Java 侧权威执行
- 未配置 `ODW_BACKEND_BASE_URL` 或 `ODW_AGENT_SERVICE_TOKEN` 时返回 503 +「SQL 执行通道未配置」
- 对前端鉴权沿用 `api/routes.py` 既有路由依赖（与 topics/tasks 一致）
- 部署：dev/prod compose 均已为 `dataagent-backend` 服务注入上述两个环境变量（已核实），无部署改动

### 4. 删除直连执行器（本次随设计一并执行）

- 删除 `dataagent/dataagent-backend/core/sql_executor.py`
- 删除 `dataagent/dataagent-backend/tests/test_sql_executor.py`
- 删除 `models/schemas.py` 中仅被其引用的 `SqlExecutionResult`
- 已核实无其他业务调用方；`core/tool_runtime.py` 因 `metadata_loader.py` 仍在使用而保留，但不用于本功能

## Interfaces / Data Model

### 新增 HTTP 接口

`POST /api/v1/nl2sql/query/execute`

请求：

```json
{
  "sql": "SELECT ... (必填)",
  "database": "目标库名 (必填)",
  "engine": "mysql|doris (可选，映射为 preferredEngine)",
  "limit": 100,
  "timeout_seconds": 30
}
```

- `limit`：默认 100，钳制到 [1, 1000]
- `timeout_seconds`：默认 30，钳制到 [1, 120]

响应（与 `sql_execution` 工具输出契约同形，前端复用同一渲染路径）：

```json
{
  "kind": "sql_execution",
  "engine": "mysql",
  "database": "example_schema",
  "sql": "SELECT ...",
  "columns": ["col_a"],
  "rows": [{"col_a": 1}],
  "row_count": 1,
  "has_more": false,
  "truncated_by_size": false,
  "notice": null,
  "duration_ms": 120,
  "result_state": "success",
  "error": null
}
```

错误映射：上游 4xx → 400/403 透传 message；上游 5xx/网络错误 → 502；通道未配置 → 503。`result_state` 为 `success | empty_result | failed`。

### 前端 API 客户端

`src/api/nl2sql.js` 新增：

```js
queryApi.executeSql({ sql, database, engine, limit, timeoutSeconds })
```

### 既有契约

`sql_execution` / `chart_spec` / `sql_export` 工具输出契约、`GET /topics/{topic_id}/files/{rel_path}` 文件下载接口均不变。

## Risks / Alternatives

- **代理 vs 直连（已决策）**：`core/sql_executor.py` 直连虽已存在，但绕过平台治理（凭据分散、守卫不一致、无统一审计），明确禁用并删除。代理增加一跳网络延迟，对交互式查询可接受。
- **自研表格 vs el-table**：el-table 自带排序/筛选，但在聊天气泡内样式侵入重、widget 包体积敏感；需求面窄（内存内排序/筛选/搜索/分页/导出），自研约束在一个组件内且与现有 `.tool-table` 视觉一致。若后续需求膨胀（列冻结、树形、虚拟滚动）再评估切换 el-table。
- **图表导出用 ECharts `getDataURL` 而非 html2canvas**：零依赖、清晰度可控（pixelRatio=2）；代价是仅导出 canvas 本体（不含外层 DOM 装饰），可接受。
- **复制图片的兼容性**：`ClipboardItem` 在非 secure context 不可用，策略为能力检测后隐藏按钮，不做降级 hack。
- **执行入口的安全面**：前端可编辑任意 SQL 提交，安全边界完全依赖 Java 侧只读校验与 data-scope。dataagent-backend 不自行实现 SQL 解析（避免双实现漂移），仅做参数钳制——这是有意取舍：单点权威优于两层不一致的校验。
- **流式期间交互**：工具输出在流式渲染中可能被多次刷新，工具栏操作需基于当前 payload 快照；`SqlCodePanel` 编辑态在消息重渲染时保持本地状态（以 `toolInstanceKey` 为界重置）。

## Verification

- 前端单测（`npm --prefix dataagent/dataagent-frontend test`，vitest）：
  - 新增 `tableExport.spec.js`（CSV 转义/BOM、TSV、Markdown）
  - 新增 `ResultDataTable.spec.js`（排序三态与数值排序、搜索、列筛选、分页阈值、导出内容）
  - 新增 `SqlCodePanel.spec.js`（复制、编辑/还原、执行成功/失败态、limit 钳制传参）
  - 更新 `ToolOutputRenderer.spec.js`（sql_execution/chart_spec/sql_export 分支接入新组件）
- 后端单测（pytest，mock Java 上游）：正常返回归一、上游只读拒绝/越权透传、limit 与 timeout 钳制、上游 5xx → 502、未配置通道 → 503
- 删除验证：dataagent-backend 全量 pytest 通过，无 `sql_executor` / `SqlExecutionResult` 残余引用
- 端到端 smoke（按 AGENTS.md 智能问数流程：本地 MySQL 3316 + Redis 6379 + uvicorn + 前端 dev）：真实提问「最近 30 天工作流发布次数趋势」，验证图表工具栏（下载 PNG / 导出 CSV / 查看数据）、结果表格排序筛选导出、SQL 复制与编辑后重新执行原位刷新
- 实现阶段若未跑端到端 smoke，必须在验证说明中如实声明已验证层与未覆盖路径
