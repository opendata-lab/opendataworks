# 统一 Agent 事件契约设计

- 日期：2026-09-10
- 状态：待评审
- 主题：unified-agent-event-contract
- 影响范围：
  - `dataagent/dataagent-runtime-pi/`
  - `dataagent/dataagent-backend/`
  - `dataagent/dataagent-frontend/`
  - `dataagent/contracts/`
  - `deploy/`

## 1. 结论

当前系统拥有“共享 block 投影门面”，但没有统一事件模型。实时与历史分别维护 JavaScript 和 Python 两套语义等价、实现重复的投影逻辑。

本设计采用 A+，并进一步合并投影：

1. 定义以 Claude Code 数据模型为基础超集的 `AgentRecordV1`。
2. Claude 原生 `stream/tool_result/done` 是契约的一等成员，不做破坏性降级。
3. Pi 和后续新引擎统一生产 `agent_event`。
4. `da_agent_sdk_record` 保存完整 AgentRecordV1 信封。
5. 前端 `processV2Record()` 是唯一 block/state reducer。
6. 历史接口返回原始 AgentRecordV1 记录，前端直接重放。
7. 删除 Python `_project_sdk_records()` 和前端 `buildV2StateFromStoredBlocks()`。
8. 不再维护第二套历史 block 投影，也不再用三向差分测试维持两份重复实现。
9. `tool.progress` 从 v1 契约和生产端删除；没有明确 UI 用途的事件不进入公共协议。
10. 模型上下文、UI 事件持久化和前端投影保持三条独立链路。

新增 block 类型时，只修改前端唯一 reducer 及其 fixture，不再同时修改 Python 投影。

## 2. 目标

### 2.1 功能目标

- Pi 成功任务进入 `finished`，SSE 正常关闭。
- Pi 成功任务对应的消息队列和定时任务状态为 `completed`。
- Pi 工具结果中的 SQL 表格和图表恢复结构化渲染。
- 实时和历史使用同一个 reducer。
- 多 turn、`question_request`、`tool.denied`、usage 和终态可正确回放。
- Pi 和未来引擎的完整事件身份、运行身份、序号和时间戳得到持久化。
- 第三个引擎只需实现 AgentRecordV1 生产适配器，不修改前端 reducer 的引擎分支。
- Prompt caching 使用稳定前缀，只在真实水位触发上下文 compaction。

### 2.2 硬约束

- 中性模型必须是 Claude Code 当前数据模型的超集。
- `da_agent_message → _build_history` 的内容、角色、顺序和序列化结果不得被事件翻译层改动。
- 图表和表格渲染必须恢复。
- 现有渲染代码尽量不改。
- 简单性优先；删除重复投影，不用长期差分测试维持重复实现。
- 兼容逻辑必须单层、显式、可观测。
- 不通过双写维持兼容，避免重复 block。
- 第三个引擎接入不能要求修改前端或增加 Python block 投影。

## 3. 非目标

本次不做：

- 重命名物理表 `da_agent_sdk_record`。
- 立即将 Claude 原生 streaming protocol 改写成中性事件。
- 修改 `_build_history` 或使用事件日志重建模型上下文。
- 批量重写已有 `pi_event` JSON。
- 在前端展示所有 `output_meta` 字段。
- 为没有产品用途的 `tool.progress` 设计 UI。
- 用关闭 prompt caching 掩盖上下文裁剪问题。
- 保留 Python block projection 作为兼容 fallback。

## 4. 现状与根因

### 4.1 当前是两套投影

实时路径：

    records
      → processV2Record / reducePiEvent
      → LiveChatState

历史路径：

    records
      → Python _project_sdk_records
      → stored blocks
      → buildV2StateFromStoredBlocks
      → LiveChatState

重复点包括：

- turn 创建与分组；
- content started/delta/completed；
- thinking/text 分类；
- tool started/completed/denied；
- synthetic tool block；
- question/permission；
- usage；
- terminal 状态；
- output/output_meta；
- block 索引和状态收口。

当前历史路径已经产生行为差异：

- 多 turn 被压平。
- `question_request` 被丢弃。
- `content.completed.text` 覆盖规则不同。
- 缺少 `turn.started` 时前后端行为不同。
- orphan tool completion 处理不同。
- Pi output 真实形状未被共享 fixture 覆盖。

继续修补 Python 投影，只会把重复实现永久化。

