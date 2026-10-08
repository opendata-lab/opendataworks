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
