# DataAgent 对话 SDK 渲染验证报告

分支：`perf/agent-conversation-render`。基线源码：`cfb286f2`。限定 packages/agent-conversation；没有修改 ontograph，没有 commit/push。

## 根因与改动

- `src/ui/MessageList.vue` 原 283 行深度 watch 会遍历整条消息的 records、blocks、turns 和工具输出，且每次流式事件都重复。现 289 行仅读取消息数组引用、数量、最后消息版本及尾块标量；271 行 rAF 合并滚动并在执行帧重新检查用户是否向上滚动。
- `src/ui/Composer.vue` 原 341 行 autoResize 在 input、prop 回写、focus 和 mount 中重复执行写 height=auto / 读 scrollHeight。现 358 行每帧一次、文本与宽度去重，仍支持缩小和 160px 上限；173 行尺寸上报优先使用 ResizeObserver 的 borderBoxSize，消除挂载强制布局。
- `src/core/message.js` 原 33 行 marked.parse 无缓存；原 245 行整条助手消息 deep reactive。现 30 行 LRU 128 条、512000 字符预算（原文加 HTML），大条目不保留；文件 URL 在缓存外按当前会话重写。273 行完成 state 用 markRaw、消息顶层用 shallowReactive 保留反馈；运行 state 仍 reactive，useConversation.load 会将续跑快照恢复为 reactive。
- `src/ui/ToolOutput.vue` 原 675 行自动展开；工具输出原 245 行有第二套无缓存 Markdown parser。现统一 LimitedText、默认折叠和延迟 Markdown，SQL 面板也折叠；图表保持现有直接可见行为。626 行仅运行中的 shell 有 elapsed timer，完成历史不再每秒唤醒所有卡片。
- `src/ui/ThinkingBlock.vue` 原本已默认折叠且 v-if 延迟解析，不能将其描述成原来始终解析；新增展开内容上限。`src/ui/LimitedText.vue:23` 限制 4000 UTF-16 字符，Markdown 工具预览最多 5 行，提供“展开全部”和“收起”；复制工具输出仍使用完整原文。
- 原快照的一个 Bash 输入长 8264 字符，CSS ellipsis 仍需排版全部命令。ToolOutput 的折叠摘要限制 160 字符，完整命令在展开面板里也按 4000 字符预览，可展开全部。

## 基准方法

`bench/render.mjs` 使用真实 Chromium 和生产 dist，完整原始快照，不经过 BFF 裁剪，不用 jsdom。网络获取和 SDK 导入在计时前；计时包含 JSON.parse、Web Component 挂载、normalize/replay、Vue 更新、布局与两次 rAF。readyMs 单独记录 ready 事件兑现时的阶段耗时。渲染完成定义为 ready 后两个帧回调（DOM 已提交并有一次 layout/paint 机会），因此总时间有约一帧的调度波动，不等于纯 CPU 时间。

原始文件：447843 bytes，148 records，2 条消息（其中 1 条助手），22 tools、20 thinking。SHA-256：`a50ca9ce3d044e23988a84ce17989efdff3faf2b77c413b0f86e733eacb1acc6`。Node 20.19.0，Playwright 1.63.0，Chromium 153.0.8010.12，macOS 本机，1100×900 viewport、1000×800 容器、无 CPU 节流。1 次 warmup + 5 个全新页面；每个页面数据缓存为空，按源码优化前、最终构建、宿主旧 tarball 顺序运行，没有并行构建或测试。

LongestTask 来自浏览器 Long Task API 的同步主线程任务耗时（含 JS/layout，阈值 ≥50ms），只统计与加载窗口重叠的任务。0 代表未观察到 ≥50ms 任务，不代表绝对零阻塞。Timer gap 是 setInterval(0) 相邻回调最大间隔，包含线程阻塞及调度等待，用于交叉核对。不是根据 Vitest 总耗时估算。

