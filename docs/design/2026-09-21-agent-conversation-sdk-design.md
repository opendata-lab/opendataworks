# Agent Conversation SDK 设计

**日期:** 2026-09-21
**涉及栈:** DataAgent 前端（Vue 3 / Vite 5）、DataAgent 后端（FastAPI）
**配套文档:** `docs/plans/2026-09-21-agent-conversation-sdk-plan.md`
**下游消费方设计:** OntoFoundry 仓库 `docs/design/2026-09-21-dataagent-conversation-sdk-integration-design.md`

## 1. 现状

DataAgent 的会话能力目前有两个外壳，共用同一套内核：

| 外壳 | 位置 | 形态 |
| --- | --- | --- |
| SPA 智能问数页 | `dataagent/dataagent-frontend/src/views/intelligence/NL2SqlChatV2.vue`（3039 行） | 路由页面 |
| 嵌入式 Widget | `dataagent/dataagent-frontend/src/widget/`（entry.js 846 行、WidgetChat.vue 1197 行、styles.js 1471 行） | IIFE bundle + Shadow DOM |

共用的会话内核已经存在，位于 `src/views/intelligence/`：

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `useNl2SqlChat.js` | 581 | 会话状态机：消息装载、任务生命周期、SSE 订阅与重连。**已经通过 `options.api` 接收客户端**，但同时还管 Topic 列表、创建/删除/切换 Topic 与运行时配置 |
| `v2StreamParser.js` | 274 | AgentEvent → 消息块（block）投影 |
| `agentEvents/reducer.js` | — | 事件归约 |
| `chatMessage.js` | 248 | Markdown 渲染、错误文案提取、IME 回车判定 |
| `useChatMessageActions.js` | 94 | 复制、反馈 |
| `useSlashCommands.js` + `SlashCommandMenu.vue` | 156 + — | 斜杠命令 |
| `ToolOutputRenderer.vue` | 1133 | 工具调用卡片 |
| `PermissionConfirmationCard.vue` | 261 | 权限确认卡片 |
| `QuestionSelectionCard.vue` | 168 | Agent 提问卡片 |
| `components/ResultDataTable.vue` | — | 结果表格 |
| `components/SqlCodePanel.vue` | — | SQL 面板。**直接 `import { createNl2SqlApiClient } from '@/api/nl2sql'` 并调用 `queryApi.executeSql`**（SqlCodePanel.vue:63,182） |

网络层由 `src/api/nl2sql.js` 的 `createNl2SqlApiClient({ baseURL, timeout, defaultHeaders, onUnauthorized })` 提供。`WidgetChat.vue:389` 构造该客户端后用 `provide('nl2sqlApi', api)` 注入子树。

Widget 的构建产物由 `vite.widget.config.js` 产出，形态是 `formats: ['iife']`、`inlineDynamicImports: true`、`cssCodeSplit: false` 的单文件 bundle，只能通过 `<script src>` 加载，不能被其他项目的构建器作为依赖消费。

npm 管理根是 `dataagent/dataagent-frontend/`（仓库根**没有** `package.json`）。该包是 `"private": true`，仓库内没有任何 `.npmrc` 或 registry 配置，CI 中只有 docker 与 vercel 流程。

## 2. 问题

OntoFoundry（本体平台）需要在自动建模工作台的中间栏放一个完整的 Agent 会话区。它是 React + TypeScript 项目，当前自己实现了一套：`AgentStream.tsx`（119 行）+ `lib/dataagentStream.ts`（219 行 SSE reducer）+ `BuilderPage.tsx` 里的消息列表和输入框。这带来三个问题：

1. **重复实现。** 流式协议解析、消息投影、运行状态机在两边各有一份，DataAgent 的事件协议一变两边都要改。OntoFoundry 那份只覆盖了一个子集——没有工具调用卡片、没有权限确认、没有 Agent 提问、没有附件。
2. **现有 Widget 不适合这个位置。** Widget 是"带悬浮入口、站点标题栏、历史抽屉、登录页"的完整产品外壳；建模工作台需要的是嵌入三栏布局中间的一块纯对话区域，并且要在输入框里插入宿主自己的"开始建模"按钮。
3. **IIFE bundle 不可被依赖。** 即使裁掉外壳，当前产物也无法让 OntoFoundry 固定版本、获得类型声明、在 CI 里做构建期校验。

## 3. 目标与非目标

### 目标

- 把已有的会话内核和消息渲染能力提取成一个**版本化、可被任意前端框架消费**的包。
- 包对外只有一个完整元素，不要求宿主理解 AgentEvent、SSE、任务状态机。
- 包**不直接访问 DataAgent**。网络出口由宿主注入，宿主用自己的后端（BFF）适配 DataAgent。浏览器里不出现 DataAgent 地址和凭据。
- 现有 Widget 和 SPA 改为复用同一内核。

### 非目标（v1 明确不做）

- 不做 `contextRef` 业务上下文协议。业务上下文通过不透明的 `metadata` 透传。
- 不做 launch token 签发体系。
- 不在 SDK 内提供 Agent 选择器、模型选择器、会话历史列表、站点标题栏、埋点上报——这些属于外壳。
- **不在 SDK 内提供 Topic 列表 / 创建 / 删除 / 切换。** SDK 的作用域是**单个会话**（见 §5.1）。

## 4. 方案总览