### 4.2 合并投影的可行性

代码检查没有发现必须保留 Python block 投影的硬约束。

当前后端对 projected blocks 的直接业务依赖只有 follow-up suggestions 辅助逻辑：

- `_message_answer_text()` 优先读 blocks，但已有 `message.content` fallback；最终回答本来就持久化在 `da_agent_message.content`。
- `_message_result_summary()` 查找的 block type 为 `tool/chart_spec`，而当前 SDK 投影产出的是 `tool_use`，该路径已经不能稳定得到工具摘要。

因此 follow-up suggestions 可以直接使用权威的 `message.content`。如果未来确实需要工具结果摘要，应在任务完成时显式持久化 `result_summary`，而不是保留整套 Python UI 投影。

当前 `_load_task_history_views()` 已经从数据库加载消息页中所有 task 的原始 SDK records，再投影为 blocks。因此删除投影不会增加数据库查询次数；主要新增成本是 API 传输更多 delta records 和浏览器内重放。

这不是保留重复投影的理由。传输成本通过以下方式控制：

- topic message 分页；
- 单次批量查询关联 task records，避免 N+1；
- HTTP gzip；
- 前端增量重放；
- 对 legacy `tool.progress` 记录在读时过滤；
- 对超大历史页进行基准测试；
- 若达到实际阈值，再引入通用 records 分页或按 task 懒加载，而不是恢复 Python block projection。

## 5. 三条独立链路

### 5.1 模型上下文链路

    da_agent_message
      → _build_history
      → Pi CellInitPayload.history/messages
      → model context

规则：

- 不经过 AgentRecord 翻译层。
- 不读取 `da_agent_sdk_record`。
- 不读取前端 block。
- 不使用历史重放结果作为模型上下文。
- 事件改造前后 `_build_history` 输出必须字节一致。
- Pi 上下文裁剪只修改本次模型调用使用的内存副本。

### 5.2 UI 事件持久化链路

    engine-native event
      → engine adapter
      → AgentRecordV1
      → da_agent_sdk_record

规则：

- 引擎适配器负责生产标准记录。
- Pi 和第三个引擎使用 `agent_event`。
- Claude 保留原生 record variants。
- 大工具结果在模型折叠前捕获 UI 版本。
- 所有持久化数据先执行 redaction。
- 数据库 `seq_id` 是 SSE 和历史分页游标。
- `engine_sequence` 只表示引擎 attempt 内顺序。

### 5.3 唯一 UI 投影链路

实时：

    SSE AgentRecordV1
      → processV2Record
      → LiveChatStateV1

历史：

    TopicMessage.records
      → processV2Record
      → LiveChatStateV1

最终删除：

- Python `_project_sdk_records()`。
- 前端 `buildV2StateFromStoredBlocks()`。
- `TopicMessage.blocks`。
- block projection 双端 golden fixture。

## 6. 方案对比与取舍

### 6.1 仅修补现有两套投影

优点：

- 单次修改较小。

缺点：

- block 语义长期维护两遍。
- 新增 block 类型必须同时修改 Python 和 JavaScript。
- 必须依赖差分测试防止漂移。
- 第三个引擎接入仍增加跨语言维护成本。

结论：不采用。

### 6.2 后端中央 translator 统一所有引擎

优点：

- 前端输入单一。

缺点：

- 中央模块必须认识每个引擎。
- 第三个引擎仍需修改共享后端。
- Claude 原生流需要额外翻译。
- 容易把引擎细节硬编码进公共运行时。

结论：不采用。

### 6.3 前端按引擎适配

缺点：

- 每个引擎新增 reducer。
- 历史和实时仍可能分叉。
- 无法形成低成本第三引擎协议。

结论：不采用。

### 6.4 AgentRecordV1 + 单一前端 reducer

优点：

- Claude 当前数据模型无需降级。
- Pi 和第三引擎只需实现中性 producer。
- 实时和历史复用同一代码。
- 可删除 Python block 投影和历史 hydration 投影。
- 新增 block 类型只改一处。
- fixture 只验证真实 producer、存储 round-trip 和唯一 reducer。

代价：

- 历史 API 需要从 `blocks` 转为 `records`。
- 历史响应体可能增加。
- rolling deployment 需要短暂的 additive 过渡。
- 前端历史加载需要运行一次纯内存 reducer。

结论：采用。

## 7. AgentRecordV1

