# 统一 Agent 事件契约实施计划

- 日期：2026-09-10
- 状态：待评审
- 对应设计：`docs/design/2026-09-10-unified-agent-event-contract-design.md`
- 主题：unified-agent-event-contract

## 1. 实施原则

1. 阶段 0 是已确认的生产缺陷止血，不必等待整体方案定稿即可实施。
2. 验证基础设施必须先恢复，否则“全量测试通过”没有意义。
3. 状态修复和普通 output 解包可独立上线。
4. 新 writer 只能在 reader 支持 AgentRecordV1 后启用。
5. UI result 捕获和模型结果折叠必须原子落地。
6. 历史切换到 records 后必须删除两套旧投影。
7. Prompt caching 独立发布。
8. 不修改 `da_agent_message → _build_history`。
9. 不以长期兼容名义保留 Python block projection。
10. 回滚优先保留新增 reader 和 nullable schema，不删除数据。

## 2. 阶段总览

| 阶段 | 内容 | 可否独立上线 |
|---|---|---:|
| -1 | 清理有害 pymysql 测试桩，恢复测试收集 | 是，所有验证的前置项 |
| 0 | Pi task status 止血 | 是，不必等整体方案定稿 |
| 0B | 修复历史 task/downstream 状态 | 是，在阶段 0 后 |
| 1 | Pi output 解包和 legacy output 兼容 | 是 |
| 2 | AgentRecordV1 schema、存储字段、reader 双读、删除 progress | 是 |
| 3 | Claude/Pi writer 保存完整信封，Pi 切换 agent_event | 必须在阶段 2 后 |
| 4 | 模型折叠与 UI 持久化解耦 | 内部修改必须原子上线 |
| 5 | 历史直接重放并删除两套旧投影 | 前后端协同迁移 |
| 6 | Prompt caching 水位 compaction | 独立上线 |
| 7 | 第三引擎模板和端到端验收 | 前述阶段完成后 |

## 3. 阶段 -1：恢复测试收集

### 3.1 原因

当前 backend 全量测试在 collection 阶段出现 13 个错误。

根因：

- 7 个测试文件将 `sys.modules["pymysql"]` 替换为 `types.SimpleNamespace`。
- `core/eval_store.py` 使用 `import pymysql.cursors`。
- SimpleNamespace 不是 Python package，导致后续模块无法导入 `pymysql.cursors`。
- 项目虚拟环境已经安装真实 PyMySQL，这些模块级桩没有必要且污染整个 pytest collection。

在该问题修复前，不能把 backend 全量测试作为本方案验收条件。

### 3.2 触达文件

移除模块级 pymysql 桩：

- `dataagent/dataagent-backend/tests/test_admin_routes.py`
- `dataagent/dataagent-backend/tests/test_auth_routes.py`
- `dataagent/dataagent-backend/tests/test_auth_config.py`
- `dataagent/dataagent-backend/tests/test_readonly_query_proxy.py`
- `dataagent/dataagent-backend/tests/test_runtime_excludes_eval_api.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_store.py`
- `dataagent/dataagent-backend/tests/test_widget_runtime_routes.py`

### 3.3 修改规则

- 直接使用 `.venv-py313` 中的真实 PyMySQL package。
- 需要隔离数据库访问时，patch 具体连接函数、store factory 或 `_connect()`。
- 禁止修改 `sys.modules["pymysql"]`。
- 测试不能依赖 collection 顺序。
- 单文件运行和全量收集必须得到一致 import 行为。

### 3.4 验证

先验证依赖：

    cd dataagent/dataagent-backend
    .venv-py313/bin/python -c \
      "import fastapi, uvicorn, alembic, pymysql, pymysql.cursors, anyio"

验证收集：

    .venv-py313/bin/python -m pytest tests/ --collect-only -q

验收：

- collection errors：0
- collection 顺序不影响结果
- 7 个文件可分别单独收集
- 全量测试可以进入执行阶段

再运行：

    .venv-py313/bin/python -m pytest tests/ -q

