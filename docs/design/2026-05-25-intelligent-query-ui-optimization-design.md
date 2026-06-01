# 智能问数 UI 交互优化设计文档

## 1. 背景与目标
在目前的「智能问数」功能中，对话界面存在若干体验细节优化空间，为进一步提升产品易用性与交互精致度，特设计此优化方案。

具体改进点包括：
1. **上下文窗口占比环形指示器**：在输入框发送按钮左侧新增一个环形，显示当前会话已消耗的 Context Window 比例，悬浮时展示具体的 Token 统计（输入、输出、总计、模型上限值）。
2. **智能体切换入口移动**：将智能体切换选择器从左侧会话记录上方移至中间对话区左上角（置顶 Header 栏）。
3. **对话中展示智能体名称**：对话过程中，在 AI 消息泡上方或头像旁显示当前应答智能体的具体名称。
4. **发送快捷键优化**：输入框调整为按下 `Enter` 键直接发送，`Shift + Enter` 键换行。
5. **消息悬浮工具栏**：
   - 鼠标悬浮在消息卡片上时，卡片下方展示该消息的发送/接收具体时间（年-月-日 时:分），并支持一键复制消息内容。
   - AI 应答卡片额外提供点赞和点踩交互。
   - 会话中最后一条用户发送消息的“编辑并重新发送”能力本期暂缓，不展示编辑入口，也不实现截断或重发逻辑。
6. **猜你想问（Follow-up Suggestions）**：在 AI 最新回答下方提供 2-3 个接下来可能提问的推荐问题，点击后直接提交进行新一轮对话。
7. **结论区图表外置**：优化目前图表组件（ToolOutputRenderer）的展示逻辑。如果后端流式事件或历史消息中存在 `tool` 类型的 `chart_spec` 输出，不再折叠隐藏在「思考区」中，而是置于主回答区域（结论区）内直观呈现。当前阶段不把 `main_text` 中的内联 `<chart_spec>` 转换成可见图表，以避免和现有“剥离内联 chart_spec 文本”的兼容行为冲突。

---

## 2. 界面布局与交互设计

### 2.1 智能体切换与顶栏设计
在 `.query-main` 中引入一个固定的顶部导航条 `.query-main-top-bar`。
- 左侧放置智能体切换下拉菜单，统一使用 Element Plus 的 `<el-select>` 与 `<el-option>`，不继续使用原生 `<select>`。展示文案为「当前智能体：XXX」或通过独立 label + select 组合呈现。
- `<el-select>` 绑定现有 `selectedAgentId`，禁用条件沿用 `!agents.length`，选项使用 `agent.agent_id` 作为 value、`agent.name` 作为 label。
- 右侧保留或重构现有的模型与 Provider 信息徽章，使其在视觉上更对称。
- 移出左侧 `.query-sidebar-head` 中的 `.query-agent-panel`。

### 2.2 上下文窗口比例环（Context Window Ring）
在发送按钮的左侧放置一个 SVG 渲染的百分比圆环组件 `.query-context-ring`。
- **圆环计算逻辑**：
  - 基于当前会话中最后一条 assistant 消息的 `usage` 数据。后端事件中的 `data.token_usage` 在前端流处理后会合并到 `msg.usage`，历史消息 hydrate 时也使用 `message.usage`。UI 层不直接读取 `token_usage` 字段。
  - 优先读取 `usage.input_tokens` 与 `usage.output_tokens`。若 `usage.total_tokens` 存在但输入/输出缺失，则用 `total_tokens` 作为已用值并在 Tooltip 中标注输入/输出未知。若 usage 缺失或为 0，圆环显示空态（例如 `--`），Tooltip 提示“暂无 Token 用量”。
  - 在 LLM 对话中，最新一条回复的 `input_tokens` 通常已包含整个对话历史（System Prompt + 消息历史 + 检索上下文），因此 `input_tokens + output_tokens` 可作为当前 Context 消耗的近似值。该值是 UI 估算，不作为后端截断或限流依据。
  - 模型上下文上限配置：
    - `claude-3-5` 等 Claude 3/3.5 模型：200k (200,000)
    - `gpt-4o` 等主流模型：128k (128,000)
    - `deepseek` 等模型：64k (64,000)
    - 默认 fallback：128k (128,000)
