# OpenDataWorks 智能问数 Widget

嵌入式智能问数组件，可通过单个 `<script>` 标签将 OpenDataWorks 的智能问数能力集成到任何网页中。

本文档是 Widget 接入参数、身份策略和运行方式的权威说明。`docs/design/` 与 `docs/plans/` 下的文件仅记录内部设计和实施过程。

## 接入前置配置

嵌入脚本生效前，管理员需要先在 DataAgent 的 `/widget-access` 页面新增接入站点：

1. `Website ID`：必须与嵌入脚本的 `data-website-id` 完全一致。
2. `允许来源（Origin）`：填写宿主页面来源，例如 `https://portal.example.com`；`*` 仅建议用于本地演示。
3. `匿名访问`：默认关闭。生产站点建议保持关闭，由宿主系统提供当前用户身份。

未加入站点白名单或 Origin 不匹配时，后端返回 `403`，Widget 不允许访问智能问数接口。

## 快速开始

在页面底部（`</body>` 前）添加以下脚本标签：

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="your-website-id"
  data-agent-id="your-agent-id"
  data-api-base-url="http://localhost:8900"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

Widget 会自动初始化并在页面右下角显示一个悬浮按钮。

## 配置属性

通过 `<script>` 标签的 `data-*` 属性进行配置：

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `data-website-id` | 是 | — | 站点标识符，用于区分不同的接入站点 |
| `data-agent-id` | 是 | — | 使用的 Agent ID，决定后端使用哪个智能体处理请求 |
| `data-api-base-url` | 是 | — | 后端 API 基础地址，如 `http://localhost:8900` |
| `data-display-mode` | 否 | `'floating'` | 显示模式：`'floating'`（悬浮模式）或 `'inline'`（内嵌模式） |
| `data-panel-mode` | 否 | `floating` | 浮动实例初始面板布局：`floating` / `docked-left` / `docked-right` |
| `data-position` | 否 | `'bottom-right'` | 悬浮按钮位置：`'bottom-right'` 或 `'bottom-left'`（仅悬浮模式） |
| `data-project-name` | 否 | `'智能问数'` | 显示名称，出现在面板标题栏 |
| `data-project-color` | 否 | `'#4A90A4'` | 主题色（十六进制），影响标题栏等元素的颜色，启动图标使用固定渐变 |
| `data-container-id` | 否 | — | 内嵌模式下的容器元素 ID（仅内嵌模式必填） |
| `data-user-id` | 否 | — | 当前用户标识；未开启匿名访问时必须由宿主系统提供 |
| `data-login-url` | 否 | — | 未提供用户身份时“前往登录”按钮的跳转地址 |
| `data-allow-anonymous` | 否 | `'false'` | 是否允许客户端生成访客 ID；还需在管理端为该站点开启匿名访问 |
| `data-fullscreen-url` | 否 | — | 悬浮窗口右上角全屏入口的目标地址，通常指向父应用的内嵌 Widget 菜单页 |

## 身份与匿名访问

匿名访问采用前后端双重开关，任何一侧未开启都不会放行匿名用户：

| 用户 ID | 嵌入参数 `allowAnonymous` | 管理端允许匿名 | 结果 |
|---------|--------------------------|----------------|------|
| 已提供 | 任意 | 任意 | 按该外部用户加载会话和使用 Widget |
| 未提供 | `false` 或未配置 | 任意 | 展示“请先登录”，不生成访客 ID |
| 未提供 | `true` | 关闭 | 后端返回 `401/WIDGET_LOGIN_REQUIRED`，切换到登录状态 |
| 未提供 | `true` | 开启 | 生成 `visitor_` 标识，按匿名访客使用 Widget |

匿名访问默认关闭。未登录状态保留 Widget 入口、标题栏和关闭按钮，但隐藏历史会话、推荐问题和输入区，不加载会话或业务数据：

- 配置 `data-login-url`：显示“前往登录”。
- 未配置 `data-login-url`：显示“刷新页面”。
- `sendMessage()`、`ask()`、新建会话和历史会话等公开 API 同样会被拦截。
- 后端拒绝匿名身份时返回 HTTP `401`，错误码为 `WIDGET_LOGIN_REQUIRED`。