```text
dataagent-frontend/                     ← npm 管理根
├── src/views/intelligence/             SPA 页面（保留，改为 import 内核）
├── src/widget/                         Widget 外壳（保留，改为组合 SDK 元素）
└── packages/agent-conversation/        ← 新增，独立构建与发布
    ├── src/core/                       从 views/intelligence 提取的会话内核
    ├── src/ui/                         消息列表、输入框、卡片
    ├── src/element.js                  <dataagent-conversation> 自定义元素
    ├── src/transport/http.js           默认 HTTP transport（宿主 BFF 协议）
    ├── types/index.d.ts                TypeScript 声明
    └── package.json                    @opendataworks/agent-conversation
```

依赖方向：

```text
SPA 页面 ─┐
Widget  ─┼─→ packages/agent-conversation（内核 + UI）
宿主应用 ─┘        │
                   └─→ transport（宿主注入）→ 宿主 BFF → DataAgent API
```

**关键约束：`packages/agent-conversation` 不得 import `@/api/nl2sql`。** 网络访问只能通过 transport 接口，由 lint 规则强制。

## 5. 会话内核的提取

### 5.1 拆分边界（先定义，再动手）

`useNl2SqlChat.js` 当前同时承担两类职责，不能整体改名搬走：

| 职责 | 去向 | 理由 |
| --- | --- | --- |
| 单会话的消息装载、当前任务生命周期、流式订阅与重连、发送与取消 | **进包** | 这是 SDK 的全部作用域 |
| Topic 列表、新建/删除/切换 Topic、运行时配置（`runtimeApi`） | **留在 SPA / Widget 外壳** | 属于产品外壳，OntoFoundry 用自己的会话切换 UI |

外壳切换会话的方式不是"替换 transport"，而是**改元素的 `endpoint`**（见 §6.1）。这样外壳不需要理解 SDK 内部状态机的重置时机。

### 5.2 逐文件去向

| 源 | 去向 | 改动 |
| --- | --- | --- |
| `useNl2SqlChat.js` 的单会话部分 | `src/core/useConversation.js` | 签名改为 `useConversation({ transport })`；`options.api` 的多命名空间调用收敛到 §7 的 transport 方法 |
| `useNl2SqlChat.js` 的 Topic 管理部分 | 留在 `views/intelligence/useTopicList.js`（新建） | 供 SPA 与 Widget 外壳使用 |
| `v2StreamParser.js` | `src/core/streamParser.js` | 原样移动 |
| `agentEvents/reducer.js` | `src/core/agentEvents/reducer.js` | 原样移动 |
| `chatMessage.js` | `src/core/message.js` | 原样移动 |
| `useChatMessageActions.js` | `src/core/useMessageActions.js` | 反馈走可选的 `transport.submitFeedback` |
| `ToolOutputRenderer.vue` | `src/ui/ToolOutput.vue` | 原样移动 |
| `PermissionConfirmationCard.vue` | `src/ui/PermissionCard.vue` | 提交走 `transport.submitInteraction` |
| `QuestionSelectionCard.vue` | `src/ui/QuestionCard.vue` | 同上 |
| `components/ResultDataTable.vue` | `src/ui/components/ResultDataTable.vue` | 原样移动 |
| `components/SqlCodePanel.vue` | `src/ui/components/SqlCodePanel.vue` | **删除对 `@/api/nl2sql` 的 import**，改走可选的 `transport.executeSql`（见 5.3） |
| `useSlashCommands.js`、`SlashCommandMenu.vue` | `src/ui/slash/` | 依赖可选的 `transport.listSlashCommands` |

原位置改为从包内 re-export，使 `NL2SqlChatV2.vue` 与 `WidgetChat.vue` 的 import 改动最小，且不会出现两份副本。

### 5.3 可选能力与降级规则

transport 的可选方法缺失时，SDK **静默关闭对应 UI，不得抛错**：

| 可选方法 | 缺失时的行为 |
| --- | --- |
| `executeSql` | SQL 面板降级为只读：语法高亮 + 复制按钮，隐藏"执行"，不显示结果表格 |
| `listSlashCommands` | 输入 `/` 不弹菜单 |
| `setPermissionMode` | 不渲染权限模式切换器 |
| `submitFeedback` | 不渲染点赞/点踩 |

OntoFoundry 的 BFF 只实现必选方法，因此建模工作台里 SQL 卡片是只读的——这是正确的，本体平台不该具备执行任意 SQL 的能力。

## 6. 公开接口

```js
import { defineAgentConversation } from '@opendataworks/agent-conversation'

defineAgentConversation()          // 注册 <dataagent-conversation>，幂等
defineAgentConversation('my-chat') // 可选：自定义标签名，避免多版本共存冲突
```

样式不需要宿主 import，由元素自行注入 Shadow Root（见 §11.1）。

### 6.1 会话地址与生命周期

这是最容易出错的一处，规则写死：

| 情形 | 宿主怎么做 | 元素行为 |
| --- | --- | --- |
| 会话已存在 | 设 `endpoint` 属性为该会话地址 | **挂载时立即装载**历史与当前任务 |
| 会话尚未创建 | 只设 `endpointResolver` 属性，`endpoint` 留空 | 挂载时**不**调用 resolver，不发任何请求；首次 `sendMessage()` 时调用一次，把结果写回 `endpoint` 属性，随后走正常装载路径 |
| 切换到另一个会话 | **改 `endpoint` 属性**（不要调 `reload()`） | 检测到属性变化 → abort 当前流 → 清空消息与运行状态 → 装载新会话 |