| 指标（ms） | 优化前源码 | 优化后 | 宿主旧 tarball |
|---|---:|---:|---:|
| 快照至两帧渲染完成，中位数 | 107.1 | 107.5 | 107.9 |
| 最长同步任务，中位数 | 101.0 | 91.0 | 99.0 |
| 五次中的最长同步任务 | 101.0 | 92.0 | 100.0 |
| 最大 timer gap，中位数 | 101.1 | 91.6 | 99.1 |

完整逐次数字保存在源码仓库的 `bench/results/before.json`、`after.json`、`host.json`（开发基准不随发布包分发）。ready 阶段中位数 56.1 → 38.7ms。同步任务中位数降低约 9.9%；两帧总耗时基本持平，不能声称整体加载大幅提速。独立 SDK 中未复现宿主 3～9s 卡顿（旧 tarball 同样约 108ms）；宿主复杂 DOM、重复刷新或布局反馈可能放大开销，但本轮没有验证这些假设。须由宿主回填后在原本体构建页再采 CPU profile，不能将本报告当作宿主 P0 卡顿已完全解决的证据。

## 重复执行

在 `dataagent/dataagent-frontend` 目录：

```sh
export NVM_DIR="$HOME/.nvm"
. "$NVM_DIR/nvm.sh"
nvm use
npm run build:sdk
# 若已有 playwright，令 PLAYWRIGHT_MODULE 指向其 index.mjs；也可以装到临时目录。
bench_tools_dir="$(mktemp -d)"
npm install --prefix "$bench_tools_dir" --no-save playwright@1.63.0
"$bench_tools_dir/node_modules/.bin/playwright" install chromium
export PLAYWRIGHT_MODULE="$bench_tools_dir/node_modules/playwright/index.mjs"
node packages/agent-conversation/bench/render.mjs --snapshot /path/to/conv-live-run-raw.json
# 基线：将旧 tarball 解压至临时目录，再指定 --dist /tmp/old/package/dist。
# 可选 CPU profile：--profile /tmp/render.cpuprofile。
```

本次原始输入保留在用户指定 scratchpad，没有把会话内容复制进仓库。修改前源码的生产 dist 保留在 `/private/tmp/odw-conversation-render-baseline/dist`；宿主旧包解压到同目录 `host/package`。

## 测试与边界

- `npm run test:sdk`：17 个测试文件，196/196 通过；新增 10 个性能行为测试。原有下载测试仍输出 jsdom 不支持 navigation 的提示，测试通过。
- `npm run test:sdk-types`：React 18 和 React 19 均通过。
- `npm run build:sdk`：成功，621 modules；SDK 未新增运行时依赖。
- 生产 Chromium smoke：每次确认消息数和最终答案、工具展开、思考展开；无 pageerror。两套已有投影输入、反馈、权限、问题、附件、恢复、取消和流式滚动回归全绿。
- 此次是 SDK 前端内部优化，未修改 DataAgent 后端或执行协议，未启动后端/模型全链路，也未在 ontograph 页面回填新包。

## 后续建议

优先在宿主原页面核对重复 load/属性更新及 composer-resize 布局反馈；增加宿主性能预算和固定 CPU 节流的 CI benchmark。会话历史继续增长时引入按消息/轮次虚拟列表；用户展开巨大内容时采用分段加载或 Worker Markdown 解析；缓存仍保持有界。当前“展开全部”是显式完整同步渲染，超大内容仍可能阻塞。不要只调高超时来掩盖主线程开销。

## 2026-10-11 SDK 流式渲染与思考展开回归

本轮仅修改 packages/agent-conversation，保留分支原有 16ms 事件批处理及 settle/500 事件测试改动。未执行 Git 写操作，未访问或修改 ontograph。

实现：MessageItem 将消息内容渲染隔离，renderVersion 显式更新 raw 快照；MarkdownText 与 LimitedText 共用 250ms 采样，先采样再拆图表/解析 Markdown；块键优先 ID/工具或交互 ID/turn + block 坐标，无身份块按对象保留键，旧快照替换时按同类型顺序匹配。列表层保留展开集合，并在新发送回合消息 ID 从本地转成持久化时通过 taskId 迁移；删除后清理。空快照不制造空 turn；尾沿渲染通知列表重新执行现有自动滚动策略。

