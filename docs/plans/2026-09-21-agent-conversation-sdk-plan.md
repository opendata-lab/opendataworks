# Agent Conversation SDK 实施计划

**设计文档:** `docs/design/2026-09-21-agent-conversation-sdk-design.md`
**下游:** OntoFoundry 仓库 `docs/plans/2026-09-21-dataagent-conversation-sdk-integration-plan.md`
**给下游的两个交付点：** T7 发布 npm 包（下游 T7 前端接入的前置）；T8 服务端 access key（下游真实联调与生产部署的前置）。

## 全局约束

以下取值在所有任务中生效，实施者不得自行更改：

- 包名 `@opendataworks/agent-conversation`，首版 `0.1.0`，license `GPL-3.0-only`。
- 元素标签名 `dataagent-conversation`；DOM 事件前缀 `dataagent-`，全部 `bubbles: true, composed: true`。
- 元素用 **`defineCustomElement`** 实现，不用 `createApp().mount()`——后者无法投影 Light DOM 插槽。
- 样式**编译为 JS 内联字符串**由元素注入 Shadow Root；包**不导出** `style.css`，宿主不 import 任何 CSS。
- 包内**任何文件不得 import `@/api/nl2sql` 或 `@/views/*`**。**由 `src/__tests__/package-boundary.spec.js` 强制**——`dataagent-frontend` 没有 eslint（只有独立的 `frontend/` 应用有），为一条规则引入整套 lint 工具链不划算。
- Vue 与 Element Plus 打进产物，不作为 peerDependency。
- **npm 管理根是 `dataagent/dataagent-frontend/`，仓库根没有 `package.json`。** 所有 npm 命令必须显式指定 cwd。
- DataAgent 运行时 API 前缀 `/api/v1/nl2sql`，事件流 `/tasks/{task_id}/sdk-events/stream`；**Agent 存在性查询在不同前缀** `/api/v1/dataagent/agents/{agent_id}`。
- 运行状态转换表（写死，`core/task_status.py:17,23`）：`waiting→queued`、`running→running`、`waiting_input→waiting_input`、`waiting_permission→waiting_permission`、`finished→finished`、`error→failed`、`suspended→cancelled`、任务不存在`→failed`。活动态是前四个。
- Node `>=20.19.0`。
- 每个任务结束提交一次，前缀 `feat(sdk):` / `refactor(sdk):` / `fix(dataagent):`。

---

## T1 — 包骨架与构建管线

**产出：** 一个能被 `npm pack` 打出、能在示例项目挂载、能正确投影插槽的空元素。

**涉及文件**

- 新增 `dataagent/dataagent-frontend/packages/agent-conversation/package.json`
- 新增 `.../vite.config.js`
- 新增 `.../src/index.js`、`.../src/element.js`、`.../src/ui/ConversationRoot.vue`
- 新增 `.../types/index.d.ts`、`.../README.md`
- 新增 `.../src/__tests__/element.spec.js`
- 修改 `dataagent/dataagent-frontend/package.json`（增加 `build:sdk`、`test:sdk`）
- 修改 `dataagent/dataagent-frontend/eslint` 配置

**步骤**

- [x] 写 `package.json`，字段按设计 §11.1 抄全。`exports` 只有 `"."` 一项，**没有 `./style.css`**。
- [x] 写 `vite.config.js`：`build.lib` 入口 `src/index.js`，`formats: ['es','umd']`，`cssCodeSplit: false`，`build.rollupOptions.external: []`，插件 `vue({ customElement: true })`。
  **必须 `root: __dirname`。** vite 的 `outDir` 相对 `root` 解析，而 `root` 默认是 cwd——从 `dataagent-frontend` 执行时会把库直接产到主应用的 `dist/` 里。
  **必须 `define: { 'process.env.NODE_ENV': JSON.stringify('production') }`。** UMD 产物由 `<script>` 直接加载，裸 `process` 引用会 `ReferenceError`；顺带消除 dev 分支，ES 产物从 158kB 降到 108kB。
  样式写在 SFC 的 `<style>` 块里，`customElement: true` 会把它编译成字符串挂到组件上，由 `VueElement` 注入 shadow root——无需 `?inline`，也不产出独立 CSS。