`endpoint` 属性变化是会话切换的**唯一**信号。宿主不需要、也不应该在切换时调用 `reload()`——那会与 React 状态更新产生竞态。`reload()` 只用于"同一会话、重新拉取"。

### 6.2 元素

```html
<dataagent-conversation
  endpoint="/api/v1/workspaces/w-1/sessions/s-1/agent-conversation"
  placeholder="描述你的建模需求"
>
  <div slot="composer-actions">
    <button id="start-modeling">开始建模</button>
  </div>
</dataagent-conversation>
```

HTML 属性：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `endpoint` | string | 宿主 BFF 会话地址。非空即触发装载；变化即切换会话 |
| `placeholder` | string | 输入框占位文案 |
| `active` | boolean attr | 缺省为真。取消时断开 SSE 订阅并停止轮询，用于宿主的标签页休眠 |
| `disabled` | boolean attr | 禁用输入与发送，仍展示历史 |

JS 属性（经 `ref` 设置）：

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `endpointResolver` | `() => string \| Promise<string>` | 仅在 `endpoint` 为空且发生首次发送时调用一次 |
| `transportFactory` | `(endpoint: string) => ConversationTransport` | 自定义网络层。**不改变 `endpoint` 的地位**——`endpoint` 仍是会话键，变化时元素用新值重新调用工厂并重置状态。缺省实现是内置 HTTP transport |
| `value` | string | 输入框当前草稿，可读可写 |

**为什么是 `transportFactory` 而不是 `transport`。** 早期方案让宿主直接塞一个 `transport` 对象并"忽略 `endpoint`"，结果是两套互斥的会话切换模型：用 `endpoint` 的宿主改属性切换，用 `transport` 的宿主只能自己替换对象——而"替换 transport 是否重置内部状态"没有定义，Widget 照着实现会继续用旧 Topic。改成工厂后，**`endpoint` 在两种模式下都是唯一会话键**，切换语义只有一条。

Widget 这类直连场景把 `endpoint` 用作 topic 标识即可（例如 `topic://{topic_id}`），工厂据此构造直连 DataAgent 的 transport。元素不解析 `endpoint` 的内容，只把它当作不透明的会话键做相等性比较。

### 6.2.1 自定义元素的 JS 接口如何落地

`defineCustomElement` 只为**声明过的 props** 生成属性访问器，且默认不暴露方法。因此实现规则写死：

- `endpoint`、`placeholder`、`active`、`disabled` 声明为普通 props（带 attribute 映射）。
- `endpointResolver`、`transportFactory` 声明为 props 但标记为非 attribute（对象/函数值只能经 JS 属性传入）。
- `value` 需要**可读可写**，由包装类定义 `get value()` / `set value(v)` 访问器，转发到内部组件实例的响应式状态；不能只靠单向 prop。
- `reload()` / `sendMessage()` / `cancel()` / `focus()` 由组件 `defineExpose`，再由包装类把同名方法代理到内部实例。
- **挂载前赋值必须生效**：包装类在 `connectedCallback` 之前收到的属性写入需缓存，挂载时回放。宿主用 `ref` 在同一次 React 渲染中赋值时就是这个时序。

这三种时序（挂载前赋值、挂载后赋值、移除后重新插入）都要有测试。

### 6.3 方法

| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `reload()` | `() => Promise<void>` | 同一会话重新装载。不清除 `endpoint` |
| `sendMessage()` | `(content?: string, options?: SendOptions) => Promise<void>` | `content` 省略时取 `value`。成功后按 `options.clearDraft`（缺省 `true`）清空输入框 |
| `cancel()` | `() => Promise<void>` | 停止当前任务；无运行中任务时为空操作 |
| `focus()` | `() => void` | 聚焦输入框 |

```ts
interface SendOptions {
  metadata?: Record<string, unknown>  // 对 SDK 完全不透明，原样提交给 transport
  clearDraft?: boolean
}
```

### 6.4 DOM 事件

全部 `bubbles: true, composed: true`，可跨 Shadow DOM 用 `ref.addEventListener` 捕获。

| 事件 | `detail` | 触发时机 |
| --- | --- | --- |
| `dataagent-ready` | `{}` | 一次会话装载完成（切换会话后会再次触发） |
| `dataagent-draft-change` | `{ value: string }` | 输入框内容变化 |
| `dataagent-run-change` | `{ taskId, status, detail }` | 运行状态变化 |
| `dataagent-complete` | `{ taskId, status, metadata }` | 任务到达终态 |
| `dataagent-error` | `{ code, message, hint? }` | 网络或协议错误 |

**`metadata` 必须能跨刷新恢复。** SDK 内部按 taskId 记忆 metadata 只在本次会话生命周期内有效；刷新后由 `loadConversation()` 返回的 `RunRef.metadata` 恢复。因此**宿主 BFF 有义务在快照里回填 metadata**（OntoFoundry 从 `dataagent_task_mode` 列重建 `{ mode }`）。没有这一条，刷新后恢复的流完成时宿主无法判断该不该刷新业务区。

### 6.5 插槽