环境：nvm use 成功，Node v20.19.0 / npm 10.8.2，Vitest 2.1.9 + jsdom，Vite 5.4.21。

- npm run test:sdk：18 个测试文件，208 个测试全部通过（原 197 个 + 新增 11 个）。附件下载测试仍有 jsdom 不支持 navigation 的提示，不影响测试通过。
- npm run build:sdk：成功，625 modules，主入口 dist/index.js 383.04 kB（gzip 104.40 kB）。
- 渲染隔离：两条消息，单条 raw 消息更新 10 次，MessageItem.render 仅调用 10 次，全部属于被更新消息；历史消息未重渲染。
- 1000 text delta / 模拟 1 秒：marked.parse 4 次，content_block_stop 后立即补最后一次，总计 5 次，完整显示 1000 字符，尾部计时器无重复解析。
- 展开 reasoning 的 1000 delta / 模拟 1 秒：解析 4 次，无后续事件时尾沿更新到最新内容；非 streaming 状态立即刷新最终文本。
- useConversation mock transport 真实 subscribe/refreshMessages 路径：流式期间、终态 records 回放、旧 blocks 快照、新发送回合本地 ID 替换为持久化 ID 均保持思考展开。另验证跨 turn 同 blockIndex、前插块、原始块版本更新、移除清理、计时器卸载与尾沿滚动。
- 原有 500 事件批处理测试继续通过。

预计：历史消息内容渲染由每帧所有消息缩为变化消息；流式单块解析频率由 16ms 批处理上限约 62.5 次/秒降为约 4 次/秒（约减少 94%，另有首次/终态/显式展开）。图表拆分也在采样后执行。此频率估算不代表主线程耗时同比下降；长文本仍整段解析，单条活跃消息仍需遍历自身块，初次大历史快照仍需解析。旧快照若缺少身份且思考块自身顺序改变，无法保证准确匹配。

本轮未重跑 OntoFoundry 真实生产会话、DataAgent 模型执行或 CPU profile；上面的计数是确定性 SDK 回归测试，生产构建通过。不能据此宣称已将用户原始 50 秒阻塞降低到某个实测值。

## 2026-10-11 第二轮：真实 OntoFoundry 历史会话

### 改动与确定性证据

- ThinkingBlock 在自身 computed 内订阅 presentation.expanded.has(blockKey)，MessageItem 不再读取集合；保留列表跨快照、跨 local/persisted message ID 的展开记忆。折叠内容保持 v-if。
- MarkdownText 按采样文本 computed 保存解析 HTML，文件链接重写单独依赖当前 resolver。大文本超过共享 LRU 预算也在当前组件内保留，链接/streaming/父更新不会重复解析。LimitedText 同样按采样文本缓存当前 HTML；共享 LRU 仍保持 128 条/512000 字符预算，超大内容不放进全局缓存。
- MessageItem 按块复用字段未变的工具 props；ToolOutput 的动作、输出分别依赖 input、output 的稳定 computed。相邻文本流式更新不再重建工具 computed 链；工具状态或 input 更新不会 normalize 未变的 output。
- scroll 改为 passive，事件不读取布局；非响应式滚动状态，单一 rAF 先读 scrollHeight/scrollTop/clientHeight 后至多写一次 scrollTop，目标为实际底部 scrollHeight-clientHeight。相同落点不重复写；程序化 scroll 的下一帧只读，不再引发写入回环。取消跟随、强制滚动与卸载清理均有回归覆盖。

historyRender.spec.js 使用原始 fixture：10 MessageItem、42 ThinkingBlock、38 ToolOutput、9 MarkdownText，各挂载渲染一次；首次只发生 9 次 marked.parse。点击第 21 个思考块：ThinkingBlock 渲染 1 次，MessageList/MessageItem/MarkdownText/ToolOutput 各 0 次；关闭同样只渲染该 ThinkingBlock 1 次，关闭不解析。工具动作计算不发生。单独 jsdom 样本首次脚本约 174.7ms（开发测试组件；不包含真实布局），全套并发执行时有波动，不将它设为易抖动的测试时间断言。

