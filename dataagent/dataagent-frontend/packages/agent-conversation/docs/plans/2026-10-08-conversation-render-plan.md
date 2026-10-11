# 对话 SDK 渲染性能计划

设计：[设计文档](../design/2026-10-08-conversation-render-design.md)。

1. 核验宿主 tarball 元数据和导出；从修改前源码构建生产包，保存到临时目录，用 bench/render.mjs 记录基线。
2. 修改 core/message、core/useConversation 和 MessageList，消除历史深遍历；保持流事件、反馈、恢复行为。
3. Composer rAF 合并和去重；工具默认折叠、文本限长；思考展开限长；共享有界 Markdown 缓存。
4. 新增性能行为回归测试，更新原测试等待帧及使用真实变更文本；执行 npm run test:sdk、npm run test:sdk-types。
5. npm run build:sdk 后运行同一生产基准和展开 smoke；写验证报告及复现说明。
6. npm pack 打包到指定 scratchpad，核对 name/version/exports 与旧包完全一致；不提交、不 push，不改 ontograph。回退由宿主继续使用原 tarball 即可。

## 2026-10-11 执行补充

1. 保留未提交事件批处理改动，拆分 MessageItem、MarkdownText；保持列表滚动和 DOM/事件契约。
2. 添加块身份与列表级展开记忆，保留历史快照的真实身份信息；验证坐标重复跨 turn、插入、快照替换，以及新发送回合本地 ID 到持久化 ID 的状态迁移。
3. 实现 250ms 文本采样、结束立即刷新、显式展开立即刷新和计时器清理；验证尾沿渲染后的自动滚动。
4. 新增消息渲染隔离、1000 delta 解析上限、ThinkingBlock 流中与终态快照展开保留测试，保留 500 事件批处理测试。
5. nvm use 后执行完整 test:sdk、build:sdk；记录确定性计数证据与未重测的真实宿主性能边界，不进行 Git 写操作。

## 2026-10-11 第二轮执行

1. 固定真实 fixture，扩展 bench 的脚本时间和展开/收起指标；保存本轮修改前构建至临时目录，运行五次基线。
2. 将展开集合订阅下放 ThinkingBlock；工具投影按块/字段复用；MarkdownText/LimitedText 分离解析与文件链接解析，保留实例当前大文本解析结果。
3. passive scroll 事件不读布局；合并测量与自动滚动进一个 rAF，忽略程序化滚动反馈，验证用户取消和强制滚动。
4. 新增真实 fixture 渲染计数、解析次数、工具重算、单块展开隔离、大文本缓存及滚动顺序回归；执行完整 test:sdk 与类型检查。
5. 执行 build:sdk、同一 Chromium 基准与真实 Web Component 交互验证，保存本轮前后数字、环境和剩余瓶颈到包内报告。无需后端 NL2SQL smoke，本轮只触及呈现。

## 2026-10-11 第三轮执行

1. 更新 Chromium bench 覆盖导航→首次出现→5 秒稳定→点击，以及按组件更新次数；只读提取 HEAD 到包内 ignored scratch，构建 HEAD 与本轮前工作区并保存两个 fixture 数字。
2. 依据真实浏览器组件计数与必要时 profile 查出 mount 后反复 patch 的调度源，修正；稳定快照首次展示后零组件更新，思考展开只更新该块。
3. 基于 ResizeObserver 与跟随状态合并自动滚动，回归用户退出跟随、程序化反馈、流式增长与卸载清理。
4. 完整 SDK/类型测试、build:sdk、同方法两个 fixture 后测，记录根因、完整指标、环境及限制。所有改动仅本包，不执行 Git 写操作。
