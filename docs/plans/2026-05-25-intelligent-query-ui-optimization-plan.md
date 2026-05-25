# 智能问数 UI 交互优化实施计划

## 1. 任务拆解与实施步骤

### 1.1 顶栏重构与智能体切换位置迁移
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 移除左侧 `.query-sidebar-head` 中的 `.query-agent-panel`（包含下拉选择）。
  2. 在右侧 `.query-main` 顶部新增 `.query-main-top-bar` 容器，固定在对话区顶端。
  3. 将智能体切换组件移至顶部栏左侧，并整体切换为 Element Plus 控件：使用 `<el-select v-model="selectedAgentId" class="query-agent-select" :disabled="!agents.length">` 与 `<el-option v-for="agent in agents" :key="agent.agent_id" :label="agent.name" :value="agent.agent_id" />`。
  4. 调整顶部栏右侧的模型微章 `.query-model-badge` 为紧凑样式，与智能体选择器呼应。
  5. 优化 `.query-main-top-bar` 和 `.query-messages` 的高度占比，保证滚动区域自适应。
  6. 保留左侧顶部的产品标题和「新建」按钮，避免智能体选择迁移后侧栏失去入口语义。
  7. 删除或重写只适用于原生 `<select>` 的样式，改为通过 `.query-agent-select` 和 `:deep(.el-select__wrapper)` 控制宽度、高度、边框、背景和 focus 状态。
  8. 更新 `NL2SqlChat.spec.js` 中依赖原生 `select` 的选择器或触发方式，改为查找 Element Plus select/option 或直接设置 `selectedAgentId` 后断言话题列表刷新。

### 1.2 消息气泡 AI 智能体名称显示
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 调整 `query-message-assistant` 布局，使其包含智能体头像/图标区域。
  2. 在 AI 消息泡上方或左侧添加展示文本 `{{ activeTopic?.agent?.name || activeAgent?.name || '智能问数' }}`。
  3. 通过 CSS 给予淡灰色调和圆润边框，使其融入整体界面。

### 1.3 上下文窗口比例环（Context Window Ring）
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 在 `<script setup>` 中编写 `latestAssistantMessage` 计算属性，从 `activeMessages` 倒序查找最近一条 `role === 'assistant'` 的消息。
  2. 编写 `contextWindowUsage` 计算属性，读取 `latestAssistantMessage.value?.usage`。优先使用 `input_tokens + output_tokens`；若只有 `total_tokens`，使用 `total_tokens`；若 usage 缺失，返回 `{ available: false }` 空态。
  3. 定义 `getContextWindowLimit(model)` 工具函数：Claude 3/3.5/3.7 系列默认 200000，GPT-4o 系列默认 128000，DeepSeek 系列默认 64000，其余默认 128000。匹配逻辑统一转小写后基于模型名关键词判断。
  4. 在输入区域的发送按钮左侧增加 `.query-context-ring-wrap`，使用 SVG 画出环形比例；无 usage 时显示空态，不显示误导性 0%。
  5. 外层嵌套 `<el-tooltip>`，展示已用、上限、输入、输出、占比；输入/输出未知时显示“未知”。

### 1.4 回车发送与快捷键微调
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 找到 `<textarea>` 的 keydown 绑定。
  2. 修改为 `@keydown.enter.exact.prevent="handleSend"`，移除或保留 `Ctrl/Meta + Enter` 作为兼容快捷键均可；若保留，测试需要覆盖两种方式不会重复发送。
  3. 保持默认的 `Shift + Enter` 换行响应（不要添加 `.prevent`）。
  4. 确认 `handleSend` 仍通过 `canSendMessage` / `activeCancelableMessage` / provider/model/agent 状态阻止空输入、运行中任务和配置缺失时发送。

### 1.5 消息悬浮工具栏 (复制、时间、点赞/踩)
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 在每一条 `user` 或 `assistant` 的消息卡片内增加 `.query-message-footer` 层。
  2. 样式上使用 `opacity: 0` 隐藏，当 `.query-message-row:hover` 时设为 `opacity: 1` 渐显。
  3. 增加 `formatMessageTime(value)`，复用现有 `parseDisplayDate` 与 `formatInShanghai`，输出 `YYYY-MM-DD HH:mm`；无时间时隐藏时间文本。
  4. 渲染一键复制图标与复制函数 `handleCopyMessage(msg)`。复制内容优先使用 `msg.content`，assistant 消息为空时拼接 `main_text` block 的可见文本；调用 `navigator.clipboard.writeText`，失败时 `ElMessage.error('复制失败，请手动复制')`。
  5. 对于 AI 消息，展示点赞和点踩按钮，绑定响应动作改变 `msg.feedback`：再次点击同一按钮清空，点击另一按钮切换。该状态仅本地保存，不持久化。
  6. 不实现“编辑最后一条用户消息并重新发送”：不展示编辑按钮，不新增 `editingMessageId` / `editingMessageText`，不截断前端消息，也不调用重发逻辑。
  7. 若后续恢复该能力，先新增 DataAgent 后端“从指定 message_id 裁剪话题消息”接口和对应测试，再补前端交互。