宿主系统应在自身登录校验完成后注入 `data-user-id`，不要直接采用用户可修改的 URL 参数或表单值。当前 `X-ODW-User-Id` 是外部身份传递契约，不等同于密码或签名令牌。

公开演示场景需要在管理端站点设置和嵌入脚本中同时开启匿名访问：

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="public-demo"
  data-agent-id="demo"
  data-api-base-url="http://localhost:8900"
  data-allow-anonymous="true"
  defer
></script>
```

## 显示模式

### 悬浮模式（Floating）

默认模式。Widget 在页面右下角（或左下角）显示一个悬浮触发按钮，点击后弹出对话面板。

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="http://localhost:8900"
  data-display-mode="floating"
  data-position="bottom-right"
  data-project-name="智能助手"
  data-project-color="#6366f1"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

适用场景：
- 第三方网站集成
- 不希望改变现有页面布局
- 需要全局可访问的对话入口

### 内嵌模式（Inline）

Widget 直接渲染到指定的 DOM 容器中，成为页面布局的一部分。

```html
<!-- 1. 准备容器 -->
<div id="widget-container" style="width: 400px; height: 600px;"></div>

<!-- 2. 加载 Widget -->
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="http://localhost:8900"
  data-display-mode="inline"
  data-container-id="widget-container"
  data-project-name="智能问数"
  data-project-color="#4A90A4"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

适用场景：
- 将智能问数作为页面的固定功能区域
- 需要与其他页面内容并排显示
- 构建专用的数据分析工作台

## JavaScript API

Widget 加载后会在 `window` 上暴露 `OpenDataWorksWidget` 控制器对象。

### 编程式安装

不使用脚本 `data-*` 属性时，可以通过配置对象创建实例。属性名采用 camelCase：

```javascript
const widget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site',
  agentId: 'agent-001',
  apiBaseUrl: 'http://localhost:8900',
  userId: currentUser.id,
  loginUrl: '/login',
  allowAnonymous: false,
  displayMode: 'floating',
  projectName: '智能问数',
  projectColor: '#4A90A4'
});
```

编程式安装和 `data-*` 安装遵循相同的身份与匿名访问策略。

### 悬浮小窗进入内嵌菜单页

典型流程：**悬浮小窗点击「全屏打开」→ 父应用的智能问数菜单页 → 内嵌 Widget 继续当前会话**。按钮只在悬浮模式显示；内嵌模式不增加标题栏或全屏按钮。此入口打开完整页面，不会调用浏览器 Fullscreen API。

| 对象配置 | 脚本属性 | 行为 |
|----------|----------|------|
| `fullscreenUrl` | `data-fullscreen-url` | 通过原生链接在当前标签页打开目标地址 |
| `onFullscreen(context)` | 无 | 点击时调用父应用方法，可返回 Promise，适合 SPA 路由或自定义页面打开方式 |

未配置有效 URL 或回调时隐藏按钮；同时配置两者时，回调优先，不再执行 URL 跳转。URL 支持相对路径、hash 路由和 HTTP(S) 地址，以父应用当前页面为基准，与 `apiBaseUrl` 无关。不从脚本属性或字符串解析 JavaScript 函数。

**方式一：指定菜单页地址**

```html
<script
  src="https://dataagent.example.com/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="https://dataagent-api.example.com"
  data-user-id="current-user-id"
  data-fullscreen-url="/intelligent-query"
  defer
></script>
```

Hash 路由可配置 `/#/intelligent-query`。通过对象安装时使用 `fullscreenUrl: '/intelligent-query'`。固定 URL 不会自动附加当前 `topicId`；需要继续同一会话时使用下面的回调方式。

**方式二：父应用控制路由并传递当前会话**

先加载不带接入 `data-*` 属性的 bundle，等待脚本加载完成，再调用 `installWidget`；同一实例只选择自动安装或编程式安装中的一种方式。

```html
<script src="https://dataagent.example.com/widget/opendataworks-widget.bundle.js"></script>
```

以下 Vue Router 示例中的 `router` 和 `currentUser` 使用父应用已有对象：

