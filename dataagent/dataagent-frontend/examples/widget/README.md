# OpenDataWorks 智能问数 Widget

嵌入式智能问数组件，可通过单个 `<script>` 标签将 OpenDataWorks 的智能问数能力集成到任何网页中。

## 快速开始

在页面底部（`</body>` 前）添加以下脚本标签：

```html
<script
  src="http://localhost:3001/widget/opendataworks-widget.bundle.js"
  data-website-id="your-website-id"
  data-agent-id="your-agent-id"
  data-api-base-url="http://localhost:8900"
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
| `data-position` | 否 | `'bottom-right'` | 悬浮按钮位置：`'bottom-right'` 或 `'bottom-left'`（仅悬浮模式） |
| `data-project-name` | 否 | `'智能问数'` | 显示名称，出现在面板标题栏 |
| `data-project-color` | 否 | `'#4A90A4'` | 主题色（十六进制），影响按钮、标题栏等元素的颜色 |
| `data-container-id` | 否 | — | 内嵌模式下的容器元素 ID（仅内嵌模式必填） |
| `data-user-id` | 否 | — | 可选的用户标识，用于关联会话和历史记录 |

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
  defer
></script>
```

适用场景：
- 将智能问数作为页面的固定功能区域
- 需要与其他页面内容并排显示
- 构建专用的数据分析工作台

## JavaScript API

Widget 加载后会在 `window` 上暴露 `OpenDataWorksWidget` 控制器对象。

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
# 1. 进入前端目录
cd frontend

# 2. 确保使用正确的 Node 版本
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use

# 3. 安装依赖（如果尚未安装）
npm install

# 4. 启动开发服务器
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

**Q: 内嵌模式下 Widget 没有渲染？**
A: 确保 `data-container-id` 指向的 DOM 元素已存在且具有明确的宽高。