报告必须包含：

- collected
- passed
- failed
- errors
- skipped

### 3.5 回滚

该阶段不应回滚到 `sys.modules` 污染方式。若某个测试需要特殊数据库替身，改为局部 monkeypatch。

## 4. 阶段 0：Pi 状态止血

> 本阶段不必等待整体设计和后续重构定稿，可以立即实施、验证和发布。

### 4.1 目标

修复：

- Pi 成功后 SSE 不关闭。
- UI 持续显示进行中。
- Pi 成功被 queue/schedule 标记为失败。
- Pi 取消写入非法 `cancelled` task status。

### 4.2 触达文件

建议新增：

- `dataagent/dataagent-backend/core/task_status.py`

修改：

- `dataagent/dataagent-backend/core/task_executor.py`
- `dataagent/dataagent-backend/core/topic_task_store.py`
- `dataagent/dataagent-backend/api/routes.py`
- 对应 tests

### 4.3 实现

集中定义：

    ACTIVE_TASK_STATUSES = {
      "waiting",
      "running",
      "waiting_input",
      "waiting_permission"
    }

    TERMINAL_TASK_STATUSES = {
      "finished",
      "error",
      "suspended"
    }

引擎映射：

- `success → finished`
- `failed → error`
- `cancelled → suspended`
- `suspended → suspended`

下游映射：

- `finished → completed`
- `error → failed`
- `suspended → suspended`

`finish_task()` 对未知终态返回 `INVALID_TERMINAL_TASK_STATUS`，删除当前 else fallback。

### 4.4 测试

修改：

- `tests/test_pi_runtime_dispatch.py`
- `tests/test_task_coordinator.py`
- `tests/test_topic_task_store.py`
- `tests/test_routes_contract.py`

覆盖：

1. Pi success 返回 finished。
2. Pi failed 返回 error。
3. Pi cancelled 返回 suspended。
4. finished 同步为 topic finished。
5. finished 对应 queue completed。
6. finished 对应 schedule log completed。
7. error 对应 failed。
8. suspended 对应 suspended。
9. 非法终态不落库。
10. `run.completed + finished` 使 SSE 关闭。
11. frontend subscription 返回并清空 activeTaskId。

### 4.5 验证命令

后端：

    cd dataagent/dataagent-backend
    .venv-py313/bin/python -m pytest \
      tests/test_pi_runtime_dispatch.py \
      tests/test_task_coordinator.py \
      tests/test_topic_task_store.py \
      tests/test_routes_contract.py -q

前端：

    export NVM_DIR="/Users/guoruping/.nvm"
    . "$NVM_DIR/nvm.sh"
    nvm use
    cd dataagent/dataagent-frontend
    npx vitest run \
      src/views/intelligence/__tests__/useNl2SqlChat.spec.js \
      src/views/intelligence/__tests__/NL2SqlChatV2.spec.js

### 4.6 回滚

- 不允许回滚为持久化 `success/cancelled`。
- 若共享模块出现问题，可临时内联正确映射。
- 不修改数据库 schema。

## 5. 阶段 0B：历史状态修复

### 5.1 触达文件

新增：

- `dataagent/dataagent-backend/alembic/versions/<revision>_normalize_agent_task_status.py`

### 5.2 数据修复

迁移前统计：

- task success/cancelled 数量
- topic success/cancelled 数量
- 成功 task 但 queue failed 数量
- 成功 task 但 schedule log failed 数量

幂等修复：

- task `success → finished`
- task `cancelled → suspended`
- topic `success → finished`
- topic `cancelled → suspended`
- 无 error 的 finished task 对应 queue `failed → completed`
- 无 error 的 finished task 对应 schedule log `failed → completed`
- 清理对应 error message
- schedule 的 `last_error_message` 在 `last_task_id` 指向成功 task 时清空

所有修复必须通过真实关联 ID 约束。

### 5.3 验证

迁移后断言：

- task 非法状态数为 0
- topic 非法状态数为 0
- 不存在 finished task 对应 failed queue/schedule log
- error task 的 failed 状态不被误改
- migration 重复运行结果不变