### 7.1 顶层信封

    {
      "seq_id": 12345,
      "contract_version": 1,
      "engine_kind": "pi_agent_core",
      "record_type": "agent_event",
      "event_type": "tool.completed",
      "event_id": "evt_xxx",
      "run_id": "run_xxx",
      "task_attempt_id": "attempt_xxx",
      "engine_sequence": 17,
      "occurred_at": "2026-09-10T10:00:00.123Z",
      "turn_index": 2,
      "data": {
        "...": "event-specific payload"
      }
    }

字段定义：

| 字段 | 说明 |
|---|---|
| `seq_id` | 数据库记录 ID；SSE resume 和历史增量加载的唯一游标 |
| `contract_version` | 新写记录固定为 `1` |
| `engine_kind` | `claude_code`、`pi_agent_core` 或未来引擎标识；不能用于选择 UI reducer |
| `record_type` | AgentRecordV1 判别字段 |
| `event_type` | Claude stream type 或中性 AgentEventType |
| `event_id` | 事件身份；新写记录必填 |
| `run_id` | 本次运行身份；新写记录必填 |
| `task_attempt_id` | 本次任务尝试身份；新写记录必填 |
| `engine_sequence` | attempt 内严格递增；新写记录必填 |
| `occurred_at` | 事件发生时间，不是数据库插入时间 |
| `turn_index` | 轮次索引 |
| `data` | record-specific payload |

数据库 `seq_id` 与 `engine_sequence` 不得混用：

- `seq_id` 保证持久化和交付顺序。
- `engine_sequence` 用于引擎协议校验与诊断。

### 7.2 record_type 联合类型

| `record_type` | 状态 | 用途 |
|---|---|---|
| `stream` | 正式支持 | Claude 原生 streaming event |
| `tool_result` | 正式支持 | Claude 工具结果 |
| `permission_request` | 正式支持 | 权限请求 |
| `permission_decision` | 正式支持 | 权限决定 |
| `question_request` | 正式支持 | AskUserQuestion 请求 |
| `question_answer` | 正式支持 | AskUserQuestion 回答 |
| `done` | 正式支持 | Claude 兼容终态 |
| `error` | 正式支持 | 公共错误 |
| `agent_event` | 正式支持 | Pi 和未来引擎 |
| `pi_event` | legacy input only | 已有历史记录；新 writer 禁止写入 |

最终 API read normalizer 将 legacy `pi_event` 转换为 `agent_event` 后再返回前端。`pi_event` 只存在于旧数据库行和兼容测试中。

### 7.3 中性 AgentEventType

v1 包含：

| 类型 | 必要字段 | 语义 |
|---|---|---|
| `run.started` | `topic_id` | 运行开始 |
| `turn.started` | `turn_id` | turn 开始 |
| `content.started` | `turn_id`, `content_id`, `kind` | 内容块开始 |
| `content.delta` | `turn_id`, `content_id`, `kind`, `delta` | 内容增量 |
| `content.completed` | `turn_id`, `content_id`, `kind`，可选 `text` | 内容完成 |
| `tool.started` | `turn_id`, `tool_call_id`, `tool_name`, `input` | 工具开始 |
| `tool.completed` | `turn_id`, `tool_call_id`, `tool_name`, `output`, `is_error` | 工具完成 |
| `tool.denied` | `turn_id`, `tool_call_id`, `tool_name`, `reason` | 工具被拒绝 |
| `usage.updated` | `turn_id`, `usage` | 用量更新 |
| `turn.completed` | `turn_id` | turn 完成 |
| `run.completed` | `terminal_status=success` | 运行成功 |
| `run.failed` | `error_code`, `message` | 运行失败 |
| `run.cancelled` | `reason` | 运行取消 |
| `run.suspended` | `reason` | 运行挂起 |

### 7.4 删除 tool.progress

`tool.progress` 不进入 AgentRecordV1：

- 当前前端不展示。
- 当前后端不消费。
- `tool.started` 已足以驱动“正在执行”状态。
- SSE 自身已有 ping，可用于保持连接和判断传输存活。
- Pi Cell 的 15 秒 synthetic tool progress timer 没有产品语义，只增加事件量和历史体积。

需要删除：

- TS `AgentEventType` 中的 `tool.progress`。
- 前端 `AgentEventType` 中的 `tool.progress`。
- `event-normalizer.ts` 对 `tool_execution_update` 的公共事件输出。
- `cell.ts` 的 synthetic progress interval。
- 对应 tests 和 fixtures。