| 插槽 | 位置 | 用途 |
| --- | --- | --- |
| `composer-overlay` | 输入框**上方** | 需要贴着输入框的浮层，如斜杠命令菜单 |
| `composer-actions` | 输入框 footer 左侧，标准发送按钮之前 | 宿主业务按钮 |
| `composer-toolbar` | footer **下方**独立一行 | 宿主自己的输入区控件 |
| `empty` | 消息区为空时 | 宿主自定义空态 |

**为什么是四个而不是两个。** 最初只设计了 `composer-actions` 与 `empty`——那是照
OntoFoundry"在输入框里加一个按钮"的需求来的。实际让 Widget 组合 SDK 时才发现它的
输入区远不止一个按钮：斜杠命令菜单要贴着 textarea 浮起，权限模式与模型选择器占据
footer 下方独立一行。两者都是产品选择，SDK 不该有意见——所以它提供**位置**而不是
控件。插槽的**顺序**同样是契约的一部分（浮层在输入框之前、工具栏在 footer 之后），
有测试钉住。

插槽内容来自元素的 Light DOM（宿主用 React/Vue/原生渲染），必须真正被投影到 Shadow DOM 内的对应位置。实现方式见 §11.1。

### 6.6 错误码

| `code` | 含义 | SDK 行为 |
| --- | --- | --- |
| `transport_unreachable` | 宿主 BFF 不可达 | 展示错误条，输入保持可用 |
| `conversation_unavailable` | BFF 返回 4xx/5xx 且带可读 `message` | 展示 `message` 与 `hint` |
| `stream_interrupted` | 流中断 | 按 1s/2s/4s 退避用 `afterId` 续订，上限 3 次；耗尽后降级为每 3 秒 `loadConversation()` 轮询直到终态 |
| `protocol_error` | 响应不符合协议 | 展示通用错误，写 `console.error` |

## 7. Transport 契约

### 7.1 运行状态

SDK 使用自己的状态词表，**不是** DataAgent 的原始 `task_status`。转换由 transport 负责，转换表写死：

| DataAgent `task_status` | SDK `RunStatus` |
| --- | --- |
| `waiting` | `queued` |
| `running` | `running` |
| `waiting_input` | `waiting_input` |
| `waiting_permission` | `waiting_permission` |
| `finished` | `finished` |
| `error` | `failed` |
| `suspended` | `cancelled` |
| 任务不存在 | `failed` |

依据：`dataagent/dataagent-backend/core/task_status.py:17,23`（`ACTIVE_TASK_STATUSES` / `TERMINAL_TASK_STATUSES`）。

```ts
type RunStatus =
  | 'idle' | 'queued' | 'running'
  | 'waiting_input' | 'waiting_permission'
  | 'finished' | 'cancelled' | 'failed'

const ACTIVE:   RunStatus[] = ['queued', 'running', 'waiting_input', 'waiting_permission']
const TERMINAL: RunStatus[] = ['finished', 'cancelled', 'failed']
```

`waiting_input` 与 `waiting_permission` 属于**活动**状态。宿主的"禁止编辑业务数据"互斥条件必须覆盖全部四个活动状态，不能只判 `running`。

### 7.2 接口

```ts
interface RunRef {
  taskId: string
  status: RunStatus
  detail: string
  metadata?: Record<string, unknown>
}

interface ConversationMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  blocks?: unknown[]
  attachments?: { name: string; relPath: string; mediaType?: string }[]
  taskId?: string
  createdAt?: string
}

interface ConversationSnapshot {
  messages: ConversationMessage[]
  run: RunRef | null        // 活动任务；无活动任务时给最近一次终态 run，供恢复 metadata
}

// 流的每一项要么是已解析的事件，要么是终态。终态必须是最后一项。
type StreamItem =
  | { type: 'event'; seqId: number; event: unknown }
  | { type: 'terminal'; run: RunRef }

interface ConversationTransport {
  // 必选
  loadConversation(): Promise<ConversationSnapshot>
  sendMessage(input: { content: string; metadata?: Record<string, unknown> }): Promise<RunRef>
  streamEvents(input: { taskId: string; afterId: number; signal: AbortSignal }): AsyncIterable<StreamItem>
  cancelRun(input: { taskId: string }): Promise<RunRef>
  submitInteraction(input: {
    taskId: string
    kind: 'permission' | 'question'
    requestId: string
    payload: unknown
  }): Promise<void>
  fileUrl(relPath: string): string

  // 可选，缺失时按 §5.3 降级
  executeSql?(input: { sql: string; signal?: AbortSignal }): Promise<{ columns: string[]; rows: unknown[][] }>
  listSlashCommands?(): Promise<{ name: string; description: string }[]>
  setPermissionMode?(mode: string): Promise<void>
  submitFeedback?(messageId: string, value: 1 | -1 | 0): Promise<void>
}
```

**`streamEvents` 吐的是已解析的对象，不是字节。** 这是刻意的：DataAgent 原生 SSE 是无事件名的裸 `data:` 帧、靠 EOF 终止（`routes.py:85` 的 `_encode_sse`，`:656` 的循环 break），而宿主 BFF 可以也应该发出显式终态。把线格式差异关在各 transport 内部，SDK 内核只消费 `StreamItem`。

**终态必须显式，且必须经过状态校验。** EOF 不足以区分"任务结束"和"网络断了"。规则：

