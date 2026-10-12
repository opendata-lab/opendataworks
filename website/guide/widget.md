# Widget 嵌入指南

OpenDataWorks 提供嵌入式智能问数 Widget，通过单个 `<script>` 标签即可将智能问数能力集成到任何网页中。Widget 基于 Shadow DOM 实现样式隔离，不会与宿主页面样式冲突。

## 快速开始

在页面 `</body>` 前添加以下脚本标签：

```html
<script
  src="https://your-server/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="https://your-backend"
  data-user-id="current-user-id"
  data-login-url="/login"
  defer
></script>
```

Widget 会自动初始化并在页面右下角显示悬浮按钮。生产接入由父应用提供当前用户 ID；匿名访问默认关闭，公开演示需要同时在站点设置和脚本中开启。

## 配置属性

通过 `<script>` 标签的 `data-*` 属性配置 Widget：

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `data-website-id` | 是 | — | 站点标识，用于区分不同接入站点 |
| `data-agent-id` | 是 | — | Agent ID，决定后端使用哪个智能体处理请求 |
| `data-api-base-url` | 是 | — | 后端 API 地址，如 `http://localhost:8900` |
| `data-display-mode` | 否 | `floating` | 显示模式：`floating`（悬浮）/ `inline`（内嵌） |
| `data-panel-mode` | 否 | `floating` | 浮动实例初始面板布局：`floating` / `docked-left` / `docked-right` |
| `data-position` | 否 | `bottom-right` | 悬浮按钮位置：`bottom-right` / `bottom-left` |
| `data-project-name` | 否 | `智能问数` | 面板标题栏显示名称 |
| `data-project-color` | 否 | `#4A90A4` | 主题色（十六进制） |
| `data-container-id` | 否 | — | 内嵌模式下容器元素 ID（内嵌模式必填） |
| `data-user-id` | 否 | — | 用户标识，用于关联会话和历史记录 |
| `data-login-url` | 否 | — | 未提供用户身份时的登录跳转地址 |
| `data-allow-anonymous` | 否 | `false` | 客户端匿名开关，还需在管理端为该站点开启匿名访问 |
| `data-fullscreen-url` | 否 | — | 全屏按钮打开的完整页面地址，支持宿主相对路由和 HTTP(S) 地址 |

## 显示模式

### 悬浮模式（Floating）

默认模式。Widget 在页面右下角（或左下角）显示悬浮按钮，点击后弹出对话面板。适用于不希望改变页面布局的第三方网站集成。

```html
<script
  src="https://your-server/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="https://your-backend"
  data-display-mode="floating"
  data-position="bottom-right"
  data-project-name="智能助手"
  data-project-color="#6366f1"
  defer
></script>
```

### 内嵌模式（Inline）

Widget 直接渲染到指定 DOM 容器中，成为页面布局的一部分。适用于将智能问数作为页面的固定功能区域。

```html
<!-- 1. 准备容器 -->
<div id="widget-container" style="width: 400px; height: 600px;"></div>

<!-- 2. 加载 Widget -->
<script
  src="https://your-server/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="https://your-backend"
  data-display-mode="inline"
  data-container-id="widget-container"
  data-project-name="智能问数"
  data-project-color="#4A90A4"
  defer
></script>
```

::: tip 容器尺寸
内嵌模式下需要为容器指定明确的宽高，否则 Widget 可能无法正常渲染。
:::

## JavaScript API

Widget 加载后会在 `window` 上暴露 `OpenDataWorksWidget` 控制器对象，支持多实例管理。

### 右上角全屏入口

悬浮模式配置 `fullscreenUrl` 或 `onFullscreen` 后，右上角显示「全屏打开」按钮；未配置时隐藏。内嵌模式不显示此入口。这里的全屏入口用于打开宿主的完整页面，不会调用浏览器 Fullscreen API。

**指定完整页面路由**：

```html
<script
  src="https://your-server/widget/opendataworks-widget.bundle.js"
  data-website-id="my-site"
  data-agent-id="agent-001"
  data-api-base-url="https://your-backend"
  data-user-id="current-user-id"
  data-fullscreen-url="/intelligent-query"
  defer
></script>
```

相对地址以父应用当前页面为基准，默认在当前标签页打开；也可配置 `/#/intelligent-query` 或完整 HTTP(S) URL。通过对象接入时，使用 `fullscreenUrl: '/intelligent-query'`。固定 URL 不自动附加当前会话 ID；需要继续当前会话时使用回调。

**指定父应用方法**：先加载不带接入 `data-*` 属性的 bundle，再由父应用安装实例：

```javascript
import { isNavigationFailure, NavigationFailureType } from 'vue-router'

const widget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site',
  agentId: 'agent-001',
  userId: currentUser.id,
  apiBaseUrl: 'https://your-backend',
  onFullscreen: async ({ topicId }) => {
    const failure = await router.push({
      path: '/intelligent-query',
      query: topicId ? { topicId } : {}
    })
    if (!failure || isNavigationFailure(failure, NavigationFailureType.duplicated)) {
      widget.close()
    }
  }
})

widget.on('fullscreen', (context) => console.log('全屏入口点击', context))
```