- **悬浮 Tooltip 气泡**：
  - 悬浮在圆环上时，使用 `<el-tooltip>` 展示具体细节：
    - **上下文窗口使用情况**
    - 已用：`{total_tokens} / {limit_tokens} Tokens`
    - 输入：`{input_tokens}` · 输出：`{output_tokens}`
    - 占比百分数。

### 2.3 消息悬浮工具栏与扩展动作
在每条消息泡（`.query-message-row`）的下方引入一个工具栏容器 `.query-message-footer`，在 Hover 时显示（`opacity: 0 -> 1`）。
- **通用动作**：
  - 展示具体时间：`YYYY-MM-DD HH:mm`
  - 复制按钮：点击将消息文本拷贝到剪贴板，使用 SVG 复制图标。
- **AI 回复特有动作**：
  - 点赞/点踩按钮：双态（选中与未选中），选中时激活高亮。
  - 点赞/点踩状态需要持久化到 DataAgent 后端，刷新页面或重新 hydrate 后继续显示上次反馈。
  - 反馈只绑定 assistant 消息，允许值为 `like`、`dislike` 或空字符串。再次点击同一按钮清空反馈，点击另一按钮切换反馈。
  - 前端乐观更新按钮状态；若后端保存失败，回滚到点击前状态并提示错误。
- **暂缓动作**：
  - “编辑最后一条用户消息并重新发送”本期不实现。原因是该能力涉及前端消息截断、后端持久化历史、任务事件流取消和刷新后一致性，单纯前端截断会产生状态不一致风险。
  - 本期消息工具栏不展示编辑入口；后续如需实现，必须先补充 DataAgent 后端消息裁剪接口和端到端 smoke 验证。

### 2.4 猜你想问 (Suggestions)
在最新的 AI 消息区块下方显示 `.query-followup-suggestions`。
- **推荐问题提取逻辑**：
  - 只在最新一条可见消息为 assistant、状态为 `success`、且当前话题没有活跃任务时展示。
  - 当最新 assistant 消息为 `queued`、`running`、`streaming`、`failed` 或 `cancelled` 时不展示。
  - 基于回复内容动态或静态推荐 2-3 个相关度较高的问题。
  - 例如，回复中含有 SQL 语句，推荐“解释一下这个 SQL 的逻辑”；含有图表，推荐“对图表展现的趋势做个深度解读”。
- **交互**：
  - 点击推荐块，复用 `handleSuggestion` 和 `handleSend` 的现有禁用逻辑提交发送，界面滚动到最下方。

