# Widget 场景入口、固定面板与统一页头验证

对应[设计](../design/2026-10-12-widget-contextual-entry-design.md)、[计划](../plans/2026-10-12-widget-contextual-entry-plan.md)。

## 最终行为

- 用户提供的双闪星 SVG 用于启动按钮与 AI 助手入口，两端资产字节一致。
- 统一页头覆盖主应用全部页面，标题 16px/600，名称跟随菜单/路由；按模块配置轮播提示，详情携带工作流 ID。
- 点击打开自己的同一 Widget 实例，只预填并聚焦；不自动发送。轮播支持暂停、键盘、失败重试与 reduced motion。
- 悬浮/左固定/右固定切换复用同一聊天组件，保留输入；父应用监听 panel-layout 预留空间，关闭/销毁释放，窄屏覆盖呈现。
- 全屏入口保留当前 topicId，进入智能问数内嵌菜单页。
- 数据开发主文字 13px、辅助信息 12px；普通目录行 28px、完整目录行 44px；查询、结果和右侧各 Tab 同用字号变量。

## 自动验证

Node 20.19.0，每次 frontend 命令前均成功 nvm use。

- Widget 全部 88 项通过；补充窄视口尺寸边界后 entry/geometry 44 项通过（包含新增 1 项，共覆盖 89 项）。
- 主应用组件、Layout、内嵌会话交接、数据开发/详情面板、目录宽度、设置、控制台和工作流挂载测试 47 项通过；扩展全部页头场景后 Layout 11 项通过（原 7 项加 4 项）。脚本加载失败重试测试另有 1 项通过。
- Widget production build、主应用 production/demo build、VitePress build 成功。
- 针对改动入口/路由的 ESLint 无错误，保留既有 unused catch/console 等警告；git diff --check 成功。
- 两份接入手册 JS/Vue 示例语法、相对链接检查通过。

## 浏览器验证

Chrome 真实浏览器；主应用 demo 数据，Widget 使用临时模拟 HTTP，未调用真实模型。

- 数据开发、工作流各 Tab 以及控制台/建模/血缘入口文案正确；菜单与页头名称一致。
- 点击仅预填、输入框聚焦；跨页面只有一个浮动实例。
- 1384px 视口右固定面板宽 440px，父应用宽 944px；左固定起点 0、父应用起点 440px；关闭恢复主应用宽度。
- 模式切换输入保留；375px 视口固定面板宽 375px、主应用不留空位。
- 通过历史会话选中模拟 topic-current，全屏进入 /intelligent-query?topicId=topic-current 并显示持久化消息。
- 入口八状态预览在 320/375/414/768px 无横向滚动。reduced motion/隐藏页暂停通过组件测试。
- 数据开发实际测量目录名 13px、辅助信息 12px、完整模式行高 44px；打开字段页确认字号与工作区一致。

截图与可复现临时预览在 gitignored 的 output/widget-contextual/，主图 data-development-final.jpg。

临时 fixture 已停止；主应用恢复标准 dev:demo 配置（主页面展示 demo 数据，Widget 请求原有 localhost:8900）。既有 3001 DataAgent 前端和 8900 后端未修改或重启。未运行真实模型 NL2SQL、任务流/取消/数据库持久化的端到端测试，不声称这些链路完全验证。本记录描述本地验证，GitHub 提交与合并按用户后续授权单独执行。