- [x] `ConversationRoot.vue` 先只渲染两个插槽占位和一个固定文案，用于验证投影：

```vue
<template>
  <div class="dac-root">
    <div class="dac-messages"><slot name="empty">暂无消息</slot></div>
    <div class="dac-composer-actions"><slot name="composer-actions" /></div>
  </div>
</template>
```

- [x] `src/element.js`：

```js
import { defineCustomElement } from 'vue'
import ConversationRoot from './ui/ConversationRoot.vue'
import styles from './styles/conversation.scss?inline'

const cache = new Map()

export function defineAgentConversation(tagName = 'dataagent-conversation') {
  if (customElements.get(tagName)) return
  // 每个标签名必须有各自的构造器：同一个构造器 define 两次会抛 NotSupportedError
  let Ctor = cache.get(tagName)
  if (!Ctor) {
    Ctor = defineCustomElement(ConversationRoot, { styles: [styles] })
    cache.set(tagName, Ctor)
  }
  customElements.define(tagName, Ctor)
}
```

- [x] 写测试：注册幂等；**同一 registry 中先注册默认标签名再注册别名不抛错**（暴露构造器复用问题）；**断开后重新插入不抛错**；Light DOM 中 `<button slot="composer-actions">` 被投影进 `.dac-composer-actions` 且可点击。
  注意断言 fallback 内容时用 `assignedNodes()` 而非 `assignedNodes({flatten:true})`——后者会把 fallback 折进来，断言变成恒真。
- [x] 写 `src/__tests__/package-boundary.spec.js`：扫描包内所有 `.js/.ts/.vue`，断言无 `@/api/`、`@/views/`、`@/` 的 import。**不引入 eslint**（理由见全局约束）。
- [x] `cd dataagent/dataagent-frontend && npm run build:sdk`，确认产出落在 `packages/agent-conversation/dist/`（**不是主应用的 `dist/`**），有 `index.js`、`index.umd.cjs`，无 `style.css`，且 `grep -c 'process\.env' dist/index.umd.cjs` 为 0。
- [x] `cd dataagent/dataagent-frontend/packages/agent-conversation && npm pack --dry-run`，确认 tarball 只含 `dist`、`types`、`README.md`、`package.json`（`docs/bff-protocol.md` 在 T4 产生）。
- [x] 提交。

**验收：** 上述测试全绿；插槽投影测试是本任务的核心门禁——它验证了 `defineCustomElement` 这条技术路线成立。

**T1 已完成**（commit `1701c785`）：12 个新测试通过，全量 506 个测试通过。三条结论已落实到代码——插槽投影成立、每个标签名需独立构造器、`root` 必须固定。

---

## T2 — 拆分会话内核与 Topic 管理

**产出：** 单会话内核进包，Topic 管理留在应用侧，SPA 与 Widget 行为不变。

**涉及文件**

- 新增 `packages/agent-conversation/src/core/useConversation.js`
- 新增 `.../src/core/streamParser.js`、`.../src/core/agentEvents/reducer.js`、`.../src/core/message.js`、`.../src/core/useMessageActions.js`
- 新增 `.../src/core/runStatus.js`
- 新增 `src/views/intelligence/useTopicList.js`
- 新增 `src/views/intelligence/nl2sqlTransport.js`
- 修改 `src/views/intelligence/useNl2SqlChat.js`、`v2StreamParser.js`、`chatMessage.js`、`useChatMessageActions.js`、`agentEvents/reducer.js` → re-export / 薄适配
- 修改 `src/views/intelligence/__tests__/` 受影响用例

**步骤**

