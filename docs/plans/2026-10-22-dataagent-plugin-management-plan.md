# DataAgent 插件管理与导入计划

设计：[DataAgent 插件管理与导入设计](../design/2026-10-22-dataagent-plugin-management-design.md)。涉及：DataAgent 后端（Python/FastAPI/Alembic）、DataAgent 前端（Vue 3/Element Plus）、部署文档；`dataagent-runtime-pi` 不改。

## 第一阶段：skill + MCP 插件

0. **通用前置修正（可单独先发布）**
   - `core/agent_runtime.py` 的 `resolve_agent_skill_runtime` 和 `sandbox_runner_main.py` 的对应逻辑：快照中的 skill folder 与"全局启用且目录存在"取交集，剔除的记 warning。
   - 测试：停用普通 skill 后，已发布 agent 的运行时不再包含它；发布说明写明这一行为变化。
1. **数据模型**
   - Alembic：`da_plugin`、`da_plugin_operation`，`da_mcp_server.plugin_id`；表显式使用 `COLLATE=utf8mb4_unicode_ci`。
   - 配置 `DATAAGENT_PLUGIN_STORE_DIR`（位于 skill 扫描根目录之外）；部署文档写明多副本时它和 `SKILLS_ROOT_DIR` 必须是共享存储。
   - `core/plugin_store.py`（新增）：插件行和操作记录。`core/runtime_registry_store.py` 和 settings 持久化增加"使用调用方传入的连接"的写法，以便在一个事务里提交。
2. **包解析与预览**
   - `core/plugin_package.py`（新增）：流式安全解压（包 20MB、解压后总量 50MB、单文件 5MB、文件数 2000），解析 manifest 子集、`.mcp.json`（规范化复用 `mcp_admin_service.normalize_mcp_server`，忽略包内的归属字段，拒绝 `${CLAUDE_PLUGIN_ROOT}`）、`skills/*`；生成未支持声明、安装参数绑定、stdio 确认令牌、前缀改写差异和路径长度校验。
   - 预览上传写入 `PLUGIN_STORE_DIR/previews/`，TTL 30 分钟，总量有上限。
3. **安装、升级、卸载、启停、轮换凭据、导出**
   - `core/plugin_service.py`（新增）：按设计 §6.1 实现命名锁、暂存、交换、单事务提交、限定范围 reindex，以及启动恢复（在 `main.py` 的启动流程中调用）。
   - 普通 skill 的写入口统一改为先拿同一把命名锁。
   - 级联启停时整体校验"不能停用最后一个启用的 skill"；新装插件强制为 disabled，升级保留原状态。
   - 引用处理：`agent_profile_service` 和 `agent_draft_service` 新增按 skill folder 和 server id 反查引用的函数；移除引用时，草稿的 revision 递增并清除旧的预览关联。
   - 导出：直接返回插件仓库中的原始包。
4. **后端归属保护**：`skill_admin_service` 的保存、回滚、覆盖导入、卸载、启停，以及 `mcp_admin_service` 的更新、删除、导入覆盖，都拒绝插件部件，返回 409；skill 列表中插件部件返回 `source=plugin`、`plugin_id`、`editable=false`。
5. **MCP 工具名冲突**：启用插件时探测工具列表，与内置工具同名的拒绝启用；agent 发布时校验所选 MCP 之间没有重名工具。
6. **API 和响应模型**：在 `api/admin_routes.py` 的 `skills_router` 下增加 `/plugins/previews`、`/plugins`、`/plugins/{id}`、`/plugins/{id}/params`、`/plugins/{id}/export`；`models/schemas.py` 中 `/agents/capabilities` 增加 `plugins` 分组，skill 和 MCP 的响应增加 `plugin_id`。
7. **前端**：`dataagent-frontend/src/views/settings/PluginConfig.vue`（新增）及路由和菜单；`SkillStudio.vue`、`McpConfig.vue` 中插件部件显示标签并改为跳转到插件页，内置 Portal MCP 仍显示为"内置"。
8. **测试**（覆盖设计 §10 的清单）
   - `tests/test_plugin_package.py`：解析、限制、改写、令牌、ID 和冲突。
   - `tests/test_plugin_service.py`：各阶段注入失败、中断恢复、并发、限定范围 reindex、引用和草稿处理、导出往返。
   - `tests/test_plugin_ownership.py`：旧接口全部返回 409。
   - `tests/test_plugin_runtime_contract.py`：与手工导入等价；停用后运行时不再包含插件部件。
   - 前端：`PluginConfig` 组件测试；先 `nvm use`，再跑 build。
9. **端到端冒烟**（按 AGENTS.md 中的 Intelligent Query 冒烟方法）：本地 MySQL `dataagent` schema、Redis `127.0.0.1:6379`、`.venv-py313`、`DATAAGENT_RUNTIME_KIND=pi_agent_core`、`execution_mode=auto`，本地和沙箱子容器各跑一次。测试插件包含一个 skill 和一个调用 `npx` 或 `uvx` 的 stdio MCP（返回固定值的 echo 工具），覆盖：双副本预览和安装、启用、`agent_opendataworks` 勾选并发布、真实任务调用两类部件（确认 SSE 正常结束、终态和最终消息落库、`engine_kind`）、停用后部件不再出现、卸载后引用已清理。

## 后续：commands、子代理、workflow、hooks

不在本计划内。前置条件是运行时迁到 `pi-coding-agent` SDK（单独出设计文档）。迁移完成后，插件的 `commands/`、`agents/` 和子代理工作流通过扩展接入，`hooks/` 另做安全评审。

## 发布与回退

- 发布顺序：第 0 项可以先单独发布；之后依次是迁移、后端、前端。
- 回退：先停用或卸载所有插件，再回退后端，因为旧版后端没有归属保护；新表保留即可。前端回退只会隐藏入口。