`onFullscreen(context)` 可以返回 Promise。上下文为 `{ instanceId, websiteId, agentId, topicId, displayMode }`，没有当前会话时 `topicId` 为 `null`。回调通过对象配置，不从 HTML 属性解析函数。接收路由参数并恢复会话由父应用实现。

示例中的 `router` 和 `currentUser` 使用父应用已有对象，调用 `installWidget` 前应等待 bundle 加载完成。菜单页安装内嵌实例后，可以这样接收会话（容器 `widget-container` 需已存在）：

```javascript
const inlineWidget = window.OpenDataWorksWidget.installWidget({
  websiteId: 'my-site',
  agentId: 'agent-001',
  userId: currentUser.id,
  apiBaseUrl: 'https://your-backend',
  displayMode: 'inline',
  containerId: 'widget-container'
})

const topicId = new URLSearchParams(window.location.search).get('topicId')
if (topicId) inlineWidget.selectConversation(topicId)
```

以上接收示例适用于页面查询参数；SPA 应读取自身路由的 `query.topicId` 并监听变化，hash 路由也通过路由器读取。两个实例需使用相同的站点、用户身份、智能体和后端地址；菜单页卸载时销毁内嵌实例。

回调模式由父应用决定何时调用原悬浮实例的 `close()`。保留 `installWidget` 返回的实例引用，避免菜单页安装后通过全局便捷 API 操作到最后安装的内嵌实例。

同时配置 URL 和回调时，回调优先，Widget 不再执行 URL 跳转。`fullscreen` 是点击观察事件，不覆盖配置行为；回调失败通过 `error` 事件报告 `FULLSCREEN_ACTION_FAILED`。

OpenDataWorks 主应用已接入这一流程：悬浮小窗点击后进入 `/intelligent-query` 菜单页，并收起小窗；有当前会话时携带 `topicId`，菜单页的内嵌实例通过 `selectConversation(topicId)` 继续该会话。其他父应用可按同样方式对接自己的内嵌菜单页。

### 面板控制

```javascript
const widget = window.OpenDataWorksWidget

// 打开对话面板
widget.open()

// 关闭对话面板
widget.close()

// 切换面板开关状态
widget.toggle()

// 查询面板是否打开
widget.isOpen() // → boolean
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
// 发送消息（自动打开面板）
widget.sendMessage('最近30天工作流执行趋势')

// 取消当前正在执行的任务
widget.cancel()

// 打开历史会话列表
widget.openHistory()

// 创建新会话
widget.newConversation()

// 切换到指定会话
widget.selectConversation('topic-id-xxx')

// 删除指定会话
widget.deleteConversation('topic-id-xxx')
```

### 事件监听

```javascript
widget.on('ready', () => console.log('Widget 已就绪'))

widget.on('open', () => console.log('面板已打开'))

widget.on('close', () => console.log('面板已关闭'))

widget.on('message', (data) => console.log('收到消息:', data))

widget.on('error', (error) => console.error('发生错误:', error))

widget.on('conversation:new', (topic) => console.log('新会话:', topic))

widget.on('conversation:select', (topic) => console.log('切换会话:', topic))

widget.on('conversation:delete', (topicId) => console.log('删除会话:', topicId))

widget.on('history:open', () => console.log('历史面板已打开'))
```

### 销毁

```javascript
// 完全移除 Widget，清理所有 DOM 和事件监听
widget.destroy()
```

## 后端配置

Widget 请求会携带以下特殊请求头：

- `X-ODW-Client: widget`
- `X-ODW-Website-Id: <website-id>`
- `X-ODW-User-Id: <user-id>` 或 `X-ODW-Visitor-Id: <visitor-id>`

允许接入的站点白名单在管理后台「智能问数 → Widget 接入」设置页配置，确保只有受信任的站点可以接入：

- **Website ID**：与嵌入脚本的 `data-website-id` 完全一致
- **允许来源（Origin）**：嵌入页面的来源，例如 `https://app.example.com`；填 `*` 放行所有来源（仅建议测试环境）
- **项目名称 / 主题色**：展示在 Widget 上的标题与配色

白名单持久化在 `da_agent_settings`，保存后即时生效、无需重启服务（不再使用环境变量配置）。

## 浏览器兼容性

- Chrome 80+
- Firefox 78+
- Safari 14+
- Edge 80+

## 构建部署

Widget 需要单独构建：

```bash
cd dataagent/dataagent-frontend
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
npm run build:widget   # 产出 dist/widget/opendataworks-widget.bundle.js
```

将 `dist/widget/` 中的 bundle 和 `style.css` 一起部署到 Web 服务器；新增全屏入口后需重新构建并更新产物。Nginx 配置示例：

```nginx
location /widget/ {
    try_files $uri =404;
    add_header Cache-Control "public, max-age=300";
}
```
