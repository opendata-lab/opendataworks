# 真实历史会话渲染基准

从仓库根目录运行，先成功加载 .nvmrc：

```sh
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm --prefix dataagent/dataagent-frontend run build:sdk
# playwright 已可解析时直接运行；否则指向现有 Playwright 的 index.mjs。
# export PLAYWRIGHT_MODULE=/absolute/path/to/playwright/index.mjs
node dataagent/dataagent-frontend/packages/agent-conversation/bench/render.mjs
```

默认使用 fixtures/ontofoundry-long-conversation.json 原样 BFF 响应。每次预热一次并测五个新页面；以实际 Chromium 布局渲染 Web Component，检查答案、消息数和思考/工具展开。输出首次渲染/交互脚本时间、布局、帧等待、Long Task、包与 fixture SHA256。默认脚本中位数必须 <1500ms，否则失败。测量从快照 JSON 解析开始，预载后的 SDK 模块导入和网络时间不计入。

脚本时间由 Timeline JS 区间并集得到（含 Vue RunMicrotasks），减去嵌套 Layout/UpdateLayoutTree；不是 Long Task 与 CDP ScriptDuration。后者漏计微任务。下一 rAF 时间包含等待帧，与脚本时间分开报告。

选项：`--dist /path/dist` 指向修改前的构建；`--snapshot /path/raw.json` 指定另一快照；`--max-script-ms 1500` 设置目标；`--profile /tmp/render.cpuprofile`、`--trace /tmp/render-trace.json` 保存第二个页面的诊断数据。前后比较应使用相同选项，并顺序运行，避免并行 build/test 和 profile 带来的扰动。

确定性渲染/解析计数使用同一 fixture 的组件测试（jsdom 仅测脚本和次数，不测布局）：

```sh
# 同样先 nvm use
DAC_RENDER_BENCH=1 node dataagent/dataagent-frontend/node_modules/vitest/vitest.mjs run \
  --root dataagent/dataagent-frontend \
  packages/agent-conversation/src/__tests__/historyRender.spec.js
```

输出 DAC_RENDER_COUNTS：挂载耗时、各组件首次渲染次数、marked.parse 次数及单块展开渲染次数。测试同时锁定单块关闭、超预算文本缓存、工具动作/输出重算、passive/rAF 滚动与程序化滚动回环。

本轮结果见 results/round2-before.json 和 results/round2-after.json。已有 before.json/after.json/host.json 使用其它快照，不能混作本轮基线。