### 5.4 回滚

状态纠错不执行逆迁移。误修只能依据迁移前审计结果做定向补偿。

## 6. 阶段 1：Pi output 解包

### 6.1 目标

零改动前端主 renderer，恢复普通大小的图表、表格和文本输出。

### 6.2 触达文件

Pi：

- `dataagent/dataagent-runtime-pi/src/kernel/event-normalizer.ts`

后端 legacy 兼容：

- `dataagent/dataagent-backend/core/topic_task_store.py`
- 建议新增 `dataagent/dataagent-backend/core/agent_record_compat.py`

前端仅补测试；不修改 `ToolOutputRenderer.vue` 主解析逻辑。

### 6.3 实现

Pi `tool_execution_end`：

    output = piEvent.result.content
    output_meta = normalize(piEvent.result.details)
    is_error = piEvent.isError

要求：

- content 数组原样保留。
- image/non-text block 不丢失。
- details 转 snake_case 公共字段。
- 未识别 details 经 redaction 后进入 `engine_details`。

Legacy reader：

- `{content,details}` → `output + output_meta`
- 新格式不二次转换
- SSE 和 history records 使用同一个 helper

### 6.4 测试

Pi producer：

- 真实 AgentToolResult 对象。
- content 数组输出。
- output_meta。
- image block。
- error。
- redaction。

前端：

- Pi content 数组中的 chart spec 渲染 ChartSpecView。
- SQL execution 渲染 ResultDataTable。
- SDK 字符串 chart spec 保持正常。
- 不显示整个 `{content,details}` JSON。

后端：

- legacy output 经 SSE 读取后解包。
- legacy output 经 topic history records 读取后解包。
- 新 output 不被二次处理。

### 6.5 回滚

- legacy reader 保留。
- runtime writer 可回滚，但会重新出现 JSON 展示问题。
- 无历史数据改写。

## 7. 阶段 2：AgentRecordV1 契约与 reader 双读

### 7.1 新增契约

新增：

- `dataagent/contracts/agent-events/v1/agent-record.schema.json`
- `dataagent/contracts/agent-events/v1/neutral-event.schema.json`
- `dataagent/contracts/agent-events/v1/tool-output.schema.json`
- `dataagent/contracts/agent-events/v1/task-status.schema.json`
- `dataagent/contracts/agent-events/v1/producer-cases.json`
- `dataagent/contracts/agent-events/v1/reducer-cases.json`
- `dataagent/contracts/agent-events/v1/invalid-cases.json`
- `dataagent/contracts/agent-events/v1/README.md`

### 7.2 数据库字段

新增 Alembic migration：

- contract_version
- engine_kind
- event_id
- run_id
- task_attempt_id
- engine_sequence
- occurred_at

更新：

- `core/topic_task_store.py`
- `models/schemas.py`

字段先设 nullable，兼容旧行。

### 7.3 Reader 双读

后端 read normalizer：

- 识别旧 `pi_event`。
- 输出时转换为 `agent_event`。
- 补全 legacy 信封。
- legacy tool output 解包。
- 过滤 legacy `tool.progress`。

前端过渡期：

- `processV2Record()` 同时接受 `pi_event` 和 `agent_event`。
- 两者进入同一 neutral reducer。

### 7.4 删除 tool.progress

修改：

- `dataagent-runtime-pi/src/protocol/frames.ts`
- `dataagent-runtime-pi/src/kernel/event-normalizer.ts`
- `dataagent-runtime-pi/src/kernel/cell.ts`
- `dataagent-frontend/src/views/intelligence/agentEvents/reducer.js`
- 对应 tests

删除：

- `tool.progress` 类型。
- `tool_execution_update` 对外事件。
- 15 秒 synthetic progress timer。
- 无消费者 fixture。

保留：

- SSE ping。
- `tool.started` 驱动的运行状态。

### 7.5 契约测试

三层测试：

1. TS producer contract。
2. Python storage round-trip。
3. JS single reducer contract。