- [ ] 先把 `src/views/intelligence/__tests__/` 中覆盖 `v2StreamParser` 与 `agentEvents/reducer` 的用例复制到 `packages/agent-conversation/src/core/__tests__/`，此时仍 import 旧路径，应全绿——这是**黄金夹具对照**基线。
- [ ] 移动 `v2StreamParser.js`、`agentEvents/reducer.js`、`chatMessage.js` 到包内（内容不改），原路径改 `export * from '<包内路径>'`。
- [ ] 把包内对照测试的 import 改为包内路径，跑 `npm run test:sdk`，结果必须与移动前逐条一致。
- [ ] 新建 `core/runStatus.js`，实现全局约束里的转换表与 `ACTIVE` / `TERMINAL` 常量，附单元测试（七种状态 + 未知状态）。
- [ ] **按设计 §5.1 拆 `useNl2SqlChat.js`**：
  - 单会话部分（消息装载、当前任务生命周期、流订阅重连、发送、取消）→ `core/useConversation.js`，签名 `useConversation({ transport })`。
  - Topic 列表 / 新建 / 删除 / 切换 / `runtimeApi` 配置 → `views/intelligence/useTopicList.js`。
  - 这不是改名：两部分的状态要真正分开，`useConversation` 内不得出现 topic 列表状态。
- [ ] `core/useConversation.js` 中所有网络调用收敛到设计 §7.2 的 transport 方法；消费 `streamEvents` 产出的 `StreamItem`，只有收到 `{type:'terminal'}` 才认定任务结束。
- [ ] 新建 `views/intelligence/nl2sqlTransport.js`（**留在应用侧，不进包**）：把 `createNl2SqlApiClient` 结果适配成完整 transport，实现全部必选 + 全部可选方法。其中：
  - `streamEvents` 打 `/api/v1/nl2sql/tasks/{id}/sdk-events/stream`，解析**裸 `data:` 帧**为 `{type:'event', seqId, event}`；EOF 后调 `getTask` 并按转换表合成 `{type:'terminal', run}`。
  - `fileUrl` 拼 `/api/v1/nl2sql/topics/{topic_id}/files/{relPath}`。
  - `loadConversation` **分页取全历史**（`page_size=500` 循环直到取尽），不能只取第一页。
- [ ] `useNl2SqlChat.js` 保留薄适配：`useConversation({ transport: nl2sqlTransport(inject('nl2sqlApi')) })`。
- [ ] `useChatMessageActions.js` 移动并改为 `transport.submitFeedback?.()`，缺失时返回 `false` 供 UI 隐藏按钮。
- [ ] 跑 `npm test` 与 `npm run test:sdk`，SPA 与 Widget 既有用例必须全绿。
- [ ] 提交。

**验收：** 两套测试全绿；黄金夹具对照逐条一致；`grep -rn "@/api\|@/views" packages/agent-conversation/src` 无结果；`useConversation.js` 中无 topic 列表状态。

---

## T3 — 消息 UI 与交互卡片

**产出：** 元素具备完整会话能力：消息列表、Markdown、工具卡片、权限确认、Agent 提问、附件、输入框、运行状态、停止。

**涉及文件**

- 新增 `packages/agent-conversation/src/ui/MessageList.vue`、`Composer.vue`、`ToolOutput.vue`、`PermissionCard.vue`、`QuestionCard.vue`
- 新增 `.../src/ui/components/ResultDataTable.vue`、`SqlCodePanel.vue`
- 新增 `.../src/ui/slash/`
- 新增 `.../src/styles/conversation.scss`
- 修改 `.../src/ui/ConversationRoot.vue`（从 T1 的占位改为真实组合）
- 修改 `src/views/intelligence/` 中被移动文件 → re-export
- 修改 `src/widget/styles.js`

**步骤**