```javascript
import { isNavigationFailure, NavigationFailureType } from 'vue-router';

const floatingWidget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site',
  agentId: 'agent-001',
  apiBaseUrl: 'https://dataagent-api.example.com',
  userId: currentUser.id,
  displayMode: 'floating',
  onFullscreen: async ({ topicId }) => {
    const failure = await router.push({
      path: '/intelligent-query',
      query: topicId ? { topicId } : {}
    });
    if (!failure || isNavigationFailure(failure, NavigationFailureType.duplicated)) {
      floatingWidget.close();
    }
  }
});
```

配置回调后，Widget 不会自动收起小窗，由父应用在导航成功后调用该实例的 `close()`。保留 `installWidget` 返回的实例引用：目标菜单页安装内嵌实例后，全局便捷 API 会指向最后安装的实例。

**菜单页接收会话**

目标页面先准备内嵌容器，安装 inline 实例后读取路由参数并调用 `selectConversation(topicId)`。Vue 页面可按以下方式处理安装、参数变化和卸载，前提是 bundle 已加载：

```vue
<template>
  <div id="inline-chat" style="width: 100%; height: 100%;"></div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, watch } from 'vue';
import { useRoute } from 'vue-router';

const props = defineProps({ currentUser: { type: Object, required: true } });
const route = useRoute();
let inlineWidget;

onMounted(() => {
  inlineWidget = window.OpenDataWorksWidget.installWidget({
    websiteId: 'my-site',
    agentId: 'agent-001',
    apiBaseUrl: 'https://dataagent-api.example.com',
    userId: props.currentUser.id,
    displayMode: 'inline',
    containerId: 'inline-chat'
  });
  if (typeof route.query.topicId === 'string' && route.query.topicId) {
    inlineWidget.selectConversation(route.query.topicId);
  }
});

watch(() => route.query.topicId, (topicId) => {
  if (!inlineWidget) return;
  if (typeof topicId === 'string' && topicId) inlineWidget.selectConversation(topicId);
  else inlineWidget.newConversation();
});

onBeforeUnmount(() => inlineWidget?.destroy());
</script>
```

菜单页的 `currentUser` prop 由父组件的登录状态提供；容器的父级需要提供可用高度。两个实例应使用相同的 `websiteId`、用户身份、`agentId` 和后端地址，才能访问同一份会话。匿名接入则保持同一访客身份。

回调及 `fullscreen` 观察事件的上下文：

| 字段 | 说明 |
|------|------|
| `instanceId` | 触发入口的 Widget 实例 ID |
| `websiteId` | 当前接入站点标识 |
| `agentId` | 当前配置的智能体 ID |
| `topicId` | 当前会话 ID；尚未创建或选中会话时为 `null` |
| `displayMode` | 当前显示模式，此按钮触发时为 `floating` |

`fullscreen` 是点击观察事件，不替代配置的 URL 或回调。回调同步抛错或 Promise 拒绝时，Widget 通过 `error` 事件报告 `{ code: 'FULLSCREEN_ACTION_FAILED', message }`，不会改为执行 URL 跳转。

OpenDataWorks 主应用已采用此流程：小窗进入 `/intelligent-query?topicId=...`，导航成功后收起小窗，内嵌菜单页选中该会话。

### 面板控制

```javascript
const widget = window.OpenDataWorksWidget;

// 打开对话面板
widget.open();

// 关闭对话面板
widget.close();

// 切换面板开关状态
widget.toggle();

// 查询面板是否打开
const isOpen = widget.isOpen(); // → boolean
```

### 场景提示入口与草稿

`open({ draft })` 打开面板并预填输入框、聚焦输入，不自动发送；用户确认后点击发送或按 Enter。再次调用会替换未发送的输入，`draft: ''` 清空输入；不带参数保留原有打开行为。`sendMessage(text)` 才会自动发送。

```javascript
// 使用自己安装的实例，避免全局 API 指向其他页面的最后一个实例
const widget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site', userId: 'current-user-id', agentId: 'agent-001',
  apiBaseUrl: 'https://your-backend', displayMode: 'floating'
})
document.getElementById('page-ai-button').addEventListener('click', () => {
  widget.open({ draft: '帮我分析工作流执行失败原因' })
})
```

主应用提供可复用 Vue 组件 `frontend/src/components/AiAssistantTrigger.vue`，可以放在页面头部、工具栏等位置。`prompts` 由嵌入位置配置；默认打开 Layout 提供的同一 Widget。组件未提供于 Widget 的全局 JS bundle；其他宿主可通过自己的按钮调用上述公开 API，Vue 宿主也可复用该源码并传入 `openAssistant`。