已有 legacy `tool.progress` 行由 read normalizer 过滤，不传给历史前端。

若未来存在明确的百分比、阶段名或可展示日志，再通过契约评审增加具有明确 payload 和消费者的事件；不能先生产无人消费的公共事件。

### 7.5 事件语义规则

- 每个 run 必须且只能产生一个终态事件。
- `engine_sequence` 在 `task_attempt_id` 内严格递增。
- `tool.denied` 必须携带 `tool_call_id`。
- `content.completed.text` 若存在，是权威最终文本，替换累计 delta。
- `content.completed.text` 缺失时保留累计 delta。
- `tool.completed` 或 `tool.denied` 找不到对应 `tool.started` 时，唯一 reducer 创建 synthetic tool block。
- 未知 v1 event type 在写入边界产生 `AGENT_EVENT_CONTRACT_VIOLATION`。
- 引擎私有信息只能进入 `data.extensions[engine_kind]`。
- reducer 对同一 `event_id` 的重复记录保持幂等。

## 8. Tool output 契约

### 8.1 选择

采用：

- `output` 保持 SDK 兼容形状。
- `output_meta` 作为兄弟字段。

不采用 `{text, parts, structured, meta}` 包裹。

原因：

1. Claude Code 现有 output 无需重写。
2. 当前 `ToolOutputRenderer` 已支持字符串、content block 数组和顶层 `kind` 对象。
3. Pi 的 `result.content` 解包后，现有数组分支即可解析 `chart_spec` 和 `sql_execution`。
4. 新包裹会让现有 renderer 看不到顶层 `kind`，要求增加解包逻辑。
5. `kind` 是业务输出判别字段，不是 meta。
6. 兄弟字段是对 Claude 模型的加法扩展，符合超集约束。

### 8.2 最终形态

    {
      "turn_id": "turn-2",
      "tool_call_id": "toolu_123",
      "tool_name": "Bash",
      "output": [
        {
          "type": "text",
          "text": "{\"kind\":\"chart_spec\", ...}"
        }
      ],
      "output_meta": {
        "exit_code": 0,
        "byte_count": 18420,
        "truncated": false,
        "model_context_folded": true,
        "ui_truncated": false,
        "result_ref": "res_xxx",
        "original_bytes": 18420,
        "engine_details": {}
      },
      "is_error": false
    }

`output` 允许：

- `null`
- 字符串
- SDK 兼容 content block 数组
- 顶层带 `kind` 的平台结构化对象
- Claude 当前合法产生的其它 JSON 可序列化形状

`output_meta` 公共字段使用 snake_case：

| 字段 | 说明 |
|---|---|
| `exit_code` | 命令退出码 |
| `byte_count` | 当前 output 字节数 |
| `truncated` | 工具自身是否截断 |
| `model_context_folded` | 模型上下文副本是否折叠 |
| `ui_truncated` | UI 持久化副本是否截断 |
| `result_ref` | 完整结果引用 |
| `original_bytes` | 原始字节数 |
| `content_types` | content block 类型摘要 |
| `engine_details` | redaction 后的引擎扩展 details |

Pi 映射：

    output = result.content
    output_meta = normalize_output_meta(result.details)
    is_error = piEvent.isError

不能只提取 text，必须保留所有 content blocks。

### 8.3 平台结构化输出

- 直接结构化对象继续保持顶层 `kind`。
- Bash/MCP 返回的结构化 JSON 保留在 text content block 中。
- `ToolOutputRenderer.normalizeOutput()` 继续复用现有字符串和数组解析。
- `kind` 不放入 `output_meta`。
- 恢复图表和表格不修改 renderer 主逻辑，只补真实 Pi output 回归测试。

## 9. 模型折叠与 UI 持久化解耦

### 9.1 双结果模型

每次 Pi 工具调用形成：

- `ui_result`：用于 AgentRecordV1 持久化和历史重放。
- `model_result`：用于下一轮模型上下文，可折叠。

二者不能共享可变对象。

### 9.2 原子流程

在 `afterToolCall` 收到原始结果时：

