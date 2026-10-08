# 对话 SDK 渲染性能计划

设计：[设计文档](../design/2026-10-08-conversation-render-design.md)。

1. 核验宿主 tarball 元数据和导出；从修改前源码构建生产包，保存到临时目录，用 bench/render.mjs 记录基线。
2. 修改 core/message、core/useConversation 和 MessageList，消除历史深遍历；保持流事件、反馈、恢复行为。
3. Composer rAF 合并和去重；工具默认折叠、文本限长；思考展开限长；共享有界 Markdown 缓存。
4. 新增性能行为回归测试，更新原测试等待帧及使用真实变更文本；执行 npm run test:sdk、npm run test:sdk-types。
5. npm run build:sdk 后运行同一生产基准和展开 smoke；写验证报告及复现说明。
6. npm pack 打包到指定 scratchpad，核对 name/version/exports 与旧包完全一致；不提交、不 push，不改 ontograph。回退由宿主继续使用原 tarball 即可。