### 1.6 猜你想问 (Follow-up Suggestions)
- **修改文件**：`frontend/src/views/intelligence/NL2SqlChat.vue`
- **步骤**：
  1. 编写 `latestVisibleMessage` 与 `followupSuggestions` 计算属性。仅当最后一条可见消息为 assistant、`status === 'success'`、且没有 `activeCancelableMessage` / `activeTopicSubmitting` 时返回 2-3 条建议。
  2. 根据消息内容关键词生成建议：包含 SQL 时建议解释 SQL、优化口径；包含图表或 chart 时建议解读趋势；包含错误时不展示；默认给出继续拆分、按维度对比、查看明细等问题。
  3. 在最新 assistant 消息下方渲染 `.query-followup-suggestions`，不要固定在输入框上方，避免跨话题或长历史时语义错位。
  4. 点击按钮后触发 `handleSuggestion`，复用 `handleSend` 的禁用逻辑立即继续提问。

### 1.7 结论区图表展示优化
- **修改文件**：
  - `frontend/src/views/intelligence/NL2SqlChat.vue`
  - `frontend/src/views/intelligence/__tests__/NL2SqlChat.spec.js`
- **步骤**：
  1. 在 `NL2SqlChat.vue` 从 `./chartSpec` 引入 `parseChartSpec`，保留现有 `stripChartSpecsFromText`。
  2. 编写 `extractChartSpecFromToolOutput(output)`，支持对象、JSON 字符串和数组，返回 `parseChartSpec(...)` 的结果或 `null`。
  3. 编写 `isChartBlock(block)`，仅当 `block.kind === 'tool'` 且 `extractChartSpecFromToolOutput(block.tool?.output)` 命中时返回 true。
  4. 修改 `processBlocksForMessage` 过滤掉 `isChartBlock(block)`。
  5. 修改 `finalBlocksForMessage` 包含 `main_text`、`error` 和 `isChartBlock(block)`。
  6. 在结论区渲染模板添加对 `block.kind === 'tool'` 且 `block.tool` 存在的分支渲染 `<ToolOutputRenderer>`。
  7. 更新 `NL2SqlChat.spec.js`：新增“tool chart_spec renders in conclusion area and is absent from process panel”用例；保留现有“does not inject inline chart tools into the conclusion area”用例，确认内联 `<chart_spec>` 仍不会被展示为图表。

### 1.8 前端回归测试补充
- **修改文件**：`frontend/src/views/intelligence/__tests__/NL2SqlChat.spec.js`
- **步骤**：
  1. 增加 Context Ring 用例：hydrate 一条 assistant 消息，`usage: { input_tokens: 1000, output_tokens: 200 }`，断言圆环或 Tooltip 文案包含 `1,200` 和模型上限。
  2. 增加 Enter 发送用例：输入文本后触发 textarea 的 `keydown.enter`，断言 `taskApi.deliverMessage` 调用一次；触发 `keydown.shift.enter` 时不调用发送。
  3. 增加消息工具栏用例：断言用户和 assistant 消息 footer 存在，复制按钮调用 clipboard，点赞/点踩会切换本地状态。
  4. 增加消息工具栏不含编辑入口用例：最后一条用户消息 footer 只展示时间与复制，不展示编辑按钮。
  5. 增加智能体选择器用例：断言顶部栏渲染 Element Plus select，切换 `selectedAgentId` 后会清空当前话题并调用 `topicApi.listTopics({ agent_id })`。

---

## 2. 验证方案

### 2.1 本地静态编译与 Lint 检查
- 执行命令前确认 Node 切换正确：
  `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`
- 运行打包命令：
  `npm --prefix frontend run build`

### 2.2 定向自动化测试
- 执行命令前同样先运行 `nvm use`。
- 运行智能问数组件测试：
  `npm --prefix frontend run test -- src/views/intelligence/__tests__/NL2SqlChat.spec.js`
- 如图表解析逻辑有改动，补充运行：
  `npm --prefix frontend run test -- src/views/intelligence/__tests__/ToolOutputRenderer.spec.js src/views/intelligence/__tests__/chartSpec.spec.js`

### 2.3 交互验证
1. 打开智能问数页面，验证左上角智能体切换下拉栏是否生效。
2. 进行一轮提问，观察 AI 消息上方是否显示当前智能体名称。
3. 使用包含 `tool` 类型 `chart_spec` 输出的历史或真实回答，观察图表是否正确出现在文本结论区下方，而不是折叠在思考区中。
4. 查看发送按钮左下侧的 Token 上下文百分比环是否显示，悬浮是否展示详情。
5. 悬浮在消息卡片上，检查底部是否浮现工具栏（时间、复制、赞/踩）。
6. 查看最后一条用户消息，确认消息工具栏不展示编辑入口。
7. 对话完成后，检查对话底部是否出现「猜你想问」气泡，点击是否能直接提交。

### 2.4 智能问数本地 Smoke 边界
- 本次计划默认只改 `frontend/src/views/intelligence/NL2SqlChat.vue` 和前端测试，不改变 DataAgent 后端 API、任务协调、持久化或部署行为时，可不强制运行完整 DataAgent smoke。
- 如果实施过程中新增后端消息裁剪 API、修改 `/tasks/deliver-message` 请求/响应、修改事件流处理协议，必须按仓库 AGENTS 规则补充本地智能问数 smoke，至少覆盖：
  1. `POST /api/v1/nl2sql/tasks/deliver-message` 返回 `accepted=true` 和 `task_id`。
  2. 任务状态从 `waiting -> running -> success|failed|suspended`。
  3. `/api/v1/nl2sql/tasks/{task_id}/events/stream` 能消费终态事件。
  4. `/api/v1/nl2sql/topics/{topic_id}/messages` 能看到最终 assistant 消息。
  5. 若后续恢复编辑重发并涉及后端裁剪，刷新页面后消息历史必须与当前 UI 截断结果一致。