1. redaction 原始结果。
2. 深复制为 `ui_result`。
3. 用 `tool_call_id` 注册到运行内的 `UiToolResultRegistry`。
4. 判断模型上下文是否需要折叠。
5. 必要时生成 `model_result` 摘要和 `result_ref`。
6. 只将 `model_result` 返回给 Agent loop。
7. `EventNormalizer` 收到 `tool_execution_end` 时从 registry 取 `ui_result`。
8. 生成 `output` 和 `output_meta`。
9. 写入成功或 run 终止后清理 registry。

捕获、折叠、normalizer 和 registry 清理必须一起上线。

### 9.3 大结果规则

- 未超过持久化硬上限的 `chart_spec`、`sql_execution` 和 `sql_export` 必须完整写入 UI output。
- 模型上下文可以使用摘要，但不能反向覆盖 UI output。
- 初始持久化硬上限与查询结果守卫保持一致，采用 512KB。
- 通用超大文本：
  - 完整结果进入 ResultStore；
  - UI output 保存有界预览；
  - `ui_truncated=true`；
  - `result_ref` 指向完整结果。
- 合法 `chart_spec` 超过硬上限时显式返回 `STRUCTURED_OUTPUT_TOO_LARGE`，不能把摘要当作图表持久化。

## 10. 任务状态契约

### 10.1 平台 TaskStatus

允许持久化：

| 状态 | 类型 |
|---|---|
| `waiting` | 活跃 |
| `running` | 活跃 |
| `waiting_input` | 活跃/停驻 |
| `waiting_permission` | 活跃/停驻 |
| `finished` | 终态成功 |
| `error` | 终态失败 |
| `suspended` | 终态取消或挂起 |

统一终态集合：

    {"finished", "error", "suspended"}

### 10.2 引擎结果映射

| EngineOutcome | TaskStatus |
|---|---|
| `success` | `finished` |
| `failed` | `error` |
| `cancelled` | `suspended` |
| `suspended` | `suspended` |

`success/failed/cancelled` 不得写入 task status 列。

Assistant message status 是独立契约，已有 `success` 值可以继续兼容。

### 10.3 下游映射

| TaskStatus | DownstreamStatus |
|---|---|
| `finished` | `completed` |
| `error` | `failed` |
| `suspended` | `suspended` |

`finish_task()` 必须使用穷举映射。未知终态返回 `INVALID_TERMINAL_TASK_STATUS`，不能使用 `else → failed`。

## 11. 历史直接重放

### 11.1 最终 TopicMessage 形态

Assistant message 从：

    {
      "content": "...",
      "blocks": [...],
      "resume_after_seq": 123
    }

调整为：

    {
      "content": "...",
      "records": [
        {"record_type": "...", "...": "..."}
      ],
      "resume_after_seq": 123
    }

规则：

- `records` 按 `seq_id` 严格升序。
- `resume_after_seq` 等于已返回记录最大 `seq_id`。
- 运行中的历史消息可先重放已有 records，再从该游标继续 SSE。
- user message 不返回 records。
- 无事件记录的旧 assistant message使用 `content` 构造一组仅供 reducer 使用的 synthetic Claude text records。

### 11.2 后端职责

后端历史读取只负责：

1. 按消息页关联的 task IDs 批量读取记录。
2. 将数据库行解码为 AgentRecordV1。
3. 应用唯一一层 legacy record normalizer。
4. 按 task 分组和 `seq_id` 排序。
5. 返回 records。

后端不再：

- 创建 thinking/text/tool block。
- 合并 content delta。
- 解释 content completed。
- 创建 synthetic tool block。
- 解释 question/permission UI 状态。
- 维护 block index。

### 11.3 前端职责

`hydrateMessageFromApi()`：

1. 创建 `createChatState()`。
2. 对 `message.records` 顺序调用 `processV2Record()`。
3. 若记录为空且 `message.content` 非空，通过 synthetic Claude text records 调用同一 reducer。
4. 使用 message 的终态状态作为缺失终态记录时的兼容收口。
5. 不实现第二套 block 构造逻辑。

### 11.4 删除项

最终删除：

- `topic_task_store.py::_project_sdk_records`
- `_load_task_history_views` 中的 block projection
- `chatMessage.js::buildV2StateFromStoredBlocks`
- `TopicMessage.blocks`
- `test_sdk_block_projection_contract.py`
- `sdkBlockProjection.contract.spec.js`
- 只用于两套投影对齐的 canonical block fixture

保留并升级为：

- producer contract fixture
- storage round-trip fixture
- single reducer state fixture
- history replay integration test

## 12. 历史传输和分页