- [ ] 移动四个卡片组件与两个 `components/` 组件，原位置 re-export。`PermissionCard` / `QuestionCard` 的提交改为 `transport.submitInteraction({ taskId, kind, requestId, payload })`。
- [ ] **`SqlCodePanel.vue` 删除 `import { createNl2SqlApiClient } from '@/api/nl2sql'`**（当前在 :63），`executeSql` 改走可选的 `transport.executeSql`。transport 未提供时：隐藏"执行"按钮、不渲染结果表格，保留语法高亮与复制。
- [ ] 从 `WidgetChat.vue` 抽出消息列表 → `MessageList.vue`；抽出输入框 + 发送 + 停止 + 运行状态 → `Composer.vue`，footer 内提供 `<slot name="composer-actions" />`，位置在标准发送按钮之前。
- [ ] `ConversationRoot.vue` 组合 `MessageList` + `Composer`，持有 `useConversation({ transport })` 状态，保留 `<slot name="empty" />`。
- [ ] 斜杠命令依赖 `transport.listSlashCommands`，缺失时输入 `/` 不弹菜单；权限模式切换器仅当 `transport.setPermissionMode` 存在时渲染。
- [ ] 附件下载链接 `href` 由 `transport.fileUrl(relPath)` 生成。
- [ ] `element.js` 按设计 §6.2.1 落地 JS 接口：`endpoint`/`placeholder`/`active`/`disabled` 为带 attribute 的 props；`endpointResolver`/`transportFactory` 为非 attribute props；`value` 由包装类定义 `get`/`set` 访问器转发到内部响应式状态；四个方法经 `defineExpose` 由包装类代理。**挂载前的属性写入必须缓存并在挂载时回放**（React `ref` 赋值就是这个时序）。
- [ ] 包内所有 Element Plus 弹层显式传 `:teleported="false"`，`ConversationRoot` 根容器设 `position: relative` 与 z-index 基准。
- [ ] 派发五个 DOM 事件，全部 `bubbles: true, composed: true`。
- [ ] `dataagent-complete` 回传 metadata：`useConversation` 内按 taskId 记忆发送时的 metadata，终态时取出；**装载时用 `snapshot.run.metadata` 回填该映射**，保证刷新后仍能恢复。
- [ ] `src/widget/styles.js` 中属于消息区/输入区的规则删除（已在包内），保留外壳规则。
- [ ] 补测试，覆盖设计 §12"单元"中除会话地址生命周期与流终态外的条目（那两项在 T4）。
- [ ] 提交。

**验收：** `npm run test:sdk` 全绿；无 `executeSql` 时 SQL 面板只读且无报错；`grep -rn "@/api" packages/agent-conversation/src` 仍无结果。

---

## T4 — 内置 HTTP transport、会话地址生命周期与容错

**产出：** 宿主只给 `endpoint` 就能用；会话切换无竞态；流终态显式；错误有可操作指引。

**涉及文件**

- 新增 `packages/agent-conversation/src/transport/http.js`、`.../src/transport/errors.js`
- 新增 `packages/agent-conversation/examples/mock-bff/server.mjs`、`.../examples/mock-bff/README.md`
- 新增 `packages/agent-conversation/docs/bff-protocol.md`
- 修改 `packages/agent-conversation/src/element.js`

**步骤**

- [ ] 实现 `createHttpTransport({ getEndpoint })`，按设计 §8 的六个端点实现六个必选方法，不实现任何可选方法。
- [ ] `streamEvents` 用 `fetch` + `ReadableStream` 解析 SSE（不用 `EventSource`，需要自定义 header 与 `AbortSignal`）：
  - `event: agent-event` → `{ type: 'event', seqId: data.seq_id, event: data }`
  - `event: done` → `{ type: 'terminal', run: toRunRef(data) }`，随后结束迭代
  - `: ping` → 忽略
  - 流在未收到 `done` 的情况下结束 → 抛 `StreamInterrupted`
- [ ] **实现设计 §6.1 的会话地址生命周期**：
  - `endpoint` 非空 → 挂载即 `loadConversation()`；`endpoint` 变化 → abort 当前流、清空消息与运行状态、装载新会话。旧流的后续事件必须被丢弃（用 generation 计数守卫）。
  - `endpoint` 为空且只有 `endpointResolver` → 挂载不调用；首次 `sendMessage()` 调用一次并把结果写回 `endpoint`，之后走正常路径。
  - `reload()` 只重拉当前 `endpoint`，不清除它。