```vue
<template>
  <AiAssistantTrigger :prompts="prompts" :open-assistant="openAssistant" />
</template>
<script setup>
import AiAssistantTrigger from '@/components/AiAssistantTrigger.vue'
const props = defineProps({ widget: { type: Object, required: true } })
const prompts = ['帮我检查任务 SQL', '帮我分析任务之间的依赖']
const openAssistant = (draft) => props.widget.open({ draft })
</script>
```

| 组件参数 | 说明 |
| --- | --- |
| `prompts: string[]` | 当前场景的轮播提示，空数组只显示 AI助手 |
| `openAssistant(draft)` | 自定义打开方法，可返回 Promise；未传时使用 Layout 注入实例 |
| `expanded: boolean` | 自定义打开方法时可传入面板开关状态 |
| `disabled: boolean` | 禁用入口 |
| `opened / error` | 打开成功（含 `{ draft }`）或失败的组件事件 |

提示每 5 秒轮换，鼠标悬停、键盘聚焦、页面隐藏或系统减少动态效果时暂停。加载中防止重复点击，失败可手动重试。OpenDataWorks 主应用全部页面统一使用独立页头，在标题旁提供 AI 助手入口。控制台、数据开发、工作流、建模、血缘、质量、集成、设置等模块分别配置提示；工作流列表提示随标签变化，详情携带工作流 ID。场景文案保持在调用方。

### 悬浮 / 左固定 / 右固定

浮动实例的头部「面板布局」菜单支持三种呈现方式。`displayMode` 仍为 `floating`；`inline` 由宿主容器决定布局，不显示该菜单。

| `panelMode` | 呈现 |
| --- | --- |
| `floating`（默认） | 可拖动、缩放的悬浮面板 |
| `docked-left` | 左侧固定面板 |
| `docked-right` | 右侧固定面板 |

安装配置可指定 `panelMode`，脚本属性为 `data-panel-mode`。运行时使用 `setPanelMode(mode)`，有效浮动实例返回 `true`，非法值或 inline 实例返回 `false`；`getPanelMode()` 查询当前模式。切换模式保留当前会话、输入和流连接；关闭后保留选中模式，下次打开继续沿用。回到悬浮模式恢复原位置/尺寸；模式本身不跨刷新持久化。

固定面板贴视口边缘，高度占满视口，宽度 440px（不超过视口）。Widget 不修改宿主 DOM，父应用需要监听自己的实例的 `panel-layout` 事件，让主页面给面板留空间：

```javascript
const app = document.getElementById('app') // 宿主的整页根容器
const applyPanelLayout = ({ side, reservedWidth }) => {
  app.style.width = reservedWidth ? `calc(100% - ${reservedWidth}px)` : ''
  app.style.marginLeft = side === 'left' && reservedWidth ? `${reservedWidth}px` : ''
  app.style.marginRight = side === 'right' && reservedWidth ? `${reservedWidth}px` : ''
}
const removeLayoutListener = widget.on('panel-layout', applyPanelLayout)
applyPanelLayout(widget.getPanelLayout()) // 安装后同步初始布局
widget.setPanelMode('docked-right')
// 页面卸载：widget.destroy() 先发出占位归零的事件，再清理监听
// removeLayoutListener()
```

`getPanelLayout()` 与事件返回 `{ mode, side, width, reservedWidth, isOpen }`：`side` 为 `left/right/null`，`width` 为打开的固定面板宽度；关闭、销毁、悬浮或 inline 时占位为 0。窗口宽度小于 1024px 时 `reservedWidth=0`，固定面板改为覆盖呈现，避免主页面被挤窄；窗口 resize 会重新发出布局事件。OpenDataWorks 主应用已经完成空间预留。

### 消息与会话

```javascript
// 发送一条消息（会自动打开面板）
widget.sendMessage('最近30天工作流执行趋势');

// 取消当前正在执行的任务
widget.cancel();

// 打开历史会话列表
widget.openHistory();

// 创建新会话
widget.newConversation();

// 切换到指定会话
widget.selectConversation('topic-id-xxx');

// 删除指定会话
widget.deleteConversation('topic-id-xxx');
```

### 事件监听