不再增加 Python block semantic projection 测试。

### 7.6 发布顺序

1. 数据库 migration。
2. 后端 read normalizer。
3. 前端双读。
4. Pi writer 暂时仍写 `pi_event`。
5. 观察 legacy normalization 和 SSE。

## 8. 阶段 3：完整信封与 agent_event writer

### 8.1 目标

阶段完成后，Claude 和 Pi 的所有新记录都具有完整 AgentRecordV1 信封；Pi 新写 `agent_event`。

### 8.2 触达文件

Pi：

- `dataagent-runtime-pi/src/protocol/frames.ts`
- `dataagent-runtime-pi/src/kernel/run-state-machine.ts`
- `dataagent-runtime-pi/src/kernel/event-normalizer.ts`

Python：

- `dataagent-backend/core/pi_event_writer.py`
- `dataagent-backend/core/sdk_block_writer.py`
- `dataagent-backend/core/pi_runtime.py`
- `dataagent-backend/core/task_executor.py`
- `dataagent-backend/core/topic_task_store.py`

### 8.3 实现

控制面为每次执行提供：

- `run_id`
- `task_attempt_id`

Writer 维护：

- event_id
- engine_sequence
- occurred_at
- engine_kind
- contract_version

Pi：

- `record_type="agent_event"`
- `engine_kind="pi_agent_core"`

Claude：

- 保持 `stream/tool_result/done/...`
- `engine_kind="claude_code"`
- 同样补全信封

修复：

- `tool.denied` 必须使用真实 `tool_call_id`。
- orphan `tool.completed/denied` 交给唯一 reducer 创建 synthetic block。
- producer schema 违规终止为 `AGENT_EVENT_CONTRACT_VIOLATION`。

### 8.4 测试

Producer contract 覆盖：

- text/reasoning。
- content final text。
- tool success/error/denied。
- output/output_meta。
- usage。
- completed/failed/cancelled/suspended。
- sequence。
- 单一终态。
- schema violation。

Storage round-trip 覆盖：

    AgentRecordV1
      → writer
      → database-shaped row
      → read normalizer
      → equivalent AgentRecordV1

跨进程覆盖：

    real Pi Cell
      → Python reader
      → PiEventWriter
      → persisted AgentRecordV1

### 8.5 发布与回滚

前置：

- 阶段 2 双读已上线。

发布后：

- Pi 新写 `agent_event`。
- 监控新旧 record type 写入量。

回滚：

- Pi writer 可退回 `pi_event`。
- reader 继续双读。
- 已写 `agent_event` 不删除。
- 禁止双写。

## 9. 阶段 4：折叠与 UI 持久化解耦

### 9.1 必须原子落地

必须一起上线：

- 原始 UI result 捕获。
- registry。
- model result 折叠。
- normalizer registry lookup。
- output_meta。
- registry cleanup。
- 完整图表回放测试。

### 9.2 触达文件

- `dataagent-runtime-pi/src/kernel/cell.ts`
- `dataagent-runtime-pi/src/kernel/event-normalizer.ts`
- `dataagent-runtime-pi/src/context/result-store.ts`
- `dataagent-runtime-pi/src/context/tabular-digest.ts`
- 新增 `dataagent-runtime-pi/src/kernel/ui-tool-result-registry.ts`
- 对应 tests

### 9.3 实现

1. `afterToolCall` 首先复制 redacted `ui_result`。
2. 以 `tool_call_id` 注册。
3. 再生成可折叠 `model_result`。
4. Agent loop 只接收 model result。
5. EventNormalizer 优先持久化 ui result。
6. registry miss 记录指标并使用 event result。
7. run 终止时清理 registry。

### 9.4 测试

覆盖：

- 小结果。
- 大普通文本。
- SQL 结果。
- 完整 chart spec。
- result_ref。
- registry miss。
- failure/cancel cleanup。
- redaction。
- UI output 与模型折叠副本不共享引用。

### 9.5 回滚

紧急情况下关闭模型结果折叠，不允许以丢失 UI 图表为代价回滚。