- [ ] 中断恢复：捕获 `StreamInterrupted` → 派发 `dataagent-error` code `stream_interrupted` → 按 1s/2s/4s 退避用 `afterId` 重订；三次失败后每 3 秒 `loadConversation()` 轮询直到终态。
- [ ] 错误映射：非 2xx 读 `{message, hint}` → `conversation_unavailable`；网络异常 → `transport_unreachable`；结构不符 → `protocol_error`。
- [ ] 写 `examples/mock-bff/server.mjs`：约 60 行 node http 服务，实现六个端点，`/events` 按 8.1 格式发若干 `agent-event` 后发 `done`。**这是 T4 验收依赖的产物，必须与代码一同提交。**
- [ ] 写 `docs/bff-protocol.md`：收录设计 §8 的三张表（协议、线格式、DataAgent 映射），并写明分页要求与 Agent 查询端点的不同前缀。
- [ ] 补测试：会话地址生命周期三种情形；流终态与退避转轮询；403 + hint 映射；切换会话后旧流事件不污染新会话。
- [ ] 提交。

**验收：** `npm run test:sdk` 全绿；起 mock BFF 后能跑通装载→发送→流式→终态→停止全链路；切换 `endpoint` 后消息区正确重置。

---

## T5 — Widget 与 SPA 改为复用 SDK

**产出：** 仓库内不再有第二套聊天实现，Widget 对外行为零变化。**这是发布前的 dogfood。**

**涉及文件**

- 修改 `src/widget/WidgetChat.vue`、`src/widget/styles.js`
- 修改 `src/views/intelligence/NL2SqlChatV2.vue`
- 修改 `src/widget/__tests__/WidgetChat.spec.js`
- 移动 `output/playwright/widget-smoke.mjs` 与三张基线图 → `dataagent/dataagent-frontend/tests/e2e/widget/`

**步骤**

- [ ] `WidgetChat.vue` 的消息区与输入区替换为 `<dataagent-conversation>`，通过 `ref` 设置 `transportFactory = (endpoint) => nl2sqlTransport(inject('nl2sqlApi'), parseTopicKey(endpoint))`。保留登录态判断、历史抽屉联动；`state.outboundMessage` / `cancelSignal` 改为调用元素的 `sendMessage()` / `cancel()`。
- [ ] **会话切换只设置元素的 `endpoint`**，值为 `topic://{topic_id}`。不替换 transport 对象，不调 `reload()`——元素检测到会话键变化后自行 abort、重置、用新键重新调用工厂。全仓库只有这一条切换路径。
- [ ] 补测试：切换 topic 后消息区重置，且旧 topic 的残留事件不会渲染进新会话。
- [ ] `NL2SqlChatV2.vue` 替换中间会话区，保留 Agent 选择器、会话列表（用 `useTopicList`）、管理入口。
- [ ] **把 Widget smoke 脚本移入受版本控制的 `tests/e2e/widget/`**，并修正其中写死的产物路径：当前是 `frontend/dist/widget`（widget-smoke.mjs:11），实际产物在 `dataagent/dataagent-frontend/dist/widget`（vite.widget.config.js:16）。移动后 `git add` 脚本与三张基线图。
- [ ] 跑 `npm test`，修正受影响用例。
- [ ] 跑三形态 e2e，与基线图比对。
- [ ] 提交。

**验收：** `npm test` 全绿；三形态交互正常无布局回归；e2e 脚本已在 Git 中且能从干净 clone 跑起来；`grep -rn "v2StreamParser\|agentEvents/reducer" src/` 只剩 re-export。

---

## T6 — React 消费冒烟（本地 tarball）

**产出：** 确认包能被 React 项目消费，**在公共发布之前**。

**涉及文件**

- 新增 `packages/agent-conversation/examples/react/`（`package.json`、`src/App.tsx`、`vite.config.ts`、`README.md`）

**步骤**

