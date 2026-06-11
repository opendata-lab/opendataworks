# NL2SQL 伪工具调用格式漂移收口 — 设计

## 背景与问题

在智能问数（NL2SQL）链路中，部分评测用例（ARCH_RISK_002 / ARCH_RISK_003 /
ARCH_PERF_005）出现「工具/SQL 已经实际执行、却没有可用最终回答」的现象。

定位后确认：根因不是 SQL 口径问题，也主要不是评测器误判，而是**工具调用格式漂移**。
模型把伪/假的工具调用 XML 标签（如 `</parameter></function></tool_call>`、
`<tool_call>` 等）当作纯文本吐出来，通常出现在某个 `thinking` 块的结尾，而不是发起
真正的 `tool_use`。

发生时的链路表现：

- SDK 不会把这段泄漏文本识别成真实工具调用。
- 模型本轮「正常」结束（`ResultMessage` 不是错误）。
- `SdkResultAccumulator.build_result()` 落入成功分支，把任务标记为 `finished`，
  内容取 `content or "已完成。"`。
- 由于 `thinking` 与 tool_result 内容本就不进入最终可见回答，最终答案为空或停在
  「准备查询阶段」。

净效果：一个其实已经取到数据的任务，被静默地报告为已完成、却给出死胡同回答。

## 现状代码

- `core/task_executor.py`
  - `SdkResultAccumulator`：只有 `text` 类型块进入最终答案；`_ingest_stream_event`
    对非 `text` delta 提前 return，`thinking`（伪标签所在）内容被丢弃。
  - `build_result()` 成功分支返回 `finished` + `content or "已完成。"`。
- `core/agent_runtime.py`
  - `_recover_partial_content(question, main_text, blocks, reason)`：可复用的兜底
    收口函数——有可见文本时返回清洗后的文本 + 「未完整结束」提示；无文本但 `blocks`
    含工具输出时返回「请查看上方思考过程中的工具输出」提示；否则返回空串。
  - `_block_has_tool_output`、`_partial_completion_note`、
    `_sanitize_user_visible_content`（当前为 no-op）。
- `prompts/data_agent_system_prompt.md`「四、输出要求」未禁止 XML 风格工具标签。

## 方案

采用「检测 + 兜底收口 / 标错」为主，配合提示词缓解，分两层：

### 1. 运行时检测与收口（主路径）

在 `SdkResultAccumulator` 中新增检测：

- 新增标志 `_saw_pseudo_tool_call`、`_saw_tool_use`。
- 在三条文本路径上检测伪标签：整条消息文本、`text_delta`、以及 **`thinking_delta`**
  （在原非 `text` 提前返回之前检测，但不把 thinking 写入可见答案）。注意 thinking delta
  文本位于 `thinking` 键、text delta 文本位于 `text` 键。
- 出现 `tool_use` 块时置 `_saw_tool_use`。

在 `build_result()` 成功分支返回前，新增**单一显式分支**
（仅在非错误、subtype 非 `error*` 时进入）：

- 若检测到伪标签：
  - 用 `_strip_pseudo_tool_call_tags` 清洗可见文本，连同合成的 tool 输出标记一起交给
    `_recover_partial_content` 收口；
  - 能兜底出内容 → `finished` + 带「工具调用格式异常」提示的内容；
  - 兜底为空 → `error`，错误码 `tool_call_format_drift`，给出明确可重试提示。

错误路径继续走既有 `sdk_writer.append_error(...)`，使漂移也体现在 SDK 记录流中。

仅在收口路径上清洗标签，不做全局过滤——遵循「最小兜底、单层」与未选用全局清洗的决定。

### 2. 提示词缓解

在系统提示词「四、输出要求」新增一条：工具只能经真实 Bash/Skill 等工具调用执行；严禁把
`<tool_call>`、`</tool_call>`、`<function>`、`<parameter>`、`</invoke>` 等 XML 风格
标签当文本或思考内容输出；已取到数据时直接写最终结论。该规则属通用输出格式约束，置于
系统提示词而非技能包，符合「共享运行时保持技能无关」的约束。

## 接口与契约变化

- 新增任务错误码 `tool_call_format_drift`（仅在伪工具调用漂移且无可兜底内容时返回）。
- `_recover_partial_content` 复用，不改签名。
- 新增内部辅助 `_contains_pseudo_tool_call` / `_strip_pseudo_tool_call_tags`
  与 `_partial_completion_note` 的「工具调用格式」分支。

## 取舍

- 不采用「自动追加一次 continuation 收口」：需要重跑模型、额外轮次/超时预算，改动更大；
  本次按用户选择采用更小、单分支的「兜底 + 标错」。
- 不新增全局标签清洗器，也不做「答案是否引用 query_result」的收口门禁——超出本次范围。

## 范围外

- 全局可见文本标签清洗。
- query_result 与最终答案一致性门禁。
