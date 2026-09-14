# DataAgent 数据范围解耦与通用智能体定位设计

**日期:** 2026-09-14  
**主题:** agent-data-scope-decoupling  
**目标:** 修复 DataAgent 在未配置数据范围时阻断元数据、数据和日志访问的问题，明确数据范围仅针对 OpenDataWorks 平台 Portal MCP，调整智能体定位为支持多 MCP 协同的通用数据与分析智能体。

---

## 一、当前现状与问题

### 1. 当前实现机制
- 2026-05-22 引入的 `agent-data-scope` 机制假定每个智能体必须配置明确的数据范围（`allowed_scopes`）。
- 若未配置数据范围（`allowed_scopes` 为空）：
  1. `core/agent_runtime.py` 的提示词拼接逻辑注入：
     `- 无。未配置数据范围时禁止访问任何元数据或查询任何数据。`
     导致大模型看到该硬性约束后直接放弃回答，拒绝调用任何工具。
  2. `core/tool_runtime.py` 中的 `_ensure_database_in_scope` 在 `DATAAGENT_DATA_SCOPE_JSON` 存在但 `allowed_scopes` 为空时，判定任何数据库均无权访问并抛出异常。
  3. `backend` Java 侧的 `AgentDataScopeContext` 在收到请求时（无论 Header 是否存在或内容为空），无条件激活 `ACTIVE.set(true)`，且 `currentScopes()` 为空，导致表过滤返回空列表、只读查询与 DDL 均被拒绝。

### 2. 架构缺陷
- **混淆了平台安全边界与通用智能体能力**：
  - 数据范围（Data Scope）是 OpenDataWorks 平台为了管控自身纳管数据源（Doris/MySQL 等）及 Portal MCP 的访问边界。
  - 用户可能为智能体接入其它 MCP（例如日志查询 MCP、Elasticsearch MCP、外部业务库 MCP、云监控 MCP 等）。
  - 当前的全局拦截与提示词将智能体死死绑在 OpenDataWorks 平台 Portal MCP 上，并且在未配置平台数据范围时把所有操作全部阻断，严重背离了实际业务场景。

---

## 二、设计目标

1. **未配置数据范围时不限制、不阻断**：
   - 当智能体未配置特定数据范围时，视为平台范围默认不限制（由各底层系统自身连接权限保障），绝不阻塞用户流程。
   - 允许正常检索元数据、查询数据、查阅日志与工作流。
2. **界定数据范围边界与系统解耦**：
   - DataAgent 与 OpenDataWorks 逐步作为两个独立项目演进，DataAgent 为通用智能体，不将 OpenDataWorks 或 Portal MCP 硬编码耦合在系统基础提示词中。
   - 智能体上的“数据范围”配置仅作为外部数据源/MCP 调用的授权约束参数，未配置特定数据范围时代表默认不限制。
   - 平台专有操作（如 Portal MCP、OpenDataWorks 专属写工具等）由平台自带的 Skill 或 MCP 自行声明其规范，基础提示词保持纯净和通用。
3. **调整定位与提示词**：
   - 将智能体定位调整为通用企业级智能数据与分析助手（Data Agent），支持协同任意多 MCP 与多工具。
   - 在系统提示词模板中完全移除 OpenDataWorks 与 Portal MCP 等专有名字硬编码；移除所有“未配置数据范围禁止访问”的拒答指令，改为积极协同当前可用工具。

---

## 三、系统级设计与契约变更

### 1. Python 后端与提示词体系
- **`prompts/data_agent_system_prompt.md`**：
   - 定位调整：明确是通用企业级智能数据与分析助手，具备业务语义理解、多源数据探索、日志与指标查询及分析能力；基于启用的各类 MCP、数据源、业务技能与系统工具灵活开展工作，不绑死特定平台或单一 MCP。
   - 硬性约束调整：遵守数据访问规范；配置数据范围时严格遵守；未配置时代表默认不限制，严禁以未配置数据范围为由直接拒绝访问元数据、数据或日志，严禁阻塞用户正常分析流程。
   - 完全解除与 Portal MCP/OpenDataWorks 的耦合，移除专有 MCP 工具名引用。
- **`core/agent_runtime.py`**：
   - `_build_system_prompt`:
     - 有配置范围时：注入通用 `# 已授权数据范围`，仅输出具体的数据库列表及约束说明，不绑死特定平台名称。
     - 未配置范围时：写入通用 `# 数据范围说明`，明确提示未配置特定数据范围限制（默认不限制），严禁以此为由拒绝用户或阻塞流程。
- **`core/data_scope.py`**：
   - `encode_scope_header`: `allowed_scopes` 为空时直接返回空字符串 `""`。
- **`core/mcp_admin_service.py`**：
   - `resolve_runtime_mcp_servers`: 仅在 `server_id == PORTAL_MCP_SERVER_ID` 且 `scope_header` 非空时，才注入 `X-Agent-Data-Scope` Header。
- **`core/tool_runtime.py`**：
   - `_ensure_database_in_scope`: 若 `allowed_scopes` 为空，跳过校验，不拦截查询。

### 2. 平台技能运行时
- **`_opendataworks_runtime.py`**：
  - `runtime_data_scope_header`: 若 `allowed_scopes` 为空，返回 `""`，使 `odw-cli` 不向后端传递空范围请求头。

### 3. Java 平台后端 (`backend`)
- **`AgentDataScopeContext.java`**：
  - `setEncodedScope(String encodedScope)`：
    - 解析 `encodedScope`，若为空或解析出的 `allowed_scopes` 为空列表，设置 `ACTIVE.set(false)` 并清空上下文。
    - 仅当解析出至少一个有效范围时才设置 `ACTIVE.set(true)`。
  - 效果：当请求未携带 Header 或 Header 为空范围时，`isActive()` 为 false，所有元数据检索、DDL、查询放行；当携带非空有效范围时，严格按范围校验与过滤。

### 4. 前端展示 (`dataagent-frontend`)
- `AgentDetailView.vue`：
  - 范围为空时显示：“未配置限制（默认不限制，仅影响平台 Portal MCP）”。
  - 提示文案说明该设置仅限制平台内置 Portal MCP。

---

## 四、向下兼容性分析

- **已有配置了明确数据范围的智能体**：行为完全保持不变，依然受到严格的数据库级别的隔离与限制。
- **新建或未配置数据范围的智能体**：不再进入“完全死锁”状态，而是能够正常调用所有启用的工具与 MCP，正常执行问数与日志排查。
- **第三方 MCP**：不再受到平台数据范围逻辑的干扰。
