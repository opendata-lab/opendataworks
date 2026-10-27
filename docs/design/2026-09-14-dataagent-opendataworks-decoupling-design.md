# DataAgent 与 OpenDataWorks 解耦设计

**Date:** 2026-09-14（2026-10-27 改写：DataAgent 迁出到独立仓库 `opendataagent`；权限沿用已上线的主体-数据库账号映射；不做 Plugin）
**Goal:** DataAgent 迁到独立仓库 `opendataagent`，成为不感知 OpenDataWorks 的通用智能体平台；OpenDataWorks 的 MCP server 与 Skill 留在 `opendataworks`，作为 DataAgent 的普通外部 MCP 和可导入 Skill。
**Tech Stack:** DataAgent FastAPI / Vue 3 / Pi 与 Claude runtime；OpenDataWorks Java 8 / Spring Boot 2.7 / MySQL / Vue 3；Python FastMCP；Docker Compose。

> 本文取代 `2026-03-20-portal-mcp-design.md` 中“DataAgent 默认 MCP-first”和
> `2026-09-11-dataagent-mcp-provider-registry-design.md` 中的 `portal` 特殊分支。
> 数据权限不在本文重新设计，沿用 `2026-10-26-dataagent-datasource-identity-design.md`：
> 人和智能体映射到数据库账号，权限来自数据库 GRANT，代人问数取两者交集。
> 原稿中的“租户静态 API Key + 租户数据白名单”方案已被该设计取代，不实施。

## Scope

覆盖：

- `opendataworks/dataagent/` 中除 `portal-mcp` 和 OpenDataWorks 专属 Skill 外的全部内容，迁到 `/Users/guoruping/project/bigdata/opendataagent` 仓库根目录，覆盖该仓库原有 MVP；
- `dataagent/portal-mcp/` 迁到 `opendataworks/odw-mcp-servers/opendataworks/`，统一改用 `odw` 命名；
- OpenDataWorks 专属 Skill 迁到 `opendataworks/odw-skills/`；
- DataAgent 中的 `portal` 特例、内置 OpenDataWorks 智能体、平台直连与专属提示词全部删除；
- DataAgent 的身份请求头变成通用契约，对所有 HTTP MCP 一致发送；
- 两个仓库各自的部署、镜像构建、CI、文档和测试的所有权调整。

不覆盖：

- Plugin 打包与导入（`odw-plugins/`、DataAgent Plugin 接口与页面），记入 `docs/TODO.md`；
- 租户、API Key、数据白名单；
- `opendataworks/opendataagent/`（Go 版产品线）与根目录 `skills/`，保持不动；
- 改变主门户通过 widget 嵌入 DataAgent 的能力。

## Current State

1. DataAgent 源码位于 `opendataworks/dataagent/`：`dataagent-backend`、`dataagent-frontend`、`dataagent-runtime-pi`、`contracts`、`.claude/skills`、`portal-mcp`。部署资产在 `deploy/docker/dataagent`、`deploy/docker/nginx/dataagent-frontend.conf`，评测在 `tools/dataagent-evals` 与根 `tests/test_*dataagent*`，镜像在 `.github/workflows/docker-build.yml` 构建。
2. 启动时 `bootstrap_portal_mcp_server()` 从 `DATAAGENT_PORTAL_MCP_*` 自动创建不可编辑的 `server_id='portal'` 记录；`mcp_server_ids is None` 时默认选 `portal`；`portal` 有尾斜杠、固定工具清单、身份头注入等专属分支（`core/mcp_admin_service.py`、`core/agent_runtime.py`、`core/agent_profile_service.py`）。
3. `agent_opendataworks` 和 `agent_ontology_modeling` 是内置智能体，默认绑定 `portal` MCP 与 OpenDataWorks Skill。
4. 内置 Skill 混有平台专属内容：`opendataworks-business-knowledge`、`opendataworks-platform-tools`、`opendataworks-data-dev`、`opendataworks-methodology-dag`、`ontology-modeling-assistant`。
5. DataAgent 仍有平台直连：`readonly_query_proxy`、datasource resolver、`odw-cli` fallback，以及 OpenDataWorks 元数据库连接配置。
6. 会话来源值是 `portal`，widget 叫 `OpenDataWorksWidget`，客户端请求头是 `X-ODW-*`。
7. `opendataagent` 仓库（`git@github.com:MingkeVan/opendataagent.git`）当前是 18 个提交的早期 MVP，与 DataAgent 无代码关系。

