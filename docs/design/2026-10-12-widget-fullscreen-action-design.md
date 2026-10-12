# Widget 全屏入口设计

## 当前状态与问题

DataAgent Widget 的悬浮标题栏提供新会话、历史和关闭按钮；inline 模式隐藏标题栏。父应用可通过脚本 data 属性或 installWidget 对象配置接入，但没有打开完整页面的入口。

## 范围与方案

修改 dataagent/dataagent-frontend 的 Vue Widget、接入文档及测试，并将 frontend 主应用的悬浮实例接入既有 /intelligent-query 内嵌菜单页；不修改共享 conversation SDK、Java 后端、Python 执行链路或部署配置。这里的全屏入口由宿主负责打开完整页面，不新增浏览器 Fullscreen API 或面板最大化状态。

- 新增 fullscreenUrl 字符串，脚本形式为 data-fullscreen-url。支持相对路径、hash 路由和 HTTP(S) 地址，以宿主当前页面为 URL 基准；不使用 Widget API 地址。
- 新增 onFullscreen(context) 函数，仅通过 installWidget 对象配置。函数存在时优先于 URL，适合 router.push、打开抽屉、窗口或其他自定义行为。
- 两者均未配置或 URL 无效时隐藏按钮，保留既有默认布局。URL 仅接受 HTTP(S) 协议。
- 按钮仅位于悬浮标题栏右上角、关闭按钮之前。依用户确认，inline 保持现有布局，不显示标题栏或全屏入口，即使传入配置也不展示。
- URL 使用原生链接，默认当前标签页导航，保留浏览器的 Ctrl/Cmd 点击行为；回调使用 button。入口阻止 pointerdown 冒泡，不触发标题栏拖动。
- 点击发送 fullscreen 观察事件并调用配置的回调或由原生链接导航。观察事件不会取消或覆盖配置行为。
- 回调或事件 context 为 { instanceId, websiteId, agentId, topicId, displayMode }，topicId 在无会话时为 null；不自动改写 URL 或传递用户凭据。
- WidgetChat 将真实当前 topicId 同步到实例 state，避免选中/创建/清空会话后传递过期值；不同实例互不共享。
- 同步抛错或 Promise rejection 通过既有 error 事件报告 FULLSCREEN_ACTION_FAILED，保持 Widget 可继续使用。

## 主应用接入

依用户补充，通常目标是父应用已内嵌 Widget 的菜单页。Layout 的 onFullscreen 使用 Vue Router 跳转 /intelligent-query，有当前会话时携带 topicId 查询参数，成功导航后关闭悬浮小窗。IntelligentQueryRemoteEmbed 在安装 inline 实例后通过既有 selectConversation API 选择参数中的会话，并监听查询参数变化；去掉参数时回到新会话。配置加载与接收参数保持既有 Widget 的执行路径，不取消后台任务，不新增后端接口。

## 取舍与兼容性

宿主回调负责 SPA 路由以及会话恢复参数，Widget 不依赖父应用路由框架。未配置入口不改变已有行为；不新增默认路由、全局函数名解析、隐藏的降级跳转或新窗口参数。

## 验证

覆盖脚本/对象配置、URL 协议、按钮默认隐藏、floating 显示与 inline 隐藏、回调优先级、上下文、回调异常和实例隔离，以及主应用路由/会话交接。运行两个前端的聚焦测试与生产构建，通过 Playwright 确认路由点击、回调入口及内嵌会话选择；未执行真实模型/后台任务链路时须明确记录。