### 可重复 Chromium 基准

本轮开始时先用 nvm Node 20.19.0 构建已有未提交源码，复制 dist 至 `/tmp/odw-sdk-round2-before/dist`。前后均运行当前 bench/render.mjs，相同原始 fixture，预载 SDK 模块后测 JSON 解析、归一化、Web Component 初次挂载及两帧；不计网络下载/SDK 模块首次导入。最终前后顺序执行，无并行测试负载，无 CPU 降速，无 CPU profiler；所有样本开启相同 Timeline trace。各一次 warmup + 五个全新页面样本，报告中位数。脚本时间取 Timeline JS 区间并集（包含 v8.execute 的 RunMicrotasks），减去同步嵌套的 Layout/UpdateLayoutTree。CDP ScriptDuration 漏计 Vue 微任务，只作为诊断附值；不使用它作达标判断。

环境：darwin/arm64，Chromium 153.0.8010.12，Node v20.19.0。fixture 是仓库文件原样读取，实际 236563 bytes、315 records，SHA256 `e369560b93027683486560c5bc1ff09c62531bfbd6295b5e98c6af0ba1bdd4c1`；未裁剪。前后均显示 10 消息、42 思考块、38 工具块、2448 节点，初始工具面板 0，答案可见；实际自定义元素的思考展开/关闭、工具展开均通过，pageerror 为空。未改宿主或运行真实 NL2SQL 模型；这次验证对象为 SDK 渲染与交互，不涉及后台执行。

| 指标（中位数） | 本轮修改前 | 本轮修改后 |
| --- | ---: | ---: |
| 首次渲染脚本（含 Vue 微任务） | 40.064ms | 41.129ms |
| 首次渲染布局 | 38.203ms | 39.527ms |
| 首次渲染到两帧（含帧等待/布局） | 96.1ms | 97.0ms |
| 最长主线程任务 | 92ms | 95ms |
| 思考展开脚本 | 2.743ms | 0.699ms |
| 思考收起脚本 | 2.151ms | 0.291ms |
| 思考展开到下一 rAF | 2.9ms | 0.8ms |
| 思考收起到下一 rAF | 9.3ms | 11.3ms |

原始结果：[修改前](../../bench/results/round2-before.json)、[修改后](../../bench/results/round2-after.json)。结果附 index.js SHA256，明确对应哪个构建；旧文件 before.json/after.json 属于上一轮不同 fixture，不能用于本轮比较。

**结论边界：fixture 脚本 <1500ms 通过；首次渲染十倍提升未证实。** 修改前本地构建本已约 40ms，本轮首次挂载约 40ms，基本持平；展开脚本约减少 75%，收起约减少 86%。没有在本地复现用户报告的宿主 18.5s 初次展示或 904ms 点击；不可把宿主旧数字与本地新数字相除，宣称已经修复宿主。下一帧耗时受点击相位影响，收起的 rAF 时间并未改善；脚本减少与帧等待应分开看。

### 验证与剩余瓶颈

- 完整 test:sdk：215/215，通过（新增 7 项回归；最终滚动场景调整后又执行相关 33 项，全部通过）。test:sdk-types：React 18/19 均通过。build:sdk 成功。
- Chromium 原始快照 mount、思考展开/收起、工具展开通过；bench 默认以 1500ms 脚本中位数作为失败阈值。
- 本地仍有约 39.5ms 布局、约 41ms 初次脚本和 95ms 最长任务；完整 DOM 创建与排版仍是首次展示主要成本。没有删减消息/内容、改外观或引入虚拟列表。
- 长段流式文本仍每 250ms 重解析当前全文；显式展开全部仍可能产生长任务。共享缓存有界，实例只保留当前解析结果；组件被销毁后，超过共享预算的全文需要重新解析。
- 宿主独有的长阻塞来源仍未定位；保留同一 fixture、包指纹和脚本计量方法供宿主复测，不声称真实宿主已充分验证。

只改 agent-conversation 包内文件，保留上一轮未提交修改，不执行 git add/commit/checkout/reset 等 Git 写操作。
