# Widget 场景入口实施计划

对应[设计](../design/2026-10-12-widget-contextual-entry-design.md)。只涉及 Vue Widget 与主 Vue 前端。

1. 保存用户双闪星 SVG，在两端打包；更新 Widget launcher 的图标与浅色底。
2. 扩展实例/global open({ draft })，新增 requestedDraft/draftSignal；WidgetChat 消费并聚焦输入，更新接入手册。
3. Layout provide 同一悬浮实例，复用安装 Promise，脚本失败支持用户手动重试。
4. 新增 AiAssistantTrigger：prompts/openAssistant 可配置的通用组件，提供胶囊、场景轮播、暂停、键盘/移动布局、loading/error/open 状态，制作不进入产品的状态预览。页面配置保持在调用方。
5. 按用户最新要求覆盖全部主应用页面，按模块配置提示；工作流各 Tab 及详情 ID 保持对应场景。依据用户最终反馈由 Layout 统一全部页面的独立页头，主导航恢复 60px，标题 16px/600；移除重复页名，内容区独立滚动，统一主菜单/路由名称为数据开发、工作流。
6. 统一数据开发三栏字体变量及目录行高，验证普通/完整目录、SQL 工具栏、右侧元数据的可读性与截断。
7. 实现三种 panelMode、头部菜单、固定 CSS、几何隔离、实例布局事件与父应用空间预留，补齐手册和保留会话的测试。
8. nvm use 后跑 Widget 和新增主应用聚焦测试、相关页面挂载测试、lint 与两份 build。
9. 在浏览器查看真实页面入口；点击验证只预填、焦点、实例正确、全屏入口保留；验证目标组件四档窄宽与 reduced motion。记录真实后台链路未验证之处。
10. diff/文档核对，清理临时测试服务；按用户后续授权提交到 GitHub，通过 PR 合并 main。

## 验证结果

实现及验证完成，详见[验证记录](../reports/2026-10-12-widget-contextual-entry-verification.md)。临时 HTTP fixture 已停止，本地主应用恢复标准 demo dev 配置，Widget 请求走原有本地 DataAgent 服务。保留开发预览供用户继续查看；用户已授权提交 GitHub 并合并 main，交付通过 PR 执行。