### 12.1 查询策略

- 继续先分页读取 `da_agent_message`。
- 收集当前页 assistant task IDs。
- 使用一次批量 SQL 查询这些 task 的 AgentRecordV1。
- 不按每条 message 单独查询。
- records 按 task 分组后附加到对应 assistant message。

这与当前加载原始 records 再投影的数据库成本相同，只删除了 Python 投影步骤。

### 12.2 响应体控制

- 启用或确认 API/proxy gzip。
- legacy `tool.progress` 在 read normalizer 中过滤。
- message page size 保持有界。
- 对 20、100、200 条 assistant message 的真实历史进行响应字节数和 hydration 耗时基准。
- 前端按 message 增量重放，避免一次深响应阻塞。

若真实基准超出可接受阈值：

- 优先增加按 task 的 records 分页或懒加载。
- 允许前端在消息进入视口时加载对应 records。
- 不恢复 Python block projection。
- 不新增第二套语义 reducer。

## 13. Prompt caching 与上下文裁剪

### 13.1 显式缓存配置

新增：

- `DATAAGENT_PI_CACHE_RETENTION=short|long|off`
- 默认 `short`

传递链路：

    backend settings
      → PiRunContext
      → CellInitPayload.model.cache_retention
      → pi-ai stream options

`DISABLE_PROMPT_CACHING=true` 映射为 `off`。未设置时不覆盖显式 retention。

### 13.2 稀疏、带状态 compaction

新增：

- high watermark：默认 `max_context_tokens` 的 90%。
- target watermark：默认 70%。

算法：

1. 未超过 high watermark：
   - 原样返回 messages；
   - 不改写历史前缀。
2. 首次越线：
   - 建立 compaction generation；
   - 将上下文压缩到 target 以下；
   - 固定 compacted prefix 和裁剪边界。
3. 未再次越线：
   - 复用 compacted prefix；
   - 只追加新消息；
   - 不随数组尾部移动 protect tail。
4. 再次越线：
   - 创建下一 generation；
   - 允许发生一次新的 cache miss。

`protectTailCount` 只在创建 generation 时计算。

### 13.3 可观测性

记录：

- context tokens before/after
- prune triggered
- compaction generation
- compacted message count
- cache retention
- cache read tokens
- cache creation tokens

只有真实 provider 测试出现稳定前缀 cache read 后，才能宣称 prompt caching 修复完成。

## 14. 接口和存储影响

### 14.1 数据库

保留 `da_agent_sdk_record`，增加 nullable 字段：

- `contract_version SMALLINT`
- `engine_kind VARCHAR(32)`
- `event_id VARCHAR(128)`
- `run_id VARCHAR(128)`
- `task_attempt_id VARCHAR(128)`
- `engine_sequence BIGINT`
- `occurred_at DATETIME(3)`

旧行无需 JSON 重写。

### 14.2 TopicMessage API

过渡期：

- 增加 `records`。
- 暂时保留 `blocks`，标记 deprecated。

最终：

- 删除 `blocks`。
- 只返回 `records`。
- `content` 继续作为最终回答和无事件历史的兼容来源。

这是公共响应契约变化，需要同步更新相关文档和客户端测试。

### 14.3 Follow-up suggestions

- `_message_answer_text()` 改为使用 `message.content`。
- 删除对 UI blocks 的依赖。
- `_message_result_summary()` 不再从 blocks 推导。
- 当前阶段向 suggestion generator 传空 result summary。
- 若未来需要工具摘要，增加显式持久化字段，不恢复 Python UI projection。

## 15. 契约强制

### 15.1 单一事实源

新增：

    dataagent/contracts/agent-events/v1/
      agent-record.schema.json
      neutral-event.schema.json
      tool-output.schema.json
      task-status.schema.json
      producer-cases.json
      reducer-cases.json
      invalid-cases.json
      README.md

JSON Schema 2020-12 是跨语言事实源。

### 15.2 测试分层

#### Producer contract

引擎原生事件：

    engine event → adapter → AgentRecordV1

Pi fixture 必须使用真实 `{content, details}` AgentToolResult，不允许预先手工解包。

#### Storage round-trip contract

    AgentRecordV1
      → Python writer
      → persisted row
      → read normalizer
      → AgentRecordV1

断言除数据库 `seq_id` 和明确允许的 legacy 补全字段外，语义不变。

Python 不生成 blocks。