## Design

### 1. 所有权

| 能力 | 所有者 | 位置 |
|---|---|---|
| 会话、任务编排、模型、通用 Skill/MCP 管理、通用内置 Skill | DataAgent | `opendataagent` 仓库 |
| OpenDataWorks MCP server | OpenDataWorks | `opendataworks/odw-mcp-servers/opendataworks/` |
| OpenDataWorks 业务知识、平台工具、数据开发、方法论、本体建模 Skill | OpenDataWorks | `opendataworks/odw-skills/` |
| 人/智能体到数据库账号的映射、权限执行 | OpenDataWorks backend | 已上线，不变 |

原则：**DataAgent 选择连接哪个 MCP，并声明当前是谁在用哪个智能体；OpenDataWorks 根据映射的数据库账号决定能访问什么。**

### 2. `opendataagent` 仓库布局

`opendataworks/dataagent/` 的子目录原名平移到仓库根，不改目录名，保持 `SKILLS_ROOT_DIR`、Dockerfile 和测试路径的相对结构：

```text
opendataagent/
├── AGENTS.md / CLAUDE.md        # 从 opendataworks AGENTS.md 抽出 DataAgent 相关规则
├── README.md
├── .claude/skills/              # 仅通用内置 Skill
├── contracts/
├── dataagent-backend/
├── dataagent-frontend/
├── dataagent-runtime-pi/
├── deploy/                      # DataAgent 独立 compose、nginx、config 示例
├── tools/dataagent-evals/
├── tests/                       # 原根目录 dataagent 评测测试
├── docs/                        # TODO.md 与本次迁移说明
└── .github/workflows/           # DataAgent 镜像构建
```

覆盖方式：在 `opendataagent` 的 `main` 上用一个提交删除原有全部文件并放入新内容，保留该仓库原有历史；DataAgent 自身的历史保留在 `opendataworks`。`dataagent_runtime/` 下的本地 topic 目录是运行产物，不迁移。

镜像名保持 `opendataworks-dataagent-frontend/backend/runner` 不变，避免与 `opendataworks/opendataagent/` 的 `opendataagent-server/web` 冲突，也不需要改现有部署变量。

### 3. 通用内置 Skill

DataAgent 只保留不依赖 OpenDataWorks 的 Skill：`chart-visualization`、`report-generation`（迁移时检查，凡引用 `portal_*`、`odw_*`、OpenDataWorks 或平台环境变量的内容必须删掉或改为通用表述）。

`entity-extraction`、`ontology-discovery`、`semantic-grounding` 属于 `ontograph` 项目（源码在 `ontograph/integrations/dataagent/skills/`），只是本地复制到了 `dataagent/.claude/skills/`、未被 git 跟踪，不是 DataAgent 内置 Skill，不迁移；需要时从 ontograph 导入。

`opendataworks-platform-tools` 中不属于平台访问的脚本（`build_chart_spec.py`、`format_answer.py`、`generate_report.py`）合入 `chart-visualization` / `report-generation`，其余迁入 `odw-skills/`。

### 4. DataAgent 去耦

删除：

- `bootstrap_portal_mcp_server()`、`PORTAL_MCP_*` 常量、`DATAAGENT_PORTAL_MCP_*` 配置；
- `mcp_server_ids is None` 默认选 `portal`。未选择 MCP 就是不挂载 MCP；
- `portal` 的尾斜杠、固定工具清单、超时和自动授权分支；
- `permission_gate.py` 等处对 `portal_*` 工具名的硬编码。工具风险等级改为读 MCP `tools/list` 的 annotations：`readOnlyHint=true` 按会话权限模式自动执行；缺失或 `false` 视为写操作需确认；`destructiveHint=true` 视为高风险，plan 模式禁止；
- Pi runtime 中 `portal-mcp-client` 命名改为通用 `mcp-client`；
- `readonly_query_proxy`、datasource resolver、`odw-cli` fallback、OpenDataWorks 元数据库/Doris 配置。DataAgent 只连接自己的 `dataagent` schema；
- 内置智能体 `agent_opendataworks`、`agent_ontology_modeling` 的 bootstrap；`agent_default` 的描述去掉 OpenDataWorks；
- system prompt、追问建议、摘要、评测等提示词中的 OpenDataWorks、Portal MCP、`portal_*`、平台脚本路径。

