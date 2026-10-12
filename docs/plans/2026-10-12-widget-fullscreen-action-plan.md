# Widget 全屏入口实施计划

设计：[Widget 全屏入口设计](../design/2026-10-12-widget-fullscreen-action-design.md)。影响 Vue Widget 接入接口，无后端/执行链路/部署变更。

1. 在 config.js 统一解析 fullscreenUrl，支持脚本属性及对象配置；entry.js 初始化实例上下文。
2. WidgetChat 同步当前 topicId；OpenDataWorksWidget 在既有悬浮标题栏新增有条件显示的全屏入口及回调/事件处理；inline 布局保持现状。
3. 更新 website/guide/widget.md，提供 URL 和宿主路由回调示例，说明优先级、上下文及默认隐藏。
   - 主应用 Layout 回调跳转 /intelligent-query 并收起小窗；IntelligentQueryRemoteEmbed 用既有 selectConversation API 恢复 topicId 参数，监听参数更新。
4. 扩展 config/entry/WidgetChat 的聚焦测试，验证配置、路由、回调、异常、会话变化和多实例隔离。
5. 成功 nvm use 后运行 Widget 测试和 build:widget，以及主应用两处接入聚焦测试和 build。使用 Playwright 对生产 bundle 做纯前端 smoke，验证 URL 导航、回调及会话交接；未进行真实模型/数据库完整执行链路时明确说明。
6. 检查 diff、文档交叉引用及测试结果；记录验证完成情况。不提交或部署。

回退：撤销新增配置、标题栏入口、topicId 同步和文档。没有数据迁移或持久化状态；未配置宿主不受影响。

## 完成与验证

上述实现已完成，未提交或部署。

- Node 20.19.0（成功 nvm use）。Widget 聚焦测试 84/84 通过；新增创建会话上下文断言后 WidgetChat 29/29 通过。
- 主应用 Layout / IntelligentQueryRemoteEmbed 接入测试 5/5 通过，覆盖跳转携带 ID、导航被阻止时保留小窗、URL 参数变化和脚本加载期间参数更新。
- 两个生产构建通过：DataAgent build:widget、主应用 build。主应用构建有既有大 chunk 提示；触及的主应用文件 ESLint 0 errors、3 条既有 warnings。
- Playwright 生产 Widget bundle smoke：原生 URL 点击进入目标页面；回调优先于 URL 且收到实例上下文；inline 不显示入口；选择会话后点击按钮进入 /intelligent-query?topicId=topic-current，悬浮实例关闭，内嵌实例加载同一会话并显示 smoke-ok。
- 浏览器使用本地测试宿主和 HTTP fixture 的配置、话题与消息接口，不使用真实 MySQL/Redis/provider/model。实际主应用整站、真实运行中的后台任务/SSE 恢复与取消链路未做完整端到端验证；不将纯前端 smoke 描述为完整 NL2SQL 联调通过。
- 临时 smoke 宿主与日志位于 gitignored output/playwright/widget-fullscreen/，不进入部署产物。
