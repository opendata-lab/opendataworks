# 架构与运行时

本章沉淀稳定的架构与运行时说明。活动中的中大型变更设计请查看 `docs/design/`。当前仓库内长期并行维护两条智能体产品线：

- 主前端通过远程 JS 内嵌的现有智能问数
- 独立项目 `opendataagent`

本章重点说明门户主链、现有智能问数链路，以及 `opendataagent` 的独立定位。

## 组件一览

```
┌────────────────────────────────────────┐
│ Vue3 + Vite 前端 (frontend/)           │
│  - 门户、建模、调度、血缘              │
│  - 远程加载 DataAgent widget           │
└───────┬───────────────────────┬────────┘
        │ HTTP/REST             │ /dataagent/widget/*.js
┌───────▼────────────┐  ┌───────▼────────────────┐
│ Spring Boot 后端    │  │ DataAgent 前端          │
│ (backend/)          │  │ (dataagent-frontend/)   │
│ 元数据/调度/血缘 API │  │ 管理工作台 + widget     │
└────────┬───────────┘  └────────┬───────────────┘
         │ Java agent API        │ HTTP/SSE
         │ 只读平台能力          │
┌────────▼───────────┐  ┌────────▼───────────────┐
│ Portal MCP         │  │ DataAgent Backend       │
│ dataagent/portal-mcp│ │ Python / Claude SDK     │
└────────────────────┘  │ 现有智能问数主链        │
                        └────────┬───────────────┘
                                 │
                        ┌────────▼───────────────┐
                        │ dataagent 专用 skill    │
                        └────────────────────────┘

┌──────────────────────────────┐
│ Opendataagent                │
│ 独立 Go runtime + 根目录 skills│
└──────────────────────────────┘
```

## 关键职责与接口

| 模块 | 关键包/目录 | 职责 | 关键接口 |
| --- | --- | --- | --- |
| `backend` | `com.onedata.portal` | 表/字段/任务 CRUD、血缘生成、巡检、执行记录、调用 DolphinScheduler OpenAPI | `/api/v1/tables`, `/api/v1/tasks`, `/api/v1/lineage`, `/api/v1/dolphin/...` |
| `frontend` | `src/views/*` | 门户界面、交互校验、日志展示、血缘 ECharts、通过远程 JS 嵌入智能问数 widget | `/dashboard`, `/workflows`, `/lineage`, `/intelligent-query` |
| `dataagent-frontend` | `dataagent/dataagent-frontend` | DataAgent 独立管理工作台、智能问数问答页、floating/inline widget bundle | `/`, `/widget/opendataworks-widget.bundle.js`, `/widget/style.css` |
| `dataagent-backend` | `dataagent/dataagent-backend` | 现有智能问数主链，负责 NL2SQL 会话、Skills 同步、问答流式响应与 SQL 执行 | `/api/v1/nl2sql/...`, `/api/v1/dataagent/...` |
| `opendataagent` | `opendataagent/server`, `opendataagent/web` | 独立通用 agent 平台，负责 Skill、MCP、通用对话与模型配置 | `/api/v1/agent/...`, `/api/v1/settings/agent`, Web `18080` |
| `skills/` | `skills/platform/*`, `skills/generic/*` | 共享 skill 源码目录，主要服务 `opendataagent` | 通过构建/启动镜像加载 |
| `portal-mcp` | `dataagent/portal-mcp` | 现有兼容型 MCP 服务，继续保留，但不是共享平台 skill 主链 | `/health`, `/mcp` |
| `redis` | Compose 依赖 | DataAgent 任务协调、租约与调度锁 | 内部依赖，无对外 HTTP 接口 |
| Dinky / Flink | 外部预留能力 | 未来流任务执行引擎 | 当前仓库未内置模块 |
| `DolphinScheduler` | 外部集群 | 接收 Portal 提交的工作流/实例并运行 | OpenAPI `/projects/...` |

## 数据流与生命周期

