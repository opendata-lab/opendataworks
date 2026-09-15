# 剩余事件循环阻塞与设置页交互执行计划

**Date:** 2026-09-15
**Design:** `docs/design/2026-09-15-event-loop-blocking-and-settings-ux-design.md`

## Tasks

### T1 处理函数并发契约（对应 D1）

- 新增 `tests/test_handler_concurrency_contract.py`：`MUST_BE_SYNC` 与 `MUST_BE_ASYNC` 两份按文件的名单，外加一条钉住 SSE 生成器已知状态的测试
- `api/routes.py`：26 个做阻塞 I/O 的端点由 `async def` 改为 `def`
- `api/auth_routes.py`：只改 `login`（bcrypt）
- 逐个复核而非依赖 AST 启发式；`api_health` 等纯内存端点保持异步
- 验证：契约测试；并发探针对比设计文档基线表

### T2 加载提示延迟阈值（对应 D2）

- 新增 `src/utils/deferredLoading.js`：`useDeferredLoading(source, delay = 200)`
- `SkillStudio.vue` 与 `McpConfig.vue` 的 `isInitialLoading` 经过该包装
- 验证：单测断言快加载不出现骨架屏、慢加载越过阈值后出现；Playwright rAF 采样

### T3 模型管理页空态（对应 D3）

- `DataAgentConfig.vue`：`loadSettings()` 在 providers 为空时调用 `addNewProvider()`
- 「添加供应商」从 `.provider-nav-footer` 移到 `.provider-nav-header`，改 primary
- `.provider-nav-body` 加 `min-height: 0` 与 `overflow-y: auto`
- 左栏加空列表说明，右侧加 `v-else` 空态兜底
- 验证：单测断言空 providers 时自动进入新建态且右侧渲染；截图复核

### T4 侧栏底部身份三态（对应 D4）

- `SettingsLayout.vue`：footer 容器恒渲染，内部三态；新增 `goToLogin()`
- `.settings-user__trigger` 补 button 元素的样式重置，新增 `.is-static`
- 验证：单测覆盖三态与跳转参数

## Touched Files

后端：

- `dataagent/dataagent-backend/api/routes.py`
- `dataagent/dataagent-backend/api/auth_routes.py`
- `dataagent/dataagent-backend/tests/test_handler_concurrency_contract.py`（新增）

前端：

- `dataagent/dataagent-frontend/src/utils/deferredLoading.js`（新增）
- `dataagent/dataagent-frontend/src/views/settings/SkillStudio.vue`
- `dataagent/dataagent-frontend/src/views/settings/McpConfig.vue`
- `dataagent/dataagent-frontend/src/views/settings/DataAgentConfig.vue`
- `dataagent/dataagent-frontend/src/views/settings/SettingsLayout.vue`
- 对应 `__tests__` 用例

## Verification

1. 后端 `pytest`（含新增契约测试）
2. 前端 `nvm use` 后 `vitest`
3. 本机端到端：backend + 前端 dev server，Playwright 验证骨架屏时序与模型页空态
4. 复跑并发阻塞探针

验收门槛（实测见设计文档）：

- `topics` / `topic messages` / `runtime-config` 在 20 并发下不再把 `health` 拉到百毫秒级 —— 实测 185→36 ms、242→30 ms、86→26 ms
- 登录不再在事件循环上跑 bcrypt
- 快加载不出现骨架屏闪烁 —— 实测全程 CONTENT
- 慢加载出现骨架屏 —— 实测 221~620 ms
- 模型管理页无供应商时直接进入新建态且右侧表单可见
- 侧栏底部三态均非空白

## Rollout

无 schema 变更，无 API 契约变更。前后端可独立发布。

## Backout

- T1 回滚即把 `def` 改回 `async def`，同时删除契约测试
- T2 回滚即让 `isInitialLoading` 直接用原始 loading 标志
- T3、T4 为前端局部改动，互相独立
