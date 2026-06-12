# NL2SQL 伪工具调用格式漂移收口 — 执行计划

配套设计：`docs/design/2026-06-11-nl2sql-tool-call-format-drift-design.md`

## 影响栈

- DataAgent 后端（`dataagent/dataagent-backend`）：运行时收口逻辑、系统提示词、单测。

## 任务与改动文件

1. `core/agent_runtime.py`
   - 新增 `_PSEUDO_TOOL_CALL_MARKERS`、`_PSEUDO_TOOL_CALL_TAG_RE`、
     `_contains_pseudo_tool_call`、`_strip_pseudo_tool_call_tags`。
   - `_partial_completion_note` 增加「工具调用格式」分支。

2. `core/task_executor.py`
   - 扩充对 `core.agent_runtime` 的导入（`_contains_pseudo_tool_call`、
     `_strip_pseudo_tool_call_tags`）。
   - `SdkResultAccumulator.__init__` 新增 `_saw_pseudo_tool_call`、`_saw_tool_use`
     与 `_note_pseudo_tool_call` 辅助方法。
   - `_ingest_assistant_content`：检测 `tool_use` 与伪标签（整块文本）。
   - `_ingest_stream_event`：`content_block_start` 记录 `tool_use`；
     `content_block_delta` 在原非 `text` 提前返回前，对 `thinking_delta`（读 `thinking`
     键）与 `text_delta`（读 `text` 键）检测伪标签。
   - `build_result()`：成功分支返回前新增 `_saw_pseudo_tool_call` 收口分支，抽出
     `_build_format_drift_result`（兜底 `finished` 或 `error: tool_call_format_drift`）。

3. `prompts/data_agent_system_prompt.md`
   - 「四、输出要求」新增禁止 XML 风格工具标签的条款。

4. 单测
   - `tests/test_agent_runtime.py`：`_contains_pseudo_tool_call`、
     `_strip_pseudo_tool_call_tags`、`_partial_completion_note` 漂移分支。
   - `tests/test_task_executor.py`：三个合成流用例——
     (a) 有部分可见文本 + tool_use → `finished` 带「工具调用格式异常」提示；
     (b) 仅有 tool 输出 → `finished` 指向「工具输出」；
     (c) 无可兜底内容 → `error` 且 `error.code == tool_call_format_drift`。

## 验证

仓库优先使用 `dataagent/dataagent-backend/.venv-py313`（>=3.10）；本次远程容器无该
venv，使用宿主 Python 3.11（满足 >=3.10）补装 `pytest/httpx/pymysql/cffi` 后运行：

```
cd dataagent/dataagent-backend
python3 -m pytest tests/test_task_executor.py tests/test_agent_runtime.py -q
```

结果：50 passed（47 既有 + 3 新增）。`tests/test_agent_runtime.py` 辅助单测全过。
邻近套件 `tests/test_sdk_block_writer.py::test_sdk_dataclass_tool_use_without_type_field_is_recorded`
存在与本改动无关的既有失败（在干净工作树上同样失败，属环境问题）。

端到端说明：漂移是非确定性模型行为，无法通过一次真实请求稳定触发，故合成流契约测试是
收口路径的权威覆盖；真实 NL2SQL 烟测仅用于确认正常 finish 路径无回归。

## 2026-06-12 迭代任务（漂移一律标错 + 前端重试）

### 影响栈

- DataAgent 后端（`dataagent/dataagent-backend`）：漂移收口结果语义。
- DataAgent 前端（`dataagent/dataagent-frontend`）：共享会话引擎、门户聊天组件、单测。

### 任务与改动文件

1. `core/task_executor.py`
   - `_build_format_drift_result`：删除「兜底成 finished」分支，漂移一律
     `error: tool_call_format_drift`，兜底文本保留在 `content`，
     `error.message` 为面向用户的可重试提示。
2. `tests/test_task_executor.py`
   - 原 (a)(b) 两个兜底用例改为断言 `error` + `tool_call_format_drift`，
     兜底文本仍在 `content`；(c) 不变。
3. `src/views/intelligence/useNl2SqlChat.js`
   - 新增并导出 `retryMessage(failedMessage)`：忙碌时 no-op；向上找最近一条用户
     提问，找不到则 `notifyError`；否则按正常新轮次重新 `send()`。
4. `src/views/intelligence/NL2SqlChatV2.vue`
   - 错误卡片增加「重试」按钮（`v2-error-retry`，流式中禁用、Widget 模式隐藏）、
     `handleRetry` 与样式。
5. 前端单测
   - `__tests__/useNl2SqlChat.spec.js`：漂移错误后 `retryMessage` 重发同一提问并
     成功续聊；忙碌/无前置提问时 no-op。
   - `__tests__/NL2SqlChatV2.spec.js`：历史错误消息渲染重试按钮；点击后以原提问
     重新投递并渲染新回答，错误卡片保留。

### 验证（2026-06-12）

- 后端：`python3 -m pytest tests/test_task_executor.py tests/test_agent_runtime.py -q`
- 前端：`npm --prefix dataagent/dataagent-frontend run test`（vitest）
- 漂移为非确定性模型行为，仍以合成流/桩测试为权威覆盖；真实端到端烟测在远程容器
  （无本地 MySQL/Redis/模型凭据）不可执行，按验证规则在交付说明中明确未覆盖层。

## 回滚

逐文件回退上述改动即可，无 schema / 部署 / 迁移变更；新增错误码
`tool_call_format_drift` 仅为新增返回值，回退后不再产生。