- 内置 HTTP transport：读到 `event: done` 才产出 `terminal`。
- 直连 transport：EOF 后调用 `getTask`，**只有查到 `finished` / `error` / `suspended` 才允许合成 `terminal`**；查到活动态说明是上游流提前结束，必须抛 `StreamInterrupted` 走续订路径。
- BFF 侧同理：上游流结束后先查任务状态，活动态时不得发 `done`，应继续订阅。

流在没有 `terminal` 的情况下结束一律按 `stream_interrupted` 处理。这一条防的是"上游流因超时或重启提前断开，客户端却把它当成任务完成"——那会让宿主在任务仍在跑的时候去刷新业务数据。

## 8. 内置 HTTP transport 与宿主 BFF 协议

设置 `endpoint` 时 SDK 构造内置 transport。**下表就是宿主 BFF 必须实现的全部契约。**

| transport 方法 | 请求 | 响应 |
| --- | --- | --- |
| `loadConversation` | `GET {endpoint}` | `ConversationSnapshot` |
| `sendMessage` | `POST {endpoint}/messages`，body `{ content, metadata }` | `RunRef` |
| `streamEvents` | `GET {endpoint}/events?after_id={n}`，`Accept: text/event-stream` | 见 8.1 |
| `cancelRun` | `POST {endpoint}/cancel`，body `{ task_id }` | `RunRef` |
| `submitInteraction` | `POST {endpoint}/interactions`，body `{ task_id, kind, request_id, payload }` | `{ "ok": true }` |
| `fileUrl` | `GET {endpoint}/files/{rel_path}` | 二进制 |

错误响应统一为 `{ "message": "...", "hint": "..." }`，HTTP 状态码表达类别。`hint` 承载"怎么修"的可操作指引。

### 8.1 BFF 的 SSE 线格式

BFF **不得字节透传** DataAgent 的原生流，必须转换为：

```
event: agent-event
data: {"seq_id":12,"...":"原始 AgentEvent 记录"}

: ping

event: done
data: {"task_id":"t-1","status":"finished","detail":"...","metadata":{"mode":"model"}}
```

- `agent-event` 的 `data` 是 DataAgent 原始事件记录原样转发，SDK 用 `seq_id` 维护 `afterId`。
- `done` 的 `data` 是终态 `RunRef`（下划线命名，transport 负责转成驼峰）。**它必须在 BFF 完成自己的收尾工作之后才发出**——对 OntoFoundry 来说就是候选回写完成之后，这样宿主收到 `dataagent-complete` 时刷新业务区一定能看到新数据。
- `: ping` 保活，SDK 忽略。

### 8.2 协议到 DataAgent 内部 API 的映射

供实现 BFF 的人参考。**前缀是 `/api/v1/nl2sql`，事件流是 `/sdk-events/stream`**（`api/routes.py:76,594`）。

| 用途 | DataAgent 端点 | routes.py |
| --- | --- | --- |
| 创建会话 | `POST /api/v1/nl2sql/topics` | :285 |
| 读取历史消息（分页） | `GET /api/v1/nl2sql/topics/{topic_id}/messages` | :347 |
| 上传文件 | `POST /api/v1/nl2sql/topics/{topic_id}/files` | :371 |
| 下载文件 | `GET /api/v1/nl2sql/topics/{topic_id}/files/{rel_path:path}` | :391 |
| 提交任务 | `POST /api/v1/nl2sql/tasks/deliver-message` | :482 |
| 查询任务 | `GET /api/v1/nl2sql/tasks/{task_id}` | :544 |
| 取终态消息 | `GET /api/v1/nl2sql/tasks/{task_id}/message` | :553 |
| 事件流 | `GET /api/v1/nl2sql/tasks/{task_id}/sdk-events/stream?after_id=` | :594 |
| 取消 | `POST /api/v1/nl2sql/tasks/{task_id}/cancel` | :687 |
| 权限确认 | `POST /api/v1/nl2sql/tasks/{task_id}/permission-decision` | :699 |
| 回答提问 | `POST /api/v1/nl2sql/tasks/{task_id}/question-answer` | :737 |
| **查询 Agent 是否存在** | `GET /api/v1/dataagent/agents/{agent_id}` | `admin_routes.py:95,428` |

最后一行注意两点：它在 `agents_public_router` 上（无 admin 依赖），且**前缀是 `/api/v1/dataagent`，与运行时前缀不同**——宿主若把运行时前缀做成配置项，这一条不能套用同一个配置。`GET /topics?agent_id=` 只过滤已有 Topic，Agent 不存在时也返回 200 空数组，**不能**用来做 Agent 存在性检查。

历史消息接口默认 `page_size=200`、上限 500（`models/schemas.py:684`）。BFF 必须分页取全，否则长会话会静默截断。

本节两张表同时发布在 SDK 包的 `docs/bff-protocol.md` 中。

## 9. 服务端接入认证

### 9.1 现状与缺口

`api/routes.py:166` 的 `_request_context` 对 `X-ODW-Client: widget` 的请求做三段校验：`X-ODW-Website-Id` 必填并命中 `widget_allowed_sites` 白名单 → `Origin` 命中站点 `allowed_origins` → 身份（`X-ODW-User-Id`，或站点开启 `allow_anonymous` 时的 `X-ODW-Visitor-Id`）。

缺口在 `_origin_allowed`（:155）：

```python
if not normalized_origin:
    # 同源请求 / 非浏览器客户端不会发送 Origin 头，放行
    return True
```