## 10. 阶段 5：历史直接重放与投影删除

### 10.1 目标

历史消息携带 AgentRecordV1 records，前端复用 `processV2Record()`；最终删除两套旧投影。

### 10.2 子阶段 5A：后端 additive records API

修改：

- `dataagent-backend/core/topic_task_store.py`
- `dataagent-backend/models/schemas.py`
- `dataagent-backend/api/routes.py`
- `tests/test_topic_task_store.py`
- `tests/test_routes_contract.py`

实现：

- 将 `_load_task_history_views()` 替换为 `_load_task_event_records()`。
- 当前 message 页的 assistant task IDs 使用一次批量查询。
- 返回按 `seq_id` 排序的 `records`。
- `resume_after_seq` 为最大 seq_id。
- 过渡期继续返回 blocks，供旧前端使用。
- 新 records 必须经过统一 read normalizer。

Follow-up suggestions：

- `_message_answer_text()` 直接读 `message.content`。
- 删除对 blocks 的依赖。
- `_message_result_summary()` 不再从 UI block 推导。
- 当前传空 result summary。

验证：

- 无 N+1。
- records 分组正确。
- active task 可从 resume cursor 接续 SSE。
- legacy Pi rows返回规范化 agent_event。

### 10.3 子阶段 5B：前端切换唯一 reducer

修改：

- `dataagent-frontend/src/views/intelligence/chatMessage.js`
- `dataagent-frontend/src/views/intelligence/useNl2SqlChat.js`
- portal/widget tests

实现：

- `hydrateMessageFromApi()` 创建 `createChatState()`。
- 对 `message.records` 顺序调用 `processV2Record()`。
- 不再读取 `message.blocks` 构建状态。
- 无 records 的旧消息通过 synthetic Claude text records 进入同一 reducer。
- running message 重放完成后从 `resume_after_seq` 继续 SSE。
- portal 和 widget 共享同一 hydration helper。

必须验证：

- 多 turn。
- question pending/answered。
- permission。
- thinking/text。
- tool success/error/denied。
- orphan completion。
- content final overwrite。
- usage。
- terminal。
- chart/table。
- running history reattach。

### 10.4 子阶段 5C：删除重复投影

在 5B 已部署并确认无旧前端后删除：

后端：

- `_project_sdk_records()`
- `_load_task_history_views()`
- `TopicMessage.blocks`
- `_message_answer_text()` 的 blocks 分支
- `_message_result_summary()` 的 blocks 逻辑
- `test_sdk_block_projection_contract.py`
- 只服务 block projection 的 helpers

前端：

- `buildV2StateFromStoredBlocks()`
- blocks hydration 分支
- `sdkBlockProjection.contract.spec.js`

契约：

- 删除或归档 `sdk-block-projection/cases.json`
- reducer cases 迁入 `contracts/agent-events/v1/reducer-cases.json`

完成后必须通过代码搜索确认：

    rg "_project_sdk_records|buildV2StateFromStoredBlocks|sdk-block-projection" dataagent

预期：

- 生产代码零命中。
- 仅迁移说明或历史文档可保留命中。

### 10.5 单一 reducer 测试

保留一套语义 fixture：

    AgentRecordV1[] → processV2Record → LiveChatStateV1

实时测试直接使用 fixture。

历史测试：

1. 后端返回相同 records。
2. `hydrateMessageFromApi()` 调用同一个 reducer。
3. 断言得到同一个 expected state。

Python 只测试：

- records 查询。
- 顺序。
- legacy normalization。
- storage round-trip。
- API contract。

Python 不测试 block 语义。

### 10.6 性能验证

使用真实历史数据分别测试：

- 20 条 assistant messages。
- 100 条 assistant messages。
- 200 条 assistant messages。
- 含长 thinking/delta 的 topic。
- 含大型 SQL/chart output 的 topic。

记录：

- 数据库查询次数。
- SQL 耗时。
- JSON 原始字节数。
- gzip 后字节数。
- API 总耗时。
- 浏览器 replay 耗时。
- 首屏可交互时间。