- [ ] 写 React 示例：不安装 `vue`，只依赖本地 tarball；用 `useEffect` + `ref` 挂载元素、`addEventListener` 收事件、`ref.current.sendMessage(...)` 发消息、在 Light DOM 放一个 `slot="composer-actions"` 按钮。
- [ ] `cd packages/agent-conversation && npm pack` → 示例里 `npm i ../opendataworks-agent-conversation-0.1.0.tgz` → `npm run build` → dev server 手工冒烟。
- [ ] 逐项确认：元素渲染正常；样式在 Shadow DOM 内（宿主未 import 任何 CSS）；插槽按钮可见可点；控制台无 Vue 相关报错；`node -e "require('vue')"` 在示例目录失败（证明 Vue 确实打进了包）。
- [ ] 若发现接口需要调整，回到 T1–T4 修改——**此时尚未公共发布，修改零成本**。
- [ ] 提交。

**验收：** React 示例基于本地 tarball 完整可用。

---

## T7 — 公共发布

**前置：** T5（Widget/SPA dogfood）与 T6（React 冒烟）均已通过。

**涉及文件**

- 新增 `.github/workflows/npm-publish-sdk.yml`
- 修改 `packages/agent-conversation/README.md`

**步骤**

- [ ] README 写清：安装、`defineAgentConversation()`、两种接入方式（内置 HTTP transport / 自定义 `transportFactory`）、**两种模式下会话切换都只改 `endpoint`**、插槽、事件表、状态转换表，以及"必须由宿主后端代理，不要把 DataAgent 地址给浏览器"的接入前提。明确说明**不需要 import CSS**。
- [ ] 写 `npm-publish-sdk.yml`，触发 `push tags: ['sdk-v*']`。**两个工作目录必须显式声明：**

```yaml
      - name: Install & build
        working-directory: dataagent/dataagent-frontend
        run: npm ci && npm run test:sdk && npm run build:sdk
      - name: Pack & publish
        working-directory: dataagent/dataagent-frontend/packages/agent-conversation
        run: |
          npm pack
          node -e "…断言 tarball 不含 src/…"
          npm publish --access public
```

  `NODE_AUTH_TOKEN` 取 secret `NPM_TOKEN`，`setup-node` 设 `registry-url: https://registry.npmjs.org`。
- [ ] 在 npm 注册 `@opendataworks` scope，配置 `NPM_TOKEN`。
- [ ] 打 tag `sdk-v0.1.0` 发布；发布后在干净目录 `npm i @opendataworks/agent-conversation@0.1.0` 验证可安装。
- [ ] 把 T6 的 React 示例依赖从本地 tarball 改为 registry 版本，重新构建通过。
- [ ] 提交。

**验收：** 公共 npm 上可安装 `0.1.0`；React 示例基于 registry 版本构建通过。**此后下游 OntoFoundry 的 T7 可以开工。**

---

## T8 — 服务端接入认证

**产出：** 站点可开启服务端接入并用 access key 认证，存量站点零影响。**这是下游真实联调与生产部署的前置。**

**涉及文件**

- 修改 `dataagent/dataagent-backend/models/schemas.py`
- 修改 `dataagent/dataagent-backend/core/skill_admin_service.py`
- 修改 `dataagent/dataagent-backend/api/routes.py`、`api/admin_routes.py`
- 修改 `dataagent/dataagent-frontend/src/views/settings/WidgetAccessConfig.vue`
- 修改 `dataagent/dataagent-backend/tests/test_widget_runtime_routes.py`、`test_skill_admin_service.py`

**步骤**

- [ ] `WidgetAllowedSite` 增加 `server_side: { enabled: bool = False, access_key_hash: str = "" }`；归一化时缺失按 `enabled=False`。
- [ ] **站点读取 DTO 只暴露 `server_side: { enabled, key_configured }`，不含 hash；站点写入 DTO 不接受 `access_key_hash`。** 通用 settings 的 PUT 必须保留已存在的 hash（用"未提供即保留"语义，不能整体覆盖）。
- [ ] 新增两个独立端点（`settings_router`，已带 `require_admin`）：
  - `POST /api/v1/nl2sql-admin/widget-sites/{website_id}/access-key` → 生成 32 字节随机密钥，存 sha256，响应返回明文（仅此一次），并置 `enabled=true`。
  - `DELETE /api/v1/nl2sql-admin/widget-sites/{website_id}/access-key` → 清除 hash 并置 `enabled=false`。