服务端发起的请求不带 `Origin`，因此**任何能连到 DataAgent 的进程只要知道一个 `website_id` 就能创建会话、提交任务**。浏览器场景下不是问题（浏览器强制带 `Origin`），但宿主 BFF 正是服务端发起，直接复用等于没有服务认证。

### 9.2 站点服务端接入

`widget_allowed_sites` 的站点条目增加：

```json
{
  "website_id": "ontofoundry",
  "allowed_origins": [],
  "allow_anonymous": false,
  "server_side": { "enabled": true, "access_key_hash": "<sha256 hex>" }
}
```

校验规则：

- 站点未配置 `server_side` 或 `enabled=false`：**行为完全不变**（存量站点零影响，含空 `Origin` 仍放行）。
- 站点 `server_side.enabled=true`：
  - 必须携带 `X-ODW-Access-Key`，其 sha256 等于 `access_key_hash`，否则 403。
  - 校验通过后跳过 `Origin` 校验（服务端调用本就没有 `Origin`），`allowed_origins` 对该站点不再生效。

**密钥管理是独立端点，不走通用 settings 的 GET/PUT。** 这是必须的：通用设置接口会把读到的对象整体写回，掩码值会覆盖真实 hash。

| 端点 | 行为 |
| --- | --- |
| `POST /api/v1/nl2sql-admin/widget-sites/{website_id}/access-key` | 生成 32 字节随机密钥，存 sha256，**响应中返回且仅此一次返回明文** |
| `DELETE /api/v1/nl2sql-admin/widget-sites/{website_id}/access-key` | 关闭服务端接入并清除 hash |

站点读取 DTO 只暴露 `server_side: { enabled: bool, key_configured: bool }`，**不含 hash**。写入 DTO **不接受** `access_key_hash` 字段——它只能由上面两个端点改写。密钥存放在 `da_agent_settings.raw_json`，与现有设置机制一致，不需要数据库迁移。

### 9.3 会话隔离语义

`core/topic_task_store.py:446` 的可见性过滤是 `source='widget' AND website_id=? AND external_user_id=?`。因此 `X-ODW-User-Id` 就是隔离边界。

宿主若用真实终端用户 ID，同一业务会话就无法被第二个成员打开；若用业务资源 ID，隔离粒度与业务资源一致。OntoFoundry 选择后者（`ontofoundry:{workspace_id}:{session_id}`），权限判断留在 OntoFoundry BFF。本设计确认这一约定。

**副作用（接受）：** DataAgent 管理端的 Widget 用户列表里，OntoFoundry 会呈现为"每个建模会话一个伪用户"。v1 不为此增加分组维度。

任务级端点（`GET /tasks/{id}`、`/sdk-events/stream`、`/cancel`、`/permission-decision`、`/question-answer`）**同样受这一隔离约束**：它们调用的 `store.get_task(task_id, context=...)` 内部会套用 topic 的 context 谓词（`topic_task_store.py:1465`）。跨 `external_user_id` 访问返回 404，不是遗留漏洞。本次为这条语义补回归测试，防止将来被无意改掉。

## 10. 现有 Widget 的改造

Widget 保持对外行为与接入方式完全兼容（`data-*` 属性、`window.OpenDataWorksWidget` 全局 API、事件名、IIFE bundle URL 不变），内部改为：

- `WidgetChat.vue` 渲染 `<dataagent-conversation>`，设置 `transportFactory`——它接收 `endpoint` 并返回由 `createNl2SqlApiClient` 适配出的直连 transport（实现全部可选方法，含 `executeSql`）。
- 该 transport 的 `streamEvents` 把裸 `data:` 帧解析成 `{type:'event'}`，EOF 后调用 `getTask`，**仅在终态时**合成 `{type:'terminal'}`，并按 §7.1 做状态转换。
- Topic 列表/新建/切换改用 `useTopicList.js`（§5.1）。**切换会话统一改元素的 `endpoint`**（直连场景用 `topic://{topic_id}` 作为会话键），由元素触发 abort + 重置 + 用新键重新调用工厂。Widget 不自己替换 transport 对象，也不调 `reload()`——切换语义全仓库只有一条。
- `entry.js`、`OpenDataWorksWidget.vue`、`useWidgetGeometry.js`、`tracking.js`、`config.js` 全部保留：外壳不属于 SDK。
- `styles.js` 中属于消息区/输入区的样式迁入包内，外壳样式留在 Widget。

`NL2SqlChatV2.vue` 同样复用包内内核，保留 SPA 特有的 Agent 选择器、会话列表与管理入口。

## 11. 打包与分发

### 11.1 产物形态与样式装载

| 项 | 取值 | 理由 |
| --- | --- | --- |
| 模块格式 | **仅 ESM** | 见下 |
| 元素实现 | **`defineCustomElement`**（Vue 官方自定义元素），不是 `createApp().mount()` | 只有它能把 Light DOM 的 `slot="composer-actions"` 正确投影进组件的 `<slot>`。`createApp().mount()` 不接收 vnode slots，宿主按钮不会显示 |
| Shadow Root | `defineCustomElement` 自带（`shadowRoot: true`） | 同时解决"断开后重新插入时重复 `attachShadow` 抛错" |
| 样式 | **编译为 JS 内联字符串**，由元素注入 Shadow Root；宿主**不需要** import 任何 CSS | 宿主 import 的文档级 CSS 进不了 Shadow DOM；`new URL('./style.css', import.meta.url)` 在 UMD 下不可靠。内联是唯一在 ESM/UMD 都成立的方案 |
| Vue | 打进包内，不作为 peerDependency | 下游是 React 项目 |
| Element Plus | 打进包内，按需引入 | 卡片与表格依赖它 |
| Element Plus 弹层 | **包内所有弹层统一 `:teleported="false"`** | 见下 |
| 类型 | 手写 `types/index.d.ts` | 公开面很小 |

