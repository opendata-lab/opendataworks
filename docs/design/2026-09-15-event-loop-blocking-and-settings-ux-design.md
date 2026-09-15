# 剩余事件循环阻塞与设置页交互设计

**Date:** 2026-09-15
**Goal:** 把 `api/routes.py` 与 `api/auth_routes.py` 里仍然跑在事件循环上的阻塞端点移到线程池，消掉骨架屏的单帧闪烁，并修掉模型管理页在没有供应商时的空页面与侧栏底部的空白。

**前序:** `docs/design/2026-09-15-settings-pages-load-performance-design.md`（已合入 PR #475），该次只处理了 `api/admin_routes.py`。

## Scope

覆盖 `api/routes.py`、`api/auth_routes.py` 的处理函数并发语义；`views/settings/SkillStudio.vue`、`McpConfig.vue` 的加载提示时机；`views/settings/DataAgentConfig.vue` 的空态与主操作位置；`views/settings/SettingsLayout.vue` 的侧栏底部身份区。

不覆盖 `api/eval_routes.py`（管理面低频）、SSE 生成器内的阻塞轮询（见 Tradeoffs）、连接池改造。

## Current State

前序变更只改了 `admin_routes.py`。实测同样 20 并发压单个端点时 `health` 的延迟（`health` 单独 3.4~5 ms）：

| 20 并发打这个端点 | `health` 延迟 |
|---|---|
| `topics` 列表（`routes.py`，未修） | 185 ms |
| `topic messages`（`routes.py`，未修） | 242 ms |
| `runtime-config`（`routes.py`，未修） | 86 ms |
| `agents`（`admin_routes.py`，已修） | 24.5 ms |

最后一行是对照组：同样压力下已修的端点好 8~10 倍。

骨架屏的实测行为（浏览器 rAF 采样，从 Skill 页点进 MCP 页）：

```
10-77ms    CONTENT    ← 旧页面仍在，vue-router 解析完异步组件才切换
119-119ms  SKELETON   ← 只存在一帧
152-1194ms CONTENT
```

## Problem

### P1 `routes.py` 与 `auth_routes.py` 的阻塞端点仍在事件循环上

FastAPI 在事件循环上直接执行 `async def`，在线程池执行 `def`。这里没有 async MySQL 驱动，`pymysql` 全是阻塞调用，所以声明成 `async def` 的只读端点会在自己执行期间冻住整个进程，包括每一条打开的 SSE 对话流。

`auth_routes.login` 更直接：它在事件循环上跑 bcrypt 校验。实测 cost=10 为 79 ms，cost=12 为 320 ms —— 一次登录就把整个服务冻住这么久，连续尝试还会串行累加。

### P2 骨架屏只存在一帧，是闪烁而不是提示

前序变更给首屏加了骨架屏，但接口现在 6~16 ms 就返回，骨架屏只渲染一帧（实测 `119-119ms`）。用户报告「MCP 页也需要骨架屏」，实际是骨架屏在那里但看不见 —— 而一帧的出现比不出现更糟，它是一次闪烁。

同时前序设计里「路由级骨架屏盖住 chunk 加载空白」的判断是错的。实测显示 vue-router 4 在导航完成前就解析异步组件，切换期间旧页面一直在，**不存在空白期**，因此不需要路由级骨架屏。

### P3 模型管理页没有供应商时整页是空的

`.provider-detail` 的条件是 `v-if="currentProvider && currentDraft"`。一个供应商都没有时右侧什么都不渲染，左栏也只有一个孤零零的按钮。而此刻页面上唯一有意义的动作就是配置第一个供应商。

「添加供应商」放在 `.provider-nav-footer`，供应商一多就被推出可视区。

### P4 侧栏底部在没有用户时完全消失

`v-if="authStore.enabled && authStore.currentUser"` 让整个 footer 在两种情况下都不渲染：登录已启用但无会话、以及登录整体未启用。左下角是一片空白。

## Design

### D1 按「是否真的阻塞」逐个端点判定

只把**确实做阻塞工作**（MySQL、文件系统、bcrypt）的处理函数改成 `def`。纯内存读取的保持 `async def` —— 把它们塞进线程池只是白白多一次线程切换。

因此是两份名单而不是一条通用规则。判定结果落在 `tests/test_handler_concurrency_contract.py` 里，既是回归防护也是这条规则的文档。

`routes.py` 转同步 26 个（topic/task/message/queue/schedule 的读写），保持异步的包括：

- `api_health` —— 纯内存配置读取
- `api_upload_topic_file`、`api_generate_followup_suggestions`、`api_deliver_message`、`api_create_task`、`api_cancel_task`、`api_consume_message_queue`、`api_execute_readonly_query` —— await 真协程
- `api_stream_sdk_events` —— 返回 `StreamingResponse`

