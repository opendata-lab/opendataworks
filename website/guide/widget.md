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
  defer
></script>
```

Widget 会自动初始化并在页面右下角显示悬浮按钮。

## 配置属性

通过 `<script>` 标签的 `data-*` 属性配置 Widget：

| 属性 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `data-website-id` | 是 | — | 站点标识，用于区分不同接入站点 |
| `data-agent-id` | 是 | — | Agent ID，决定后端使用哪个智能体处理请求 |
| `data-api-base-url` | 是 | — | 后端 API 地址，如 `http://localhost:8900` |
| `data-display-mode` | 否 | `floating` | 显示模式：`floating`（悬浮）/ `inline`（内嵌） |
| `data-position` | 否 | `bottom-right` | 悬浮按钮位置：`bottom-right` / `bottom-left` |
| `data-project-name` | 否 | `智能问数` | 面板标题栏显示名称 |
| `data-project-color` | 否 | `#4A90A4` | 主题色（十六进制） |
| `data-container-id` | 否 | — | 内嵌模式下容器元素 ID（内嵌模式必填） |
| `data-user-id` | 否 | — | 用户标识，用于关联会话和历史记录 |

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

后端需要在环境变量中配置允许的站点列表，确保只有受信任的站点可以接入：

```bash
# 格式：JSON 数组，默认 [] 表示不允许任何外部 widget 请求
WIDGET_ALLOWED_SITES_JSON='[{"website_id":"my-site","allowed_origins":["https://app.example.com"],"project_name":"我的站点","project_color":"#6366f1"}]'
```

配置详情参见 [配置说明](./configuration)。

## 浏览器兼容性

- Chrome 80+
- Firefox 78+
- Safari 14+
- Edge 80+

## 构建部署

Widget 需要单独构建：

```bash
cd frontend
npm run build:widget   # 产出 dist/widget/opendataworks-widget.bundle.js
```

将生成的 JS 文件部署到 Web 服务器即可。Nginx 配置示例：

```nginx
location /widget/ {
    try_files $uri =404;
    add_header Cache-Control "public, max-age=300";
}
```