**图表依赖与 UMD：现状与未决项。**

工具调用卡片（`ToolOutputRenderer`）依赖 `ChartSpecView`，后者 `import * as echarts`，因此 echarts 目前在主 chunk 里。

**已确认可行但尚未启用：** `defineAsyncComponent(() => import('./ChartSpecView.vue'))` 能正确把 echarts 分出独立 chunk。阻碍不在构建而在测试——它会让 `ToolOutputRenderer.spec.js` 里三处 `findComponent(ChartSpecView)` 断言失效，`flushPromises()` 与 `vi.dynamicImportSettled()` 都无法把已解析的组件送进组件树。切换前需要先定一个测试策略（改断言渲染产物、还是在测试中替换异步边界）。代码里已留 `TODO(bundle)` 标注具体诊断。

**模块格式暂定仅 ESM。** 单文件 UMD 输出强制 `inlineDynamicImports`，与代码分割互斥；一旦启用上面的懒加载，UMD 就必须放弃。既然 UMD 没有已知消费方（OntoFoundry 用 Vite/React，Widget 由应用侧单独打 IIFE），不值得为它锁死主包的体积策略。

**Element Plus 弹层必须关闭 teleport。** `el-dropdown`、`el-popover`、`el-select`、`el-tooltip` 默认 teleport 到 `document.body`，而包的样式只注入 Shadow Root——弹层会脱离样式作用域，表现为无样式、层级错乱或定位偏移。这不是"需要验证"的风险，是已知行为。

落地规则：包内每一处 Element Plus 弹层组件显式传 `:teleported="false"`，使其渲染在 Shadow Root 内的原位；`ConversationRoot` 的根容器设 `position: relative` 并给出足够的 `z-index` 层级基准。受影响的至少有：权限模式下拉、斜杠命令菜单、工具卡片内的 tooltip、结果表格的列筛选弹层。这几处都要有真实浏览器（非 jsdom）测试，jsdom 不会暴露这类问题。

`package.json` 关键字段：

```json
{
  "name": "@opendataworks/agent-conversation",
  "version": "0.1.0",
  "license": "GPL-3.0-only",
  "type": "module",
  "exports": {
    ".": { "types": "./types/index.d.ts", "import": "./dist/index.js", "require": "./dist/index.umd.cjs" }
  },
  "files": ["dist", "types", "docs/bff-protocol.md", "README.md"]
}
```

样式内联意味着没有 `./style.css` 导出项——接入方少一步，也少一类出错方式。

### 11.2 分发渠道：公共 npm

**决策：发布到公共 npm 中央仓库（npmjs.com），`--access public`。**

- OpenDataWorks 已是 GPL-3.0 公开源码，发布编译产物不额外泄露任何东西。
- 私有 registry 需新搭并长期运维服务，并把认证凭据注入两个仓库的开发机与 Docker 构建。OntoFoundry 的镜像构建目前是纯 `npm ci`，走私有源会引入构建期凭据管理这一整类新问题。
- 公共 registry 下 OntoFoundry 的 CI、Docker 构建、新同事本地环境都不需要额外配置。

代价与缓解：

- **版本不可撤回。** 因此**首次公共发布必须排在 Widget/SPA dogfood 之后**（计划 T6 之后才是 T7 发布）。先用本地 tarball 在真实外壳上验证接口，再发公共版本。
- **包名需占位。** `@opendataworks` scope 需在 npm 注册归属。
- **GPL-3.0 随包传播。** 对 OntoFoundry（同组织）无影响；不建议外部闭源产品嵌入。若将来需要，须单独为该包重新授权。

### 11.3 版本策略

- 严格 semver。transport 接口、元素属性/方法/事件、BFF 协议的破坏性变化 → major。
- 新增可选 transport 方法、新增事件、新增插槽 → minor。
- 下游用精确版本号依赖（不用 `^`）。
- CI 的 publish 流程必须显式声明两个工作目录：安装/测试/构建在 `dataagent/dataagent-frontend`，pack/publish 在 `dataagent/dataagent-frontend/packages/agent-conversation`。**仓库根没有 `package.json`，不指定 cwd 会直接失败。**

## 12. 测试

### 单元（vitest，`packages/agent-conversation/src/__tests__/`）

