# Chat V2 会话筛选与 Widget 会话融合 — 执行计划

- 日期：2026-06-02
- 关联设计：`docs/design/2026-06-02-chat-v2-session-filter-design.md`
- 受影响栈：`frontend`（后端端点复用，不改动）

## 任务与触达文件

1. 清理独立页 — `frontend/src/views/settings/WidgetConversations.vue`（删除）。
2. 注销 Tab — `frontend/src/views/intelligence/IntelligentQueryView.vue`
   - `validTabs` 移除 `'widget-sessions'`；
   - 移除 `el-menu-item index="widget-sessions"` 与 `ChatLineSquare` icon import；
   - 移除 `<WidgetConversations v-else-if="activeTab === 'widget-sessions'" />` 与 `import WidgetConversations`。
3. Chat V2 侧边栏 — `frontend/src/views/intelligence/NL2SqlChatV2.vue`
   - 新增状态：`sourceMode`、`filterStatus`、`sortOrder`、`filterPopoverVisible`。
   - 头部：来源切换两 Tab（门户 / Widget）；`新建` 仅 portal 显示；`el-popover` 承载状态筛选 + 排序 + 重置。
   - `loadTopics()` → `loadSessionList()`：按 `sourceMode` 分流 `topicApi.listTopics` / `dataagentApi.listWidgetTopics`。
   - `selectTopic()` → `selectSession()`：按 `sourceMode` 分流 `topicApi.getTopicMessages` / `dataagentApi.getWidgetTopicMessages`，复用 `hydrateHistoryMessage`。
   - `filteredTopics` computed：在现有 `searchKeyword` 过滤上叠加 `filterStatus` 过滤与 `sortOrder` 排序。
   - 输入区：`widget` 模式禁用 textarea/发送并显示只读提示；`handleSend`/`handleNewTopic` 在 widget 模式短路。
   - `watch([sourceMode, filterStatus, sortOrder])` → 重载列表并清空 `activeTopicId`/`messages`。
4. 测试调整 — `frontend/src/views/intelligence/__tests__/IntelligentQueryView.spec.js`（若存在 `widget-sessions` 断言/stub 则移除）。

## 验证

- 前端构建/单测（先 `nvm use`）：
  - `npx vitest run frontend/src/demo/__tests__/mockServerIntelligentQuery.spec.js`（mockServer widget 端点契约不变，应保持绿色）
  - 受影响视图单测 `IntelligentQueryView.spec.js`
  - `npm --prefix frontend run build`
- demo 模式功能验收：
  - 默认门户来源可发消息；
  - 切 Widget：列表变 4 条 demo widget 话题，新建消失，选中后输入区只读；
  - 状态筛选「失败」仅留 error 类；排序切换列表顺序变化；
  - widget 模式搜索「转化率」命中 1 条；切回门户恢复可输入。

## 回退

- 恢复 `WidgetConversations.vue` 与 `IntelligentQueryView.vue` 的 `widget-sessions` 注册即可还原独立页。
- `NL2SqlChatV2.vue` 改动为增量（新状态 + popover + 分流），回退移除新增状态与模板分支即可。
- 后端 `/widget-topics` 端点、前端 API 客户端、demo 数据均保留，不受回退影响。