1. **表建模**：用户在前端填写分层、业务域、字段 → 后端持久化 `data_table`、`data_field` → 可选择生成 Doris DDL。
2. **任务建模**：填写 SQL/调度策略/输入输出 → 后端解析依赖 → 保存 `data_task`、`table_task_relation`、`data_lineage`。
3. **调度下发**：Portal 通过 OpenAPI 直接创建/更新工作流、节点、调度计划 → DolphinScheduler 执行。
4. **执行反馈**：Portal 通过 OpenAPI 查询实例状态/日志 → 写入 `task_execution_log`，并刷新血缘/统计。
5. **巡检治理**：`inspection_rule` 定义命名/Owner/统计规则 → 定期生成 `inspection_record` + `inspection_issue` → 前端提示整改。
6. **并行智能体**：
   - 现有智能问数前端由 `dataagent-frontend` 构建，主前端通过远程 `OpenDataWorksWidget` JS 接入；运行时继续走 `dataagent-backend + dataagent/.claude/skills/dataagent-nl2sql`
   - `opendataagent` 走独立 Go runtime，并在构建或启动时镜像根 `skills/`

## 运行态观察点

| 组件 | 端口 | 健康检查 | 日志|
| --- | --- | --- | --- |
| MySQL | 3306（prod compose）/ 3316（dev compose） | `mysqladmin ping` | Docker 卷 `mysql-data` 或 `/var/lib/mysql` |
| Redis | 6379 | `redis-cli ping` | 容器日志 |
| Backend | 8080 | `/api/v1/health` | `docker volume backend-logs` 或 `backend/logs/` |
| DolphinScheduler | 12345 (示例) | `/dolphinscheduler` 登录页或 OpenAPI `/projects` | 外部集群日志 |
| Frontend | 80（容器）/ 8081（compose）/ 3000（Vite dev） | `/`、`/intelligent-query` | Nginx 日志或 `frontend/dist` |
| DataAgent Frontend | 80（容器）/ 8901（compose）/ 3001（Vite dev） | `/`、`/widget/opendataworks-widget.bundle.js` | Nginx 日志或 `dataagent/dataagent-frontend/dist` |
| DataAgent Backend | 8900 | `/api/v1/nl2sql/health` | 容器日志或 `dataagent/dataagent-backend` |
| Opendataagent Web | 18080 | `/` | 容器日志或 `opendataagent/web` |
| Opendataagent Server | 18900 | `/api/v1/agent/health` | 容器日志或 `opendataagent/server` |
| Portal MCP | 8801 | `/health` | 容器日志或 `dataagent/portal-mcp` |

## 技术栈

| 层 | 技术 | 备注 |
| --- | --- | --- |
| 后端 | Java 8+, Spring Boot 2.7.18, MyBatis-Plus 3.5.5, Lombok | 使用 Maven reactor 构建 |
| 前端 | Vue 3.4+, Vite 5, Pinia, Element Plus, ECharts | 主前端与 DataAgent 前端分别构建；DataAgent widget 由远程 JS 接入 |
| 数据库 | MySQL 8.0+, Doris (目标库) | 初始化脚本见 `backend/src/main/resources/db/migration` (Flyway) |

## 架构演进提示

- **抽象层**：DolphinScheduler 适配服务隔离了后端与调度引擎；未来扩展其它调度器时可复用与 Portal 的契约。
- **数据一致性**：所有任务/表/血缘信息以 MySQL 为准，DolphinScheduler/Dinky 视为从端，因此必须由 Portal 创建/更新。
- **配置集中化**：统一放在 `deploy/.env`, `backend/src/main/resources/application*.yml`, `frontend/.env.*`, `dataagent/dataagent-frontend/.env.*`，并在 `operations-guide.md` 里列出需要修改的变量。
- **智能体双轨**：现有智能问数与 `opendataagent` 长期并行，二者技术栈、运行时与技能来源不同，不能假设会在短期内合并。