`auth_routes.py` 只转 `login`（bcrypt）；`auth_config`、`me`、`logout`、`oauth_authorize` 都是纯内存/HMAC 工作，保持异步。

**不再使用批量 AST 脚本判定。** 前序变更里那个启发式把 `api_stream_sdk_events` 判成「无 await」，因为它的 await 在另一个模块级生成器里。判定结果必须逐个复核。

### D2 加载提示加下限延迟

新增 `utils/deferredLoading.js` 的 `useDeferredLoading(source, delay = 200)`：loading 持续超过 200 ms 才把标志置真。

快的时候骨架屏完全不出现（无闪烁），慢的时候（生产 MySQL 在远端、skill 数量更多）才出现。**不用最短展示时长**，那会故意拖慢已经很快的页面。

组件级骨架屏保留：它盖的是「组件已挂载、数据在途」这一段，路由级盖不到；去掉它会让慢加载时闪过空列表和错误计数。

### D3 模型管理页直接进入新建态

`loadSettings()` 拿到空 providers 时直接调 `addNewProvider()` —— 该函数已经会推一个草稿并选中它，右侧表单随之渲染。

「添加供应商」移到 `.provider-nav-header`，列表区加 `overflow-y: auto`，主操作不再被列表挤走。

另外给右侧补一个 `v-else` 空态兜底，覆盖加载失败等 providers 为空但未进入新建态的情况。

### D4 侧栏底部三态

footer 容器始终渲染，内部分三种情况：

- 登录已启用 + 有会话 → 用户名 + 退出登录（原行为）
- 登录已启用 + 无会话 → 「未登录」，可点，跳登录页并带 redirect
- 登录整体未启用 → 「未启用登录」，静态弱化样式

第三种刻意不写「未登录」：登录没开启时不存在「未登录」这回事，那样写会让运维去找登录入口。这一条偏离了字面需求，取的是同样的意图（别留空）加上表述准确。

## Interfaces

无 API 契约变更。`routes.py` 与 `auth_routes.py` 的处理函数并发语义改变，请求与响应结构不变。

## Tradeoffs

**SSE 生成器不动。** `_stream_sdk_events` 在事件循环上做阻塞轮询（`list_sdk_records` + `get_task`），实测 2.5 ms/poll，poll 间隔 1 s，10 条并发流占用约 2%。当前并发下可接受，且改 handler 的 `async def` 解决不了 —— 阻塞在生成器里，真要修得给那两次调用套 `anyio.to_thread.run_sync`。用 `test_handler_concurrency_contract.py` 里一条测试把这个已知状态钉住，避免后来者误以为已处理。

**线程池上限。** 转同步后这些端点占用 AnyIO 默认 40 个工作线程。20 并发下实测收敛到 25~36 ms，是线程池排队而非串行阻塞。若并发继续上升，正解是连接池而不是加线程。

**200 ms 阈值。** 快网络下用户永远看不到骨架屏，这正是目的。代价是 100~200 ms 这一段既无骨架屏也无内容变化 —— 但那个区间人眼本来就感知不到。

## Verification

- 后端：`tests/test_handler_concurrency_contract.py` 锁定两份名单，并钉住 SSE 已知状态
- 前端：骨架屏延迟阈值（快=不出现、慢=出现）、刷新态、模型页空态自动进入新建、footer 三态
- 端到端：本机 MySQL + Redis + backend + 前端 dev server，Playwright rAF 采样验证骨架屏时序，并复跑并发阻塞探针

### 实测结果

环境：MySQL `127.0.0.1:3306`、schema `dataagent`、Redis `127.0.0.1:6379`、`.venv-py313`、backend `127.0.0.1:8900`、前端 `localhost:3001`、auth 关闭。

20 并发下 `health` 的延迟（`health` 单独 3.4 ms）：

| 端点 | 改前 | 改后 |
|---|---|---|
| `topics` 列表 | 185 ms | **36.4 ms** |
| `topic messages` | 242 ms | **29.5 ms** |
| `runtime-config` | 86 ms | **25.8 ms** |
| `agents`（前序已修，对照） | 24.5 ms | 32.6 ms |

四者收敛到同一量级，即线程池排队的地板值。

骨架屏时序（Playwright rAF 采样）：

| 场景 | 结果 |
|---|---|
| 正常导航进 MCP 页 | `3-1093ms CONTENT` —— 骨架屏完全不出现，无闪烁 |
| 接口人为延迟 600 ms | `4-203ms CONTENT` → `221-620ms SKELETON` → `637ms+ CONTENT` |

测试：后端 746 通过，前端 491 通过。

`tests/test_pi_runtime_e2e.py::test_real_cell_completes_the_protocol_round_trip` 失败，已确认前序即失败（需编译好的 Pi runtime）。

未覆盖：auth 启用下的真实登录态手测，footer 的「未登录」分支仅由单测覆盖。
