# 设置页加载性能与 MCP 可见性执行计划

**Date:** 2026-09-15
**Design:** `docs/design/2026-09-15-settings-pages-load-performance-design.md`

## Tasks

### T1 Skill 列表去 N+1（对应 D1）

- `core/skill_admin_service.py`
  - `_document_api_payload()` 增加可选关键字参数 `skill_runtime`、`description_cache`
  - `_skill_description_from_front_matter()` 支持传入缓存
  - `list_documents()` 解析一次 `skill_runtime`，建立一次 `description_cache` 并下传
- 验证：`tests/test_skill_admin_service.py` 新增连接计数断言——设置读取次数与文档数无关

### T2 reindex 批量读取（对应 D2）

- `core/skill_admin_service.py`
  - `reindex_documents_from_disk()` 用一次 `store.list_documents()` 构建 `relative_path -> document` 映射，替换循环内 `get_document_by_path()`
  - `_migrate_document_paths_to_discovery_root()` 接收并复用同一映射
- 验证：现有 reindex 用例全绿；新增断言 reindex 期间 `get_document_by_path` 不再被逐文件调用

### T3 只读端点脱离事件循环（对应 D3）

- `api/admin_routes.py`：用 AST 判定后，将 32 个不含 `await`/`yield`/嵌套 async 的阻塞端点由 `async def` 改为 `def`
- `create_model_detection` 与 `import_skill` 两个真异步端点保持 `async def`
- 其余 `api/routes.py`、`api/eval_routes.py`、`api/auth_routes.py` 仍有同类端点，本次不动，留作独立变更
- 验证：并发探针中 `health` 与 Skill 请求并发时不再被拉长

### T4 内置 profile 种子一次性（对应 D4）

- `core/agent_profile_service.py`：`bootstrap_default_agent_profile()` 加模块级一次性标志与锁，缓存默认 profile
- 确认 `list_agent_profiles()` 仍返回实时列表
- 验证：新增测试断言多次调用只触发一次种子与回填；欢迎页连接数下降

### T5 模型页单次往返（对应 D5）

- `views/settings/DataAgentConfig.vue`：`loadSettings()` 删除 `listProviders()` 预取与其兜底分支
- 验证：现有模型页测试全绿；网络面板确认只剩一次 `settings` 请求

### T6 加载动画（对应 D6）

- 新增 `views/settings/components/SettingsListSkeleton.vue`：按列表行结构渲染骨架，单条扫光动画，尊重 `prefers-reduced-motion`
- `views/settings/SkillStudio.vue`：初次加载渲染骨架（标题计数与空状态此时都不渲染），刷新走 `v-loading` 遮罩；`.skill-table-wrapper` 补 `min-height: 180px`
- `views/settings/McpConfig.vue`：同样处理，保持同级面板一致；`.mcp-content` 补 `min-height: 180px`
- `views/settings/DataAgentConfig.vue`：已有 `min-height: 640px`，遮罩不会塌缩，本次不改其加载态
- 验证：前端单测断言初次加载显示骨架、刷新显示遮罩且内容不跳走

### T7 读路径只读数据库（对应 D7）

- `core/skill_admin_service.py`：`list_documents()` / `get_document_detail()` 去掉 reindex 调用
- 新增 `store.list_skill_manifest_contents()` 与 `_front_matter_value_from_text()`，描述从库中内容解析
- 明确不加 `refresh` 查询参数；`reindex_documents_from_disk()` 保留给启动与写路径
- 验证：读路径零磁盘访问测试；磁盘改动需显式 reindex 才可见的测试

### T8 MCP 开放给普通用户（对应 D8）

- 后端 `api/admin_routes.py`：`GET /mcp/servers` 移到 `user_router`；写端点留在 `skills_router`
- 后端 `core/mcp_admin_service.py`：新增按身份脱敏，补 `headers_set`、`env_set`
- `models/schemas.py`：MCP 行模型补两个布尔字段
- 前端 `router/index.js`：`/settings/mcp` 去掉 `adminOnly`
- 前端新增 `views/settings/components/SettingsListSkeleton.vue`，Skill 与 MCP 共用
- 前端 `SettingsLayout.vue`：MCP 菜单项去掉 `adminOnly`
- 前端 `McpConfig.vue`：引入 `canManage`，门控新增/编辑/删除/导入与启用开关
- 验证：脱敏单测（非 admin 拿不到 token 值）、路由测试更新、普通用户可进页面且无写入口

## Touched Files

后端：

- `dataagent/dataagent-backend/core/skill_admin_service.py`
- `dataagent/dataagent-backend/core/agent_profile_service.py`
- `dataagent/dataagent-backend/core/mcp_admin_service.py`
- `dataagent/dataagent-backend/api/admin_routes.py`
- `dataagent/dataagent-backend/models/schemas.py`
- `dataagent/dataagent-backend/tests/test_skill_admin_service.py`
- `dataagent/dataagent-backend/tests/test_mcp_admin_service.py`
- `dataagent/dataagent-backend/tests/test_agent_profile_service.py`
- `dataagent/dataagent-backend/core/skill_admin_store.py`

前端：

- `dataagent/dataagent-frontend/src/views/settings/SkillStudio.vue`
- `dataagent/dataagent-frontend/src/views/settings/components/SettingsListSkeleton.vue`（新增）
- `dataagent/dataagent-frontend/src/views/settings/McpConfig.vue`
- `dataagent/dataagent-frontend/src/views/settings/DataAgentConfig.vue`
- `dataagent/dataagent-frontend/src/router/index.js`
- `dataagent/dataagent-frontend/src/views/settings/SettingsLayout.vue`
- 对应 `__tests__` 用例

## Verification

分层验证：

1. 后端 `pytest` 针对 T1、T2、T4、T7 的定向用例
2. 前端 `nvm use` 后跑设置页相关单测
3. 本机端到端冒烟：MySQL `127.0.0.1:3306`、Redis `127.0.0.1:6379`、`.venv-py313`、backend + 前端 dev server
4. 复跑连接计数与并发阻塞探针，对比设计文档中的基线表

验收门槛（实测结果见设计文档 Verification 一节）：

- Skill 列表连接数从 255 降到个位数 —— 实测 3
- Skill 请求并发时 `health` 不再被拉长到数百毫秒 —— 实测 356 ms → 3 ms
- 欢迎页不再产生每次读取的全表 UPDATE —— 已改为每进程一次
- 读路径不触碰磁盘 —— 单测断言零文件读与零目录遍历
- 非 admin 的 MCP 响应不含任何凭证值 —— 单测断言 token 值不出现在序列化结果中
- 普通用户可进入 MCP 页且看不到写入口 —— 前端单测覆盖；auth 启用下的真实登录态未手测

## Rollout

单个改动集，无 schema 变更，无需迁移。前端与后端需同时发布：普通用户的 MCP 页依赖新的读端点权限层级。

## Backout

按任务回滚，互相独立：

- T1/T2/T4 为纯内部优化，回滚不影响响应结构
- T3 回滚即把 `def` 改回 `async def`
- T5/T6 为前端局部改动
- T7 回滚即把 reindex 调用放回 `list_documents()` / `get_document_detail()`
- T8 需前后端一起回滚，否则普通用户会拿到 403
