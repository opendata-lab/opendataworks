# 智能问数工具输出渲染优化实施计划

> Design: [2026-06-13-nl2sql-tool-output-rendering-design.md](../design/2026-06-13-nl2sql-tool-output-rendering-design.md)

**Goal:** 交付图表导出（PNG/CSV/复制图片/视图切换）、结果表格交互（排序/筛选/搜索/分页/导出/复制）、SQL 面板（高亮/复制/编辑/受治理通道重新执行），并删除 PyMySQL 直连旁路。
**Tech Stack:**

- 前端：`dataagent/dataagent-frontend`（Vue 3、CodeMirror 6、ECharts 5、vitest）
- 后端：`dataagent/dataagent-backend`（FastAPI、httpx、pytest）
- 上游：Java `backend-agent-api` 既有 `POST /v1/ai/query/read`（不改）

## Architecture Summary

前端抽取三个共享组件（`ResultDataTable` / `SqlCodePanel` / `ChartSpecView` 工具栏），`ToolOutputRenderer` 与结论区共用；后端新增纯 HTTP 代理端点 `POST /api/v1/nl2sql/query/execute` → `{ODW_BACKEND_BASE_URL}/query/read`（服务令牌 + `X-Agent-Data-Scope` 头），dataagent-backend 零数据库连接。直连执行器 `core/sql_executor.py` 已随设计提交删除。

## Task 1: 后端只读执行代理端点

**Files:**
- `dataagent/dataagent-backend/core/readonly_query_proxy.py`（新增）
- `dataagent/dataagent-backend/core/data_scope.py`（新增共享 `runtime_data_scope_header()`）
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/tests/test_readonly_query_proxy.py`（新增）

**Steps:**
1. `core/data_scope.py`：新增 `runtime_data_scope_header()`，复用既有 `encode_scope_header`/`normalize_data_scope`，与 skill 运行时 `runtime_data_scope_header()` 同一契约（`ODW_AGENT_DATA_SCOPE_HEADER`/`DATAAGENT_DATA_SCOPE_HEADER` 优先，否则 `DATAAGENT_DATA_SCOPE_JSON` 归一后 base64url 去填充）。backend 侧不再在代理内重复 base64 逻辑（skill bundle 在隔离子进程仍保留各自副本，为既有边界）。
2. `core/readonly_query_proxy.py`：
   - 读取 `ODW_BACKEND_BASE_URL`（兼容 `/api/v1/ai` 与旧值 `/api/v1/ai/metadata`，与 odw-cli 同样规范化到 AI 根路径）、`ODW_AGENT_SERVICE_TOKEN`、`ODW_AGENT_SERVICE_TOKEN_HEADER_NAME`（默认 `X-Agent-Service-Token`）
   - data-scope 头名 `X-Agent-Data-Scope`，头值由 `core.data_scope.runtime_data_scope_header()` 提供
   - `async execute_readonly_query(sql, database, engine, limit, timeout_seconds)`：httpx AsyncClient POST `{ai_base}/query/read`，body `{database, sql, preferredEngine, limit, timeoutSeconds}`；归一上游 `query_result` 为 `sql_execution` 同形 dict（含 `result_state: success|empty_result|failed`）
   - 未配置 base_url/token → 抛配置错误（路由映射 503）；上游 4xx → 透传 detail（400/403）；网络错误/5xx → 502
3. `models/schemas.py`：新增 `ExecuteQueryRequest`（sql/database 必填非空，limit 默认 100、timeout_seconds 默认 30，代理内钳制 limit∈[1,1000]、timeout∈[1,120]）
4. `api/routes.py`：新增 `query_router = APIRouter(prefix="/query")`，`POST /execute` 调用代理；沿用 `_request_context` 校验请求来源；空 sql/database → 400
5. 测试（mock httpx 传输层）：正常归一、空结果 `empty_result`、limit/timeout 钳制、scope 头（JSON 编码 + 预计算值优先）、metadata base url 规范化、上游 400/403 透传、上游 500/网络错误 → 502、未配置 → 503、sql/database 为空 → 400

**Expected Result:**
- `POST /api/v1/nl2sql/query/execute` 返回 `sql_execution` 契约同形 JSON；dataagent-backend 无任何新数据库连接代码

## Task 2: 前端导出工具 tableExport

**Files:**
- `dataagent/dataagent-frontend/src/utils/tableExport.js`（新增）
- `dataagent/dataagent-frontend/src/utils/__tests__/tableExport.spec.js`（新增）

**Steps:**
1. 实现 `buildCsvContent(columns, rows)`（UTF-8 BOM、CSV 转义，参照主 frontend `csvExport.js` 模式复刻）、`buildTsvContent`、`buildMarkdownTable`、`downloadTextFile(filename, content, mime)`、`exportFilename(base, ext)`（`{base}_{yyyyMMddHHmmss}.{ext}`）
2. 单测：转义（逗号/引号/换行）、BOM、null/undefined 单元格、Markdown 管道符转义、文件名格式

**Expected Result:**
- 纯函数工具可被表格/图表组件复用，单测通过

## Task 3: ResultDataTable 增强表格

**Files:**
- `dataagent/dataagent-frontend/src/views/intelligence/components/ResultDataTable.vue`（新增）
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/ResultDataTable.spec.js`（新增）