- `streamParser` / `reducer`：迁移后与迁移前对同一组事件夹具产出相同投影（用 `views/intelligence/__tests__` 的夹具做黄金对照）。
- 状态转换表：七种 DataAgent 状态各自映射正确；未知状态按 `failed`。
- 活动状态互斥：`waiting_input` / `waiting_permission` 期间 `dataagent-run-change` 报告为活动态。
- 元素注册幂等；**在同一个 registry 中先注册默认标签名、再注册自定义标签名不抛错**（每个标签名必须用各自的 `defineCustomElement` 构造器——同一个构造器 `define` 两次会抛 `NotSupportedError`）；**断开后重新插入不抛错且状态保留**。
- JS 接口时序三种：挂载前赋值 `transportFactory` / `endpointResolver` 生效；挂载后赋值生效；移除后重新插入仍生效。
- `value` 双向：宿主写入后输入框内容变化；用户输入后宿主读到新值。
- 方法代理：四个方法在元素上可直接调用并作用于内部实例。
- 会话地址生命周期（§6.1 三种情形各一组）：
  - 设了 `endpoint` → 挂载即发起 `loadConversation`。
  - 只设 `endpointResolver` → 挂载零请求；首次 `sendMessage()` 调用 resolver 一次并写回 `endpoint`；第二次发送不再调用。
  - 改 `endpoint` → abort 旧流、清空消息、装载新会话，且旧流的后续事件不会污染新会话。
- 插槽投影：Light DOM 中 `slot="composer-actions"` 的真实按钮渲染在输入框 footer 内且可点击。
- `sendMessage()` 语义：无参取 `value`；`value` 空且无参不发请求；`clearDraft: false` 保留草稿。
- `metadata` 透传与恢复：发送时原样进入 transport；终态时原样出现在 `dataagent-complete`；**模拟刷新（重新挂载 + `loadConversation` 返回带 metadata 的 run）后仍能恢复**。
- 可选方法降级：四个可选方法各缺失一次，对应 UI 关闭且无报错；特别是无 `executeSql` 时 SQL 面板只读且不显示执行按钮。
- 流终态：收到 `event: done` 才派发 `dataagent-complete`；流在无 `done` 的情况下 EOF → `stream_interrupted` 并进入退避重订；退避耗尽 → 转轮询。
- **EOF 后状态校验**：直连 transport 在 EOF 后 `getTask` 返回 `running` → 不合成 terminal，抛 `StreamInterrupted` 并续订；返回 `finished`/`error`/`suspended` → 合成对应 terminal。
- 弹层（真实浏览器，非 jsdom）：权限模式下拉、斜杠命令菜单、工具卡片 tooltip、结果表格筛选弹层，四处均渲染在 Shadow Root 内且样式正确。
- 错误映射：BFF 返回 403 + `{message, hint}` 时 `dataagent-error.detail` 携带 `hint`。

### 后端（pytest，`dataagent/dataagent-backend/tests/`）

- 站点未配 `server_side`：现有行为逐条回归（含空 `Origin` 仍放行）。
- 站点 `server_side.enabled=true`：无 key → 403；错误 key → 403；正确 key + 空 `Origin` → 通过；正确 key + 任意 `Origin` → 通过。
- 密钥端点：生成响应含明文；站点读取 DTO 只有 `enabled` / `key_configured`，无 hash、无明文；通用 settings PUT 回写不会覆盖或清除 hash。
- 隔离回归：跨 `external_user_id` 访问 `GET /tasks/{id}`、`/sdk-events/stream`、`/cancel`、`/permission-decision`、`/question-answer` 全部 404。

### 集成

- Widget 回归：现有 smoke 脚本（三形态）通过。**该脚本目前在 `output/playwright/` 且未纳入 Git，产物路径还写死成 `frontend/dist/widget`（实际在 `dataagent/dataagent-frontend/dist/widget`）——本次一并移入受版本控制的前端测试目录并修正路径**，否则它不是可复现的回归门禁。
- 包消费冒烟：最小 React 示例（`packages/agent-conversation/examples/react/`），用 `npm pack` 的本地 tarball 安装后能挂载元素、收发消息、样式正确、宿主未安装 Vue。

## 13. 取舍与风险

| 决策 | 取舍 | 风险与缓解 |
| --- | --- | --- |
| 一个完整元素而非 controller + 可组合组件 | 宿主接入成本最低，定制粒度粗 | 用两个插槽覆盖已知定制需求；更细的拆分等第二个真实诉求 |
| `defineCustomElement` 而非 `createApp().mount()` | 必须用它才能投影插槽 | 方法与可写 `value` 不会自动暴露，需要包装类代理（§6.2.1）；Element Plus 弹层必须关 teleport（§11.1）。两者都已定死规则并配测试 |
| `transportFactory(endpoint)` 而非静态 `transport` | `endpoint` 成为两种模式下唯一的会话键，切换语义只有一条 | 直连场景要自造会话键（`topic://{id}`），略显别扭，但换来 Widget 与宿主应用共用同一套切换路径 |
| 样式内联进 JS | 接入零配置、ESM/UMD 都成立 | 包体积增大；Element Plus 全量样式需按需引入控制 |
| `streamEvents` 吐解析后的对象 + 显式终态 | 线格式差异关在 transport 内 | BFF 必须做格式转换，不能图省事字节透传 |
| Vue 打进包内 | 包体积增大 | 下游是内网业务后台；Shadow DOM 保证不冲突 |
| 不做 `contextRef`，用不透明 `metadata` | SDK 对业务零耦合 | metadata 是不可信客户端输入，BFF 必须校验 |
| 提取而非重写内核 | 快、行为等价 | 用黄金夹具对照 + Widget 回归双重兜底 |
| 沿用 Widget 上下文协议 + 新增 access key | 不需要建 integration 身份体系 | DataAgent 仍须只暴露在可信内网 |
| 公共 npm 发布，但排在 dogfood 之后 | 零基础设施，且避免发出去才发现接口要改 | 版本不可撤回；GPL 传播。见 11.2 |