### 2.5 结论区图表渲染
修改 `processBlocksForMessage` 与 `finalBlocksForMessage` 两个 computed 属性：
- **isChartBlock**：判断当前块是否是 `kind === 'tool'`，并且 `ToolOutputRenderer` 可将 `block.tool.output` 解析为 `chart_spec`。检测逻辑与 `ToolOutputRenderer` 共用 `chartSpec.js` 导出的 `extractChartSpec`，作为唯一真源，避免“工具框能渲染图表、但结论区检测不到”的不一致。`extractChartSpec` 不仅识别结构化对象，还会从工具结果的内容块数组（`[{type:'text', text}]`）以及 build 脚本 stdout 文本中深度提取 chart spec，因此通过 Bash/Shell 执行 build 脚本输出的图表也能被外置到结论区。
- **思考区**：过滤掉 `isChartBlock` 块，确保深度思考面板中不显示图表，仅保留 shell 运行、文件读写等调试追踪信息。
- **结论区**：`finalBlocksForMessage` 在输出文本块（`main_text`）的同时，允许输出 `isChartBlock`。
- 模板中在结论区通过 `<ToolOutputRenderer>` 渲染图表 block，得益于 `ToolOutputRenderer` 对 `chart_spec` 的直观渲染（直接显示折线/柱状/饼图），图表将在文本回答下方优雅呈递。
- **内联 chart_spec 边界**：当前 `main_text` 中的 `<chart_spec>` 或 ```chart fenced block 已通过 `stripChartSpecsFromText` 从正文中隐藏。本设计不改变该行为，也不把它合成为图表 block；如果后续要展示内联图表，需要同步更新 `NL2SqlChat.spec.js` 中“does not inject inline chart tools into the conclusion area”的既有预期，并补充兼容方案。

---

## 3. 技术设计与状态管理
- **键盘事件劫持**：在 `<textarea>` 绑定 `@keydown.enter.exact.prevent` 触发发送，配合 `@keydown.shift.enter` 维持默认的换行逻辑。
- **复制交互状态**：优先使用 `navigator.clipboard.writeText`；失败时降级提示“复制失败，请手动复制”，不引入额外依赖。
- **点赞/点踩状态**：前端仍使用消息对象上的 `msg.feedback = 'like' | 'dislike' | ''` 驱动按钮选中态，但该字段来自后端消息响应，并通过反馈接口写回后端。
- **点赞/点踩持久化**：
  - DataAgent 在 `da_agent_message` 增加 `feedback VARCHAR(16) NOT NULL DEFAULT ''` 字段。
  - `GET /api/v1/nl2sql/topics/{topic_id}/messages` 返回每条消息的 `feedback` 字段；用户消息固定为空字符串。
  - 新增 `PUT /api/v1/nl2sql/topics/{topic_id}/messages/{message_id}/feedback`，请求体为 `{ "feedback": "like" | "dislike" | "" }`，响应返回更新后的 `TopicMessage`。
  - 后端必须先按当前 request context 校验 topic 归属，再校验 message 属于该 topic，且只允许更新 `sender_type='assistant'` 且 `show_in_ui=1` 的消息。非法反馈值返回 400，消息不存在或不属于当前上下文返回 404，非 assistant 消息返回 400。
- **Token 用量状态**：新增 `latestAssistantUsage` / `contextWindowUsage` 计算属性，读取 `activeMessages` 中最新 assistant 消息的 `usage`，并基于 `selectedModel` 推断上下文上限。
- **样式方案**：继续在 `<style scoped>` 中编写纯粹的 CSS / Sass 样式。配合弹性布局与 Hover 触发器，提供平滑的过度动画。

---

## 4. 影响与风险评估
- **会话截断风险**：编辑并重新发送最后一条消息会涉及前端截断和后端持久化历史一致性，本期明确暂缓，避免引入刷新后历史恢复、旧任务继续写入等风险。
- **异步任务风险**：智能问数当前通过 `/tasks/deliver-message` 接受任务，并通过 `/tasks/{task_id}/events/stream` 消费事件。任何编辑重发或建议问题提交都必须复用现有的 `activeCancelableMessage` 与提交中状态，避免并行提交到同一话题。
- **图表渲染性能**：外置图表直接在对话区域渲染，随着历史消息变多，页面中的 ECharts 实例可能增多。须确保每个 `ToolOutputRenderer` 实例在销毁（`onBeforeUnmount`）时能够正确释放 ECharts 内存。
- **快捷键行为变化风险**：从 `Ctrl/Meta + Enter` 发送改为 `Enter` 发送会改变既有用户肌肉记忆。需要保留 `Shift + Enter` 换行，并在测试中覆盖空输入、禁用态和运行中任务的 Enter 行为。
- **空态与异常数据风险**：usage 缺失、provider/model 未配置、消息缺少 `created_at`、剪贴板权限被拒绝时，UI 必须优雅降级，不阻塞发送主链路。
- **反馈持久化失败风险**：点赞/点踩保存失败时，前端需要回滚乐观状态，避免 UI 显示与后端历史不一致。反馈接口不影响发送、流式事件或任务取消主链路。
