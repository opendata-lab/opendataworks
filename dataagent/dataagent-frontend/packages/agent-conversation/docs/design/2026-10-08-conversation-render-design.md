# 对话 SDK 渲染性能设计

限定范围：Vue 3 Web Component SDK。依用户要求，设计、计划和基准均放在本包内；不改宿主、后端、部署或公共 API。

问题：MessageList 深度 watch 遍历 records、工具输出和重复引用的 turn/block；Composer 输入、prop 回写、focus 和 mount 重复读布局；工具面板状态 watch 自动展开，Markdown 无跨组件内容缓存。思考块已有默认折叠，应保留并为展开内容加字符上限。

方案：消息数量、最后一条版本/尾块标量驱动滚动，每帧一次并在卸载时取消；已完成快照用 shallowReactive message + markRaw state（反馈保持响应式），运行中的 state 保持 reactive；Composer 合并到 rAF，按文本和宽度去重；工具默认折叠，图表保持现有直接可见语义；工具输出、命令和思考文本预览上限 4000 字符，显式展开全部；折叠命令摘要限制 160 字符以减少排版；完成历史不启动 elapsed 定时器；挂载尺寸上报优先使用 ResizeObserver 提供的数据；共享按原文缓存解析 HTML，限制条目与字符总预算，文件 URL 在缓存之外每次解析，防止跨会话链接污染。

验证：生产构建 + 提供的原始 JSON，真实 Chromium 快照加载至两帧后的耗时、Long Task 最长任务和 timer gap。记录 warmup 后五次样本。新增确定性单元/组件测试；完整 test:sdk、test:sdk-types；生产 Web Component 的展开交互 smoke。无需新增后端联调，因为未改变执行链路。

取舍：不引入虚拟列表、Worker 或新的依赖，避免演示前大改。完整展开仍可能产生长任务，缓存有界并跳过超大内容。完成快照不可原地改 block；内部交互仍只在 active state 上发生。后续再按实测引入虚拟列表和异步解析。
