# 对话 SDK 渲染性能设计

限定范围：Vue 3 Web Component SDK。依用户要求，设计、计划和基准均放在本包内；不改宿主、后端、部署或公共 API。

问题：MessageList 深度 watch 遍历 records、工具输出和重复引用的 turn/block；Composer 输入、prop 回写、focus 和 mount 重复读布局；工具面板状态 watch 自动展开，Markdown 无跨组件内容缓存。思考块已有默认折叠，应保留并为展开内容加字符上限。

方案：消息数量、最后一条版本/尾块标量驱动滚动，每帧一次并在卸载时取消；已完成快照用 shallowReactive message + markRaw state（反馈保持响应式），运行中的 state 保持 reactive；Composer 合并到 rAF，按文本和宽度去重；工具默认折叠，图表保持现有直接可见语义；工具输出、命令和思考文本预览上限 4000 字符，显式展开全部；折叠命令摘要限制 160 字符以减少排版；完成历史不启动 elapsed 定时器；挂载尺寸上报优先使用 ResizeObserver 提供的数据；共享按原文缓存解析 HTML，限制条目与字符总预算，文件 URL 在缓存之外每次解析，防止跨会话链接污染。

验证：生产构建 + 提供的原始 JSON，真实 Chromium 快照加载至两帧后的耗时、Long Task 最长任务和 timer gap。记录 warmup 后五次样本。新增确定性单元/组件测试；完整 test:sdk、test:sdk-types；生产 Web Component 的展开交互 smoke。无需新增后端联调，因为未改变执行链路。

取舍：不引入虚拟列表、Worker 或新的依赖，避免演示前大改。完整展开仍可能产生长任务，缓存有界并跳过超大内容。完成快照不可原地改 block；内部交互仍只在 active state 上发生。后续再按实测引入虚拟列表和异步解析。

## 2026-10-11 流式渲染与思考展开修正

真实宿主采样显示：20 秒回放约 4000 个事件；Vue inclusive 约 12 秒，其中 marked 约 5 秒。既有 16ms 事件批处理保留。新增 MessageItem 消息边界，传入 message 与标量 renderVersion；MessageList 只保留滚动、列表及按消息 ID 保存的块身份/展开状态，避免流式更新重渲染所有历史消息。保留现有 DOM、part、class、事件与样式。

块身份优先采用块 ID、工具/交互 ID、turnIndex + blockIndex。缺少身份的旧快照仅按同类型块的顺序匹配先前身份；同一消息对象中的无身份块按对象记忆键，插入其他块不会改变键。历史 blocks hydration 保留可用的原始坐标，并标记合成坐标以免误认为真实流坐标。展开集合存在列表层，消息对象被 refreshMessages 替换后仍可恢复；新发送回合从本地消息 ID 换为持久化 ID 时，以同一 assistant taskId 迁移状态；消息/task 删除后清理。空 blocks 快照不生成空 turn，避免第一轮流式坐标与 records 回放错位。无法恢复已删除或重新排序且没有身份信息的旧快照块。

文本块使用独立 MarkdownText 组件，共享 250ms 尾沿采样逻辑；先采样再拆 chart_spec / 解析 Markdown。展开的 ThinkingBlock 将 streaming 状态传给 LimitedText，使用同一节流方法。首次展示、显式展开全部、状态转为非 streaming 立即取最新文本；尾沿计时器确保最后增量无需后续事件也会显示，卸载时取消；文本实际渲染后的内部 rendered 事件通知列表再次按原有自动滚动策略跟随。renderVersion 同时传给读取 raw block 的子组件。

这将每帧的消息内容渲染范围从所有消息缩到被修改的消息，单块解析频率从约 62.5 次/秒缩到约 4 次/秒。仍解析整段采样文本，故并非彻底消除超长文本的二次复杂度；不承诺未经宿主重测的实际阻塞时间。只改 SDK 包，不改宿主、后端、公共接口或部署。

## 2026-10-11 第二轮：真实历史快照与块级隔离

以 bench/fixtures/ontofoundry-long-conversation.json 原始 BFF 响应为固定输入，先构建本轮修改前的未提交源码作为基线；不把旧 fixture 或宿主 profile 与本地新基准混作前后对比。bench 使用 Chromium Timeline（含 RunMicrotasks，减去嵌套布局）分离脚本、布局与等待帧时间；CDP ScriptDuration 仅作诊断，因实测漏计 Vue 微任务，并测首次展示、思考展开/收起；确定性测试计数解析、组件渲染、工具派生和布局访问。保持完整消息与现有 DOM 外观。

展开订阅移到 ThinkingBlock：列表仍持有可跨快照恢复的按键集合，但 MessageItem 不读集合。块自己读取 has(key)；只触发该块的渲染，避免父消息重新创建所有工具 props。工具投影按块保存并逐字段比较复用，输出与工具动作按输入字段缓存，避免同消息其它块增量造成 normalizeOutput/describeToolAction 重算。

MarkdownText 将拆分与解析分别放入 computed，解析依赖仅为采样后的文本，链接重写另依赖当前 resolver。LimitedText 采用相同解析/链接契约。每个已挂载组件保留当前原始 HTML，即使超出共享 LRU 预算也不会因其它 props 更新再解析；思考块保留 v-if 延迟挂载。共享缓存继续有界，不引入无界全局缓存。

滚动监听改为 passive，事件只设置测量标记，在单一 rAF 内先读取高度/位置后按自动跟随状态至多写一次；记录程序化滚动落点并跳过自身产生的 scroll 事件，避免持续读写回环。自动跟随状态不参与 Vue 渲染依赖。用户滚动在待执行自动滚动前可取消跟随，强制滚动仍优先。若滚动与内容增长合并在同一帧，用上一帧已测底部判断原本是否贴底，避免把新增内容误当用户向上滚动。

本轮只改 SDK 包，沿用包内设计/计划目录；不改公共 API、宿主、后端、运行配置，不执行 Git 写操作。不引入虚拟列表或 Worker，先以实测判断剩余瓶颈。

## 2026-10-11 第三轮：完整 Chromium 生命周期

用两个原始 BFF fixture，在同一 Chromium、无 CPU 节流、无宿主的 Web Component 页面测 HEAD、本轮前工作区、本轮后。fake HTTP transport 保留 createHttpTransport、JSON 解析及 run 原始语义。导航至首次消息提交后的绘制机会为首次出现；加载后至少观察 5 秒，统计 Long Task 合计/最长及按组件 mount/update 次数；随后空闲点击思考块测下一帧与更新范围。基准专用生产构建通过 configureApp mixin 计数，发布 dist 不含探针。稳定期以首次出现之后单列，禁止只测同步 mount。

优先查证持续调度来源（快照重载、响应式依赖、rendered 与滚动、定时器），先切断无内容变化的更新链，再处理按块展开与滚动。滚动目标是仅跟随底部、rAF 合并，优先 ResizeObserver 已布局尺寸；不因每个 Markdown rendered 再强制读取布局。保持现有协议、流式采样、块身份和 UI，新增真实 Web Component 回归验证。