若超过当前页面预算：

- 增加 records 分页或视口懒加载。
- 不恢复 Python projection。

### 10.7 回滚

5A：

- records 是 additive，可直接保留。

5B：

- 过渡期可回滚前端继续读 blocks。

5C：

- 只在 5B 稳定后执行。
- 若删除后需要回滚，优先回滚整次后端版本；不长期恢复双投影。
- nullable schema 和 records API 保留。

## 11. 阶段 6：Prompt caching 水位 compaction

### 11.1 触达文件

后端：

- `dataagent-backend/config.py`
- `dataagent-backend/core/task_executor.py`
- `dataagent-backend/core/pi_runtime.py`
- `deploy/` 环境模板

Pi：

- `dataagent-runtime-pi/src/protocol/frames.ts`
- `dataagent-runtime-pi/src/kernel/cell.ts`
- `dataagent-runtime-pi/src/context/context-pruner.ts`
- `dataagent-runtime-pi/src/providers/stream-fn-resolver.ts`
- 新增 stateful compaction session

### 11.2 配置

新增：

- `DATAAGENT_PI_CACHE_RETENTION=short|long|off`
- `DATAAGENT_CONTEXT_PRUNE_HIGH_WATERMARK_RATIO=0.90`
- `DATAAGENT_CONTEXT_PRUNE_TARGET_RATIO=0.70`

校验：

    0 < target < high <= 1

非法配置启动失败。

### 11.3 实现

- cache retention 显式进入 Cell init 和 pi-ai stream options。
- `DISABLE_PROMPT_CACHING=true` 映射为 off。
- 低于 high watermark 原样返回 messages。
- 越线时生成一次 compaction generation。
- 压缩到 target 以下。
- 固定 compacted prefix 和 protect-tail 边界。
- 未再次越线时只追加新消息。
- 再次越线时才生成下一代 compaction。
- 不修改 `_build_history`。

### 11.4 测试

单元测试：

- 低于 high 时字节不变。
- 连续多轮稳定前缀不变。
- 第一次越线只 compact 一次。
- 未再次越线 generation 不变。
- 第二次越线 generation 增加。
- target 生效。
- protect tail 不漂移。
- off/short/long 正确传递。
- `_build_history` snapshot 不变。

真实 provider：

- 至少三轮稳定长前缀。
- 记录 cache creation/read。
- 未 prune 的后续轮次应出现 cache read。
- compaction 后允许一次 miss。
- 下一稳定轮次应恢复 cache read。

没有真实 provider 时不得宣称缓存命中完成验证。

### 11.5 回滚

- 新 pruning mode 可切换为 off。
- 不回滚到每轮漂移裁剪。
- cache retention 可独立调整。

## 12. 阶段 7：第三引擎接入模板

第三引擎只实现：

1. 原生事件 → `agent_event`。
2. output → SDK 兼容 output。
3. output_meta。
4. EngineOutcome → TaskStatus。
5. event envelope。
6. producer conformance tests。
7. 真实 adapter round-trip test。

禁止要求：

- 修改 `processV2Record()` 的 engine kind 分支。
- 修改 Python block projection；该模块已不存在。
- 修改 `ToolOutputRenderer.vue` 来识别引擎。
- 修改 `_build_history`。

验收：

- producer schema 通过。
- storage round-trip 通过。
- single reducer fixture 通过。
- 前端零引擎特定改动。
- SSE 正常关闭。
- chart/table 实时和历史正常。
- cancel/error/denied 完整。

## 13. 全量验证

### 13.1 前置条件

阶段 -1 必须先完成：

    cd dataagent/dataagent-backend
    .venv-py313/bin/python -m pytest tests/ --collect-only -q

要求 collection errors 为 0。

### 13.2 Frontend

    export NVM_DIR="/Users/guoruping/.nvm"
    . "$NVM_DIR/nvm.sh"
    nvm use
    cd dataagent/dataagent-frontend
    npm test

报告：