- [ ] `_request_context` 在站点匹配之后、Origin 校验之前插入分支：站点 `server_side.enabled` 为真时读 `X-ODW-Access-Key`，sha256 比对不匹配 → 403 `"Widget access key is invalid"`；匹配则跳过 Origin 校验。
- [ ] `_origin_allowed` 的"空 Origin 放行"仅在站点未启用 `server_side` 的分支上生效。
- [ ] 设置页增加站点级开关与密钥生成/轮换按钮，明文只在生成后展示一次并提示复制。
- [ ] 补测试，覆盖设计 §12"后端"小节全部条目，**包括通用 settings PUT 回写不会覆盖或清除 hash** 这一条。
- [ ] 提交。

**验收：** 新旧站点行为符合矩阵；`pytest` 全绿；明文与 hash 均不出现在任何读取接口响应中。

---

## T9 — 隔离语义回归测试

**产出：** 把"任务级端点受 topic context 约束"这条语义锁进测试，防止将来被无意改掉。

**涉及文件**

- 修改 `dataagent/dataagent-backend/tests/test_widget_runtime_routes.py`

**步骤**

- [ ] 构造两个不同 `external_user_id` 的 widget 上下文，各建一个 topic 与 task。
- [ ] 断言用 B 的上下文访问 A 的 task，以下五个端点全部 404：`GET /tasks/{id}`、`GET /tasks/{id}/sdk-events/stream`、`POST /tasks/{id}/cancel`、`POST /tasks/{id}/permission-decision`、`POST /tasks/{id}/question-answer`。
- [ ] 断言同 `external_user_id` 访问正常。
- [ ] 提交。

**验收：** 五条 404 断言通过（当前实现即应通过，依据 `core/topic_task_store.py:1465` 的 context 谓词）。

---

## 验证清单（合并前一次性跑完）

- [ ] `cd dataagent/dataagent-frontend && npm ci && npm test && npm run test:sdk`
- [ ] `npm run build && npm run build:widget && npm run build:sdk`
- [ ] `node tests/e2e/widget/widget-smoke.mjs`（三形态，从受版本控制的新位置）
- [ ] `cd dataagent/dataagent-backend && pytest -q`
- [ ] React 示例基于已发布版本构建并手工冒烟
- [ ] `grep -rn "api/v1/agent\b\|agent-events/stream" dataagent/` 无结果（**搜索范围不含 `docs/`**——设计文档为说明迁移必然包含旧路径）
- [ ] `grep -rn "@/api\|@/views" dataagent/dataagent-frontend/packages/agent-conversation/src` 无结果

## 发布与回退

任务顺序：T1→T9 依序合并。**T7 的公共发布必须排在 T5、T6 之后**——一旦发到公共 registry 就不可撤回，先用真实外壳和真实 React 项目验证接口。

对下游的两个交付点：

| 交付点 | 下游依赖它的任务 |
| --- | --- |
| T7（npm 发布 0.1.0） | OntoFoundry T7（前端接入） |
| T8（站点 access key） | OntoFoundry 的真实联调、**T7 集成包**验收、生产部署 |

OntoFoundry 的 T1–T6 可以基于 mock 与本设计的协议表并行推进，不必等待上游。

回退：

- SDK 包：npm 版本不可撤回，回退方式是发布修订版本并让下游改依赖版本号。T7 前的 dogfood 与 pack 内容断言是硬门禁。
- Widget/SPA 改造（T5）：纯前端，回滚提交并重新构建镜像。
- 服务端认证（T8）：站点配置存于 `da_agent_settings.raw_json`，回退代码后未启用 `server_side` 的站点不受影响；已启用的站点需在管理端关闭。无数据库迁移。