```javascript
// 监听事件
widget.on('open', () => {
  console.log('面板已打开');
});

widget.on('close', () => {
  console.log('面板已关闭');
});

widget.on('message', (data) => {
  console.log('收到消息:', data);
});

widget.on('error', (error) => {
  console.error('发生错误:', error);
});

widget.on('fullscreen', ({ instanceId, topicId }) => {
  console.log('全屏入口点击:', instanceId, topicId);
});

widget.on('login:required', () => {
  console.log('Widget 等待宿主系统提供用户身份');
});

widget.on('ready', () => {
  console.log('Widget 已就绪');
});
```

### 销毁

```javascript
// 完全移除 Widget，清理所有 DOM 和事件监听
widget.destroy();
```

## 运行示例

### 前提条件

1. 启动 OpenDataWorks 后端服务
2. 确保已配置可用的 `agent-id`
3. 启动前端开发服务器（Widget bundle 由前端服务提供）

### 启动步骤

```bash
# 1. 进入 DataAgent 前端目录
cd dataagent/dataagent-frontend

# 2. 确保使用正确的 Node 版本
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use

# 3. 安装依赖（如果尚未安装）
npm install

# 4. 构建 Widget bundle（开发服务器的 /widget/ 路径读取此产物）
npm run build:widget

# 5. 启动开发服务器
npm run dev
```

### 查看示例

开发服务器启动后，直接在浏览器中打开示例文件：

| 示例 | 文件 | 说明 |
|------|------|------|
| 悬浮模式 | [`floating.html`](./floating.html) | 在模拟的数据看板页面中嵌入悬浮 Widget |
| 内嵌模式 | [`inline.html`](./inline.html) | 将 Widget 作为页面布局的一部分 |
| JS API | [`api-demo.html`](./api-demo.html) | 交互式演示所有 API 方法和事件监听 |
| Ask AI 搜索模式 | [`docs-search.html`](./docs-search.html) | 模拟主流文档站的顶部搜索框/底部浮动输入框 Ask AI 唤起模式 |

> **注意**：示例页面中的 `data-agent-id` 设置为 `"demo"`，`data-api-base-url` 为空。
> 要实际发送消息和接收回复，需要：
> 1. 将 `data-api-base-url` 修改为实际的后端地址（如 `http://localhost:8900`）
> 2. 将 `data-agent-id` 修改为后端已配置的有效 Agent ID
> 3. 确保后端服务和相关数据库正常运行

## 浏览器兼容性

- Chrome 80+
- Firefox 78+
- Safari 14+
- Edge 80+

## 常见问题

**Q: Widget 加载后没有出现悬浮按钮？**
A: 检查浏览器控制台是否有加载错误，确认 script src 地址可访问。

**Q: 点击发送消息没有响应？**
A: 确认 `data-api-base-url` 和 `data-agent-id` 已正确配置，且后端服务正在运行。

**Q: Widget 只显示“请先登录”？**
A: 当前站点未提供 `data-user-id`。生产接入应在宿主登录完成后注入用户 ID；公开演示则需要同时开启管理端“匿名访问”和 `data-allow-anonymous="true"`。

**Q: 已配置 `data-allow-anonymous="true"`，仍然要求登录？**
A: 检查 `/widget-access` 中对应 `Website ID` 的匿名访问开关。客户端参数不能绕过后端站点策略。

**Q: 返回 `403 Widget site/origin is not allowed`？**
A: 检查管理端站点白名单中的 `Website ID` 和允许来源，确保与实际脚本参数及宿主页面 Origin 一致。

**Q: 内嵌模式下 Widget 没有渲染？**
A: 确保 `data-container-id` 指向的 DOM 元素已存在且具有明确的宽高。

**Q: 右上角没有全屏按钮？**
A: 按钮仅在悬浮窗口中显示，需要配置有效的 `fullscreenUrl` / `data-fullscreen-url` 或 `onFullscreen`。内嵌模式不显示；更新功能后需重新构建并发布 Widget bundle。

**Q: 跳到内嵌菜单页后没有继续原会话？**
A: 固定 URL 不自动携带会话 ID。使用回调将 `topicId` 放入路由，目标页面安装 inline 实例后调用 `selectConversation(topicId)`，并保证两端使用同一站点、身份、智能体和后端地址。