- Test Files
- Tests
- Failed
- Skipped

### 13.3 Backend

    cd dataagent/dataagent-backend
    .venv-py313/bin/python -m pytest tests/ -q

报告：

- collected
- passed
- failed
- errors
- skipped

### 13.4 Pi runtime

    cd dataagent/dataagent-runtime-pi
    export NVM_DIR="/Users/guoruping/.nvm"
    . "$NVM_DIR/nvm.sh"
    nvm use
    npm test

报告：

- tests
- pass
- fail
- skipped

任何已知随机或时序测试失败都必须单独修复或登记，不得通过重复执行直到偶然通过来声明成功。

## 14. 本地端到端 smoke

### 14.1 环境

- MySQL：`127.0.0.1:3316`
- 业务 schema：`opendataworks`
- session schema：`dataagent`
- Redis：`127.0.0.1:6379`
- Python：`dataagent/dataagent-backend/.venv-py313`
- Pi runtime：其 `.nvmrc`
- Frontend：仓库 `.nvmrc`

### 14.2 场景

#### 成功终态

Prompt：

    你好，请直接回复 smoke-ok。

断言：

- accepted + task_id。
- waiting → running → finished。
- run.completed。
- SSE 关闭。
- isStreaming=false。
- assistant message 持久化。
- topic finished。

#### NL2SQL 图表

Prompt：

    最近 30 天工作流发布次数趋势

断言：

- 真实 SQL。
- sql_execution 表格。
- chart_spec 图表。
- 刷新后 records 重放仍渲染。
- 无 Python block projection。
- 模型折叠不丢 chart dataset。

#### Queue/schedule

断言：

- task finished。
- queue completed。
- schedule log completed。
- error message 为空。

#### Cancel

断言：

- run.cancelled。
- task/topic suspended。
- SSE 关闭。
- downstream suspended。

#### History interaction

断言：

- 多 turn 保留。
- question request/answer 保留。
- permission request/decision 保留。
- live 与刷新后状态一致。

### 14.3 清理

- 删除 smoke topic/session。
- 清理 workspace 和 ResultStore 测试文件。
- 停止本地 backend/frontend。
- 保留可重复使用的 MySQL schema/user 和 Redis 容器。

## 15. 整体回滚

安全保留：

- 状态纠错。
- nullable 契约列。
- legacy read normalizer。
- records API。
- schema 和 fixtures。

可独立回滚：

- Pi writer 到 legacy `pi_event`。
- UI result registry。
- records 前端切换，但仅限 blocks 尚未删除的过渡期。
- watermark pruning 到 off。
- cache retention 配置。

禁止：

- 恢复 `success/cancelled` task status。
- 双写 `pi_event/agent_event`。
- 删除已写 AgentRecordV1。
- 长期恢复 Python block projection。
- 使用历史 blocks 构造模型上下文。
- 恢复无人消费的 `tool.progress`。
- 用关闭所有超时或无限上下文解决缓存问题。

## 16. 完成定义

- backend 全量测试可正常收集，不再有 pymysql module stub 污染。
- Pi task、topic、queue、schedule 状态一致。
- SSE 所有终态关闭。
- output 保持 SDK 兼容，output_meta 独立。
- Pi 图表和表格实时及刷新后正常。
- UI result 与 model result 解耦。
- AgentRecordV1 schema 在 TS、JS、Python 中强制。
- Pi 和 Claude 新记录保存完整信封。
- topic history 返回 records。
- 历史由 `processV2Record()` 直接重放。
- `_project_sdk_records()` 已删除。
- `buildV2StateFromStoredBlocks()` 已删除。
- `TopicMessage.blocks` 已删除。
- `tool.progress` 已删除。
- legacy `pi_event` 可通过单层 read normalizer 回放。
- `_build_history` 字节级回归不变。
- prompt caching 在真实 provider 场景出现 cache read。
- 第三个引擎接入无需修改前端或 Python 投影。
- 本地完整 smoke 通过，并记录实际环境、测试数量和未覆盖项。