#### Single reducer contract

    AgentRecordV1[]
      → processV2Record
      → LiveChatStateV1

同一测试同时覆盖实时和历史，因为两者调用同一个 reducer。

#### History API integration

    persisted AgentRecordV1[]
      → topic messages API
      → message.records
      → processV2Record
      → LiveChatStateV1

该测试验证传输、分组和顺序，不引入第二个 expected projection。

### 15.3 生产校验

- TS producer 在发出 `agent_event` 前执行轻量断言。
- Python writer 在写入前验证完整信封。
- 非法事件使当前 run 失败为 `AGENT_EVENT_CONTRACT_VIOLATION`。
- 未知事件不能静默落库。
- 第三个引擎必须通过同一 conformance suite。

## 16. 已有 pi_event 兼容

### 16.1 单层 read normalizer

数据库读取时：

1. `pi_event` 映射为 `agent_event`。
2. 缺失信封字段从数据库行补全：
   - `contract_version=1`
   - `engine_kind=pi_agent_core`
   - `event_id=legacy:<task_id>:<seq_id>`
   - `run_id=<task_id>`
   - `task_attempt_id=<task_id>:legacy`
   - `engine_sequence=<seq_id>`
   - `occurred_at=<created_at>`
3. legacy output 若为 `{content,details}`：
   - `output=content`
   - `output_meta=normalize(details)`
4. legacy `tool.progress` 记录过滤。
5. 其它记录保持顺序。

该 normalizer 同时服务：

- SSE 分页。
- SSE stream。
- topic history records。
- 后端调试/验证脚本。

不保留第二层 frontend legacy output 解包。

### 16.2 Writer 策略

- 新 Pi writer 只写 `agent_event`。
- Claude writer 写完整 AgentRecordV1 信封，但保留原生 record type。
- 不双写。
- 不批量改写旧 event JSON。

## 17. 历史任务状态修复

幂等修复：

- task `success → finished`
- task `cancelled → suspended`
- topic `success → finished`
- topic `cancelled → suspended`
- 成功 task 对应的 queue `failed → completed`
- 成功 task 对应的 schedule log `failed → completed`
- 清理这些成功链路上的错误文本

修复必须通过 task ID、queue ID、schedule log ID 和空 error 条件约束范围。

## 18. 风险

### 18.1 历史响应变大

风险：raw delta records 比 projected blocks 大。

缓解：

- 批量查询但分页返回。
- gzip。
- 过滤 legacy progress。
- 增量 replay。
- 真实数据基准。
- 必要时按 task 分页或懒加载，不恢复 Python 投影。

### 18.2 rolling deployment

风险：旧前端不识别 `records/agent_event`。

缓解：

1. 后端先增加 `records`，暂保留 blocks。
2. 前端切换到 records。
3. 确认没有旧客户端后删除 blocks 和 Python 投影。
4. 最后切换新 writer，或在前端双读就绪后切换。

### 18.3 UI 持久化体积

缓解：

- 512KB 硬上限。
- ResultStore。
- structured output 完整性检查。
- 监控单记录和单 task 字节数。

### 18.4 敏感信息

缓解：

- UI result 捕获前 redaction。
- output_meta 扩展字段 redaction。
- 只保存工作区相对路径。
- 禁止环境变量和 credential 进入 extensions。

### 18.5 缓存行为改变

缓解：

- 独立阶段。
- 真实 cache usage 指标。
- 可将新 pruning mode 回退为 off。
- 不回退到每轮漂移的旧算法。

## 19. 完成定义

满足以下条件才算完成：

- Pi task status 只写合法平台状态。
- SSE 在所有终态正常关闭。
- queue/schedule 状态与 task 一致。
- Pi 表格和图表实时、刷新后均正常。
- UI 结果不再被模型上下文折叠覆盖。
- Pi 和 Claude 新记录保存完整 AgentRecordV1 信封。
- topic history 返回 records 并由 `processV2Record()` 重放。
- `_project_sdk_records()` 已删除。
- `buildV2StateFromStoredBlocks()` 已删除。
- `TopicMessage.blocks` 已删除。
- `tool.progress` 已从 v1 和生产端删除。
- legacy `pi_event` 无需数据重写即可回放。
- `_build_history` 回归快照保持不变。
- prompt caching 使用显式配置和水位 compaction。
- 第三个引擎无需修改前端或 Python 投影即可通过 conformance suite。