**Steps:**
1. Props：`columns/rows/title/meta`（meta 含 rowCount/durationMs/hasMore/truncatedBySize/notice）
2. 工具栏：全局搜索输入、导出 CSV、复制 Markdown/TSV；状态条展示行数/耗时/截断提示
3. 表头：点击三态排序（数值感知、null 置底）；漏斗列筛选（distinct ≤ 50 多选，否则列文本过滤）
4. 行数 > 20 启用分页（20/50/100）；sticky 表头、行号列、null 灰显
5. 复用 `.tool-table` 视觉风格；排序/筛选/搜索为纯内存计算（computed 链）
6. 单测：排序三态与数值排序、搜索过滤、列筛选、分页阈值、导出/复制内容正确性

**Expected Result:**
- 组件在 chat 气泡内独立可用，交互全部前端内存完成

## Task 4: SqlCodePanel 与执行接入

**Files:**
- `dataagent/dataagent-frontend/src/views/intelligence/components/SqlCodePanel.vue`（新增）
- `dataagent/dataagent-frontend/src/api/nl2sql.js`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/SqlCodePanel.spec.js`（新增）

**Steps:**
1. `nl2sql.js` 新增 `queryApi.executeSql({ sql, database, engine, limit, timeoutSeconds })` → `POST /query/execute`
2. `SqlCodePanel.vue`：CodeMirror 6 只读高亮（`@codemirror/lang-sql` MySQL dialect）；工具栏复制（复用 `useChatMessageActions` 的剪贴板逻辑抽出的 `copyTextToClipboard`）、编辑/还原、limit 选择（100/500/1000）、执行按钮（无 `database` 时禁用并提示）
3. 执行结果经事件/内部状态渲染到面板下方 `ResultDataTable`，含 loading 与错误态；以 `tool.id` 为界重置本地编辑态
4. 单测：复制调用、编辑/还原状态、执行成功渲染结果、执行失败渲染错误、参数传递

**Expected Result:**
- sql_execution 块内可复制/编辑/重跑 SQL，结果原位刷新

## Task 5: 图表工具栏与渲染入口接线

**Files:**
- `dataagent/dataagent-frontend/src/views/intelligence/ChartSpecView.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/ToolOutputRenderer.vue`
- `dataagent/dataagent-frontend/src/views/intelligence/__tests__/ToolOutputRenderer.spec.js`
- `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`（透传 topicId，如需）

**Steps:**
1. `ChartSpecView.vue` 增加 hover 工具栏：下载 PNG（`getDataURL({type:'png',pixelRatio:2,backgroundColor:'#fff'})`）、复制图片（`ClipboardItem` 能力检测，不支持则隐藏）、导出 CSV（dataset+columns→tableExport）、图表↔数据表切换（切到 `ResultDataTable`）、柱↔折切换（仅 bar/line；本地覆盖 chart_type 重建 option）
2. `ToolOutputRenderer.vue`：
   - `chart_spec` 内嵌渲染改用 `ChartSpecView`（删除重复的 echarts init/option 修饰逻辑）
   - `sql_execution`：`<pre>` → `SqlCodePanel`，结果表 → `ResultDataTable`（透传 meta）
   - `sql_export`：有 `file_path` 时渲染下载按钮（`topicApi.fileUrl`，需 `topicId` prop），`preview_rows` 用 `ResultDataTable`
   - `python_execution`/raw `<pre>`：悬浮复制按钮
3. `NL2SqlChatV2.vue`：向 `ToolOutputRenderer` 透传 `topic-id`
4. 更新 `ToolOutputRenderer.spec.js`：分支接入断言（出现 SqlCodePanel/ResultDataTable/ChartSpecView stub）；为 `ChartSpecView` 工具栏补渲染断言

**Expected Result:**
- 工具块与结论区图表共享同一工具栏；chart 渲染逻辑单点化

## Verification

- 后端：`pytest tests -q` 全量通过（含新 `test_readonly_query_proxy.py`）
- 前端：`npm --prefix dataagent/dataagent-frontend test` 全量通过；`npm --prefix dataagent/dataagent-frontend run build` 可构建
- 端到端 smoke（环境可用时）：本地 MySQL 3316 + Redis 6379 + uvicorn + 前端 dev，真实提问「最近 30 天工作流发布次数趋势」，验证图表下载/导出、表格排序筛选导出、SQL 编辑重跑；未跑则在交付说明中如实声明已验证层

## Rollout / Backout

- Rollout：随 dataagent-backend 与 dataagent-frontend 正常发布；执行端点依赖的 `ODW_BACKEND_BASE_URL`/`ODW_AGENT_SERVICE_TOKEN` 已在 dev/prod compose 注入，无部署变更
- Backout：纯增量功能；回滚提交即可。端点未配置时自身降级为 503，不影响既有聊天链路