产品命名一步切换，不保留兼容读取（DataAgent 与主门户在同一次发布中同时更新）：

- 会话来源 `portal` → `web`，Alembic 迁移改写历史数据与列默认值；
- widget `OpenDataWorksWidget` → `DataAgentWidget`，构建产物 `opendataworks-widget.bundle.js` → `dataagent-widget.bundle.js`；
- widget 客户端请求头 `X-ODW-*` → `X-DataAgent-*`；
- OpenDataWorks 主前端 `dataagentWidgetLoader.js` 改为消费新名称。

### 5. 身份请求头是 DataAgent 的通用契约

DataAgent 对每个 HTTP MCP 调用都附加执行身份，不区分是哪个 MCP：

- `X-Agent-User-Id`：带来源前缀的登录用户（`local:<用户名>` / `<provider>:<sub>`），沿用 `auth_user_id`；
- `X-Agent-Id`：当前智能体 ID。

这两个头由 DataAgent 后端从会话填写，不来自模型参数。MCP 自身的访问凭证（OpenDataWorks 的前门 token）是 MCP 记录里管理员配置的普通 header，DataAgent 不理解其含义。OpenDataWorks 只在前门 token 校验通过后才信任这两个身份头，这与现行实现一致。

### 6. 已有数据的一步迁移

DataAgent 新增一个 Alembic 迁移：

- `portal` MCP 记录改为普通可编辑记录（不再是 plugin/只读来源），内容不变，管理员按新地址和 `odw` 工具名更新；
- `agent_opendataworks`、`agent_ontology_modeling` 改为普通非内置智能体，保留历史会话引用；
- 会话来源 `portal` → `web`。

全新数据库不产生任何 OpenDataWorks MCP、智能体或 Skill。

### 7. `odw-mcp-server`

`dataagent/portal-mcp` → `odw-mcp-servers/opendataworks`，一次性改名，不保留旧名：

- Python 包 `portal_mcp` → `odw_mcp_server`；server name、compose service `portal-mcp` → `odw-mcp-server`；镜像 `opendataworks-portal-mcp` → `opendataworks-odw-mcp-server`；
- 环境变量 `PORTAL_MCP_*` → `ODW_MCP_SERVER_*`；前门 token 头 `X-Portal-MCP-Token` → `X-ODW-MCP-Token`；
- 工具名 `portal_*` → `odw_*`，并为每个工具写准确的 `readOnlyHint` / `destructiveHint` / `idempotentHint` annotations；
- 行为不变：校验前门 token，把 `X-Agent-User-Id`、`X-Agent-Id` 连同服务令牌 `X-Agent-Service-Token` 转发给 `backend-agent-api`。

### 8. `odw-skills/`

OpenDataWorks 专属 Skill 平移到 `opendataworks/odw-skills/<原目录名>/`，内容中的 `portal_*` 改为 `odw_*`。Skill 只通过 MCP 工具访问平台；依赖 `readonly_query_proxy` 或 `odw-cli` 直连的脚本删除，`SKILL.md` 和 `reference/` 同步改写。安装方式是 DataAgent 已有的 Skill 导入。

## Deployment

`opendataagent`：

- 自带 `deploy/docker-compose.yml`（mysql、redis、dataagent-backend、sandbox-runner、frontend），不含任何 OpenDataWorks 服务或 `ODW_*` / `PORTAL_MCP_*` 变量；
- 自带镜像构建 workflow，镜像名不变。

`opendataworks`：

