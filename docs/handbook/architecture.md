# 架构与运行时

本章沉淀稳定的架构与运行时说明。活动中的中大型变更设计请查看 `docs/design/`。

本仓库是 OpenDataWorks 门户。智能问数由独立仓库 [opendataagent](https://github.com/opendata-lab/opendataagent) 的 DataAgent 提供：门户通过 widget 内嵌它，它通过 `odw-mcp-server` 访问平台。两者单独部署，可以在不同机器上。

## 组件一览

```
┌────────────────────────────────────────┐
│ Vue3 + Vite 前端 (frontend/)           │
│  - 门户、建模、调度、血缘              │
│  - 远程加载 DataAgent widget           │
└───────┬───────────────────────┬────────┘
        │ HTTP/REST             │ nginx 转发 /dataagent/widget/、/api/v1/nl2sql/ 等
┌───────▼────────────┐  ┌───────▼────────────────┐
│ Spring Boot 后端    │  │ DataAgent（独立部署）   │
│ (backend/)          │  │ opendataagent 仓库      │
│ 元数据/调度/血缘 API │  └───────┬────────────────┘
└────────▲───────────┘          │ HTTP MCP + X-ODW-MCP-Token
         │ Java agent API       │ X-Agent-User-Id / X-Agent-Id
┌────────┴───────────┐          │
│ ODW MCP Server      │◄─────────┘
│ odw-mcp-server/     │
└────────────────────┘
```

## 关键职责与接口

| 模块 | 关键包/目录 | 职责 | 关键接口 |
| --- | --- | --- | --- |
| `backend` | `com.onedata.portal` | 表/字段/任务 CRUD、血缘生成、巡检、执行记录、调用 DolphinScheduler OpenAPI | `/api/v1/tables`, `/api/v1/tasks`, `/api/v1/lineage`, `/api/v1/dolphin/...` |
| `backend-agent-api` | `backend-agent-api/` | 面向 Agent 的平台接口，按主体映射的数据库账号执行 | `/api/v1/ai/...` |
| `frontend` | `src/views/*` | 门户界面、交互校验、日志展示、血缘 ECharts、通过远程 JS 嵌入智能问数 widget | `/dashboard`, `/workflows`, `/lineage`, `/intelligent-query` |
| `odw-mcp-server` | `odw-mcp-server/` | OpenDataWorks 平台 MCP，DataAgent 等客户端作为普通外部 MCP 接入 | `/health`, `/mcp` |
| `odw-skills/` | `odw-skills/*` | OpenDataWorks 专属 Skill，通过 DataAgent 的 Skill 导入使用 | — |
| Dinky / Flink | 外部预留能力 | 未来流任务执行引擎 | 当前仓库未内置模块 |
| `DolphinScheduler` | 外部集群 | 接收 Portal 提交的工作流/实例并运行 | OpenAPI `/projects/...` |

## 数据流与生命周期

1. **表建模**：用户在前端填写分层、业务域、字段 → 后端持久化 `data_table`、`data_field` → 可选择生成 Doris DDL。
2. **任务建模**：填写 SQL/调度策略/输入输出 → 后端解析依赖 → 保存 `data_task`、`table_task_relation`、`data_lineage`。
3. **调度下发**：Portal 通过 OpenAPI 直接创建/更新工作流、节点、调度计划 → DolphinScheduler 执行。
4. **执行反馈**：Portal 通过 OpenAPI 查询实例状态/日志 → 写入 `task_execution_log`，并刷新血缘/统计。
5. **巡检治理**：`inspection_rule` 定义命名/Owner/统计规则 → 定期生成 `inspection_record` + `inspection_issue` → 前端提示整改。
6. **智能问数**：主前端加载 `DataAgentWidget`，问答请求经门户 nginx 转发到 DataAgent；DataAgent 调用 `odw-mcp-server` 的 `odw_*` 工具，平台按人和智能体映射的数据库账号执行并取交集。

## 运行态观察点

| 组件 | 端口 | 健康检查 | 日志|
| --- | --- | --- | --- |
| MySQL | 3306（prod compose）/ 3316（dev compose） | `mysqladmin ping` | Docker 卷 `mysql-data` 或 `/var/lib/mysql` |
| Redis | 6379 | `redis-cli ping` | 容器日志 |
| Backend | 8080 | `/api/v1/health` | `docker volume backend-logs` 或 `backend/logs/` |
| DolphinScheduler | 12345 (示例) | `/dolphinscheduler` 登录页或 OpenAPI `/projects` | 外部集群日志 |
| Frontend | 80（容器）/ 8081（compose）/ 3000（Vite dev） | `/`、`/intelligent-query` | Nginx 日志或 `frontend/dist` |
| ODW MCP Server | 8801 | `/health` | 容器日志或 `odw-mcp-server/` |

DataAgent 的端口与观察点见 opendataagent 仓库的 `deploy/README.md`。

## 技术栈

| 层 | 技术 | 备注 |
| --- | --- | --- |
| 后端 | Java 8+, Spring Boot 2.7.18, MyBatis-Plus 3.5.5, Lombok | 使用 Maven reactor 构建 |
| 前端 | Vue 3.4+, Vite 5, Pinia, Element Plus, ECharts | DataAgent widget 由远程 JS 接入 |
| MCP | Python, FastMCP | `odw-mcp-server/` |
| 数据库 | MySQL 8.0+, Doris (目标库) | 初始化脚本见 `backend/src/main/resources/db/migration` (Flyway) |

## 架构演进提示

- **抽象层**：DolphinScheduler 适配服务隔离了后端与调度引擎；未来扩展其它调度器时可复用与 Portal 的契约。
- **数据一致性**：所有任务/表/血缘信息以 MySQL 为准，DolphinScheduler/Dinky 视为从端，因此必须由 Portal 创建/更新。
- **配置集中化**：统一放在 `deploy/.env`, `backend/src/main/resources/application*.yml`, `frontend/.env.*`，并在 `operations-guide.md` 里列出需要修改的变量。
- **智能体边界**：DataAgent 不感知 OpenDataWorks；平台能力只经 `odw-mcp-server` 暴露，数据权限由平台按数据库账号执行。
