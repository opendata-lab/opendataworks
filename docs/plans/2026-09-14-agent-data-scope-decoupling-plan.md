# DataAgent 数据范围解耦与通用智能体定位实施计划

**日期:** 2026-09-14
**主题:** agent-data-scope-decoupling
**对应设计文档:** `docs/design/2026-09-14-agent-data-scope-decoupling-design.md`

---

## 任务拆解

### 任务 1：Python 后端与系统提示词改造
- [ ] 修改 `prompts/data_agent_system_prompt.md`：更新通用数据与分析智能体定位，更新硬性约束中的权限与数据范围规范，移除未配置范围时的负向引导。
- [ ] 修改 `core/agent_runtime.py`：更新 `_build_system_prompt`，未配置范围时提示默认不限制、不阻塞流程；有配置时注明仅限平台 Portal MCP 数据源。
- [ ] 修改 `core/data_scope.py`：`encode_scope_header` 在 `allowed_scopes` 为空时返回 `""`。
- [ ] 修改 `core/mcp_admin_service.py`：`resolve_runtime_mcp_servers` 仅在 `scope_header` 非空时向 Portal MCP 添加请求头。
- [ ] 修改 `core/tool_runtime.py`：`_ensure_database_in_scope` 在未配置范围时直接放行。

### 任务 2：平台技能运行时更新
- [ ] 修改 `dataagent/.claude/skills/opendataworks-platform-tools/scripts/_opendataworks_runtime.py`：
  - `runtime_data_scope_header`：若 `allowed_scopes` 为空则返回 `""`，避免生成空数据范围头。

### 任务 3：Java 后端数据范围上下文优化
- [ ] 修改 `backend/src/main/java/com/onedata/portal/agentapi/scope/AgentDataScopeContext.java`：
  - 在 `setEncodedScope(String encodedScope)` 中，当 `encodedScope` 为空或解析出范围为空列表时，设置 `ACTIVE.set(false)`；仅当解析出非空有效范围项时才设置 `ACTIVE.set(true)`。
  - 对存在但无法解析或结构非法的请求头保持范围校验激活，并使用空授权集合拒绝访问。
- [ ] 更新 `backend/src/test/java/com/onedata/portal/agentapi/BackendAgentQueryServiceTest.java`：
  - 添加验证未设置数据范围或空范围头时，允许查询正常进行。
- [ ] 新增 `backend/src/test/java/com/onedata/portal/agentapi/scope/AgentDataScopeContextTest.java`：
  - 覆盖未配置、显式空范围、合法非空范围以及非法请求头 fail-closed。

### 任务 4：前端提示文案优化
- [ ] 修改 `dataagent/dataagent-frontend/src/views/intelligence/AgentDetailView.vue`：
  - 数据范围为空时文案改为“未配置限制（默认不限制，仅影响平台 Portal MCP）”。
  - 增加对数据范围限定于平台 Portal MCP 的提示。

### 任务 5：验证与回归
- [ ] 运行 Java 单元测试：`mvn test -pl backend -am -Dtest=BackendAgentQueryServiceTest,AgentDataScopeContextTest -DfailIfNoTests=false`。
- [ ] 运行 Python 单元测试：`pytest` 覆盖 `test_agent_runtime.py`、`test_readonly_query_proxy.py`、`test_odw_cli.py`。
- [ ] 运行平台 tools 技能相关回归测试。

---

## 回滚计划
若修改后出现非预期数据越权问题，可通过 Git revert 回滚相关 commit，重新启用显式空范围拦截。