- `deploy/docker-compose.*.yml` 继续用镜像编排 DataAgent，`portal-mcp` 服务改为 `odw-mcp-server`；DataAgent 不 `depends_on` `odw-mcp-server`，容器不注入 `DATAAGENT_PORTAL_MCP_*`、Doris 或平台元数据库凭证；skills 挂载不再指向 `../dataagent/.claude/skills`；
- `docker-build.yml` 删除 DataAgent 和评测镜像构建，`portal-mcp` 改为 `odw-mcp-server`；
- `deploy/README.md` 写明接入步骤：在 DataAgent 新增 HTTP MCP（URL 指向 `odw-mcp-server`，header 填 `X-ODW-MCP-Token`），导入 `odw-skills/` 下所需 Skill，新建智能体并绑定它们，再按已上线的身份设计为人和智能体配置数据库账号映射；
- `AGENTS.md` 删除 DataAgent 模块附录，改为指向 `opendataagent` 仓库；`.gitignore`、`README.md`、`website/` 中的 DataAgent 源码路径同步更新。

DataAgent 必须能在没有 OpenDataWorks backend、`odw-mcp-server` 和任何平台变量的情况下启动、创建智能体、使用内置 Skill、挂载任意第三方 MCP 并完成一次会话。

## Completion Criteria

- `opendataworks` 中不再有 `dataagent/` 目录；`odw-mcp-servers/opendataworks` 可独立构建和测试；
- `opendataagent` 仓库的后端、前端、Pi runtime 测试与构建全部可运行，结果与迁移前一致（既有失败除外，并记入 TODO）；
- 在 `opendataagent` 中搜索不到作为产品依赖的 `OpenDataWorks`、`portal`、`portal_*`、`odw_*`、`PORTAL_MCP`、`ODW_` 常量、工具名或环境变量（迁移脚本和历史文档除外）；
- 全新 DataAgent 数据库启动后没有 OpenDataWorks MCP、智能体或 Skill；
- 按 `deploy/README.md` 接入后，通过 DataAgent → `odw-mcp-server` → `backend-agent-api` 完成一次真实问数，身份头生效，缺映射时被拒绝。

## Risks / Alternatives

- 工具名、服务名、widget 名和会话来源一次性切换，旧智能体 prompt 与保存的 MCP 配置需要管理员更新。这是有意的一步到位，部署文档列出需要改的地方。
- 身份头发送给所有 HTTP MCP，第三方 MCP 也能看到用户标识。它不是凭证，接受这一点，换取 DataAgent 内不区分 MCP。
- 已有部署的 OpenDataWorks Skill 不再随 DataAgent 镜像发布，升级后需按部署文档导入一次。

未采用：

- **只移动目录。** 不能消除 portal 自动注册、默认 MCP、提示词和平台直连。
- **租户 API Key + 白名单。** 已被主体-数据库账号映射取代，权限直接来自数据库 GRANT，不需要第二套白名单。
- **本次同时做 Plugin。** 现有 Skill 导入和 MCP 新增已能完成接入，Plugin 记入 TODO。

## Verification

1. `opendataagent` 独立：干净 `dataagent` schema 上 `alembic upgrade head` 后启动，确认没有 OpenDataWorks MCP/智能体/Skill；后端 pytest、前端 test 与 build、Pi test 与 build 全部运行；本地按 AGENTS 冒烟方法跑一次真实问答。
2. `odw-mcp-server`：自身测试通过；工具名为 `odw_*` 且 annotations 正确；无前门 token、缺身份头时拒绝。
3. 联调：按 `deploy/README.md` 在 DataAgent 中新增 MCP、导入 Skill、建智能体，对真实 PostgreSQL 或 MySQL 数据源问一次数，确认身份头到达 `backend-agent-api`，缺账号映射时被拒绝。
4. 迁移：在带旧数据（`portal` MCP、内置 ODW 智能体、`source='portal'` 会话）的库上执行 Alembic 迁移，确认记录转为普通记录且历史会话可读。
5. 静态扫描：`opendataagent` 核心路径无 `portal` 特例与 ODW 常量；`opendataworks` 无 `dataagent/` 源码路径残留。
