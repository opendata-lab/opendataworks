# 双 Agent 平台共存说明

本仓库同时包含两套智能体（Agent）运行时。它们**有意并行、边界清晰，并非新旧重复或冗余实现**。本文记录二者的定位、边界与部署关系，避免误判为重复模块。

## 一图速览

| 维度 | `dataagent`（主链路） | `opendataagent`（并行路线） |
|------|----------------------|-----------------------------|
| 语言/栈 | Python · FastAPI · Pydantic · Claude Agent SDK | Go · agentsdk-go · Vue 3 控制台 |
| 定位 | 当前主门户集成的 NL2SQL 智能问数后端 | 独立的 Agent 平台（独立部署、独立 MySQL） |
| 部署 | 根 `deploy/docker-compose.{dev,prod}.yml` | `opendataagent/deploy/docker-compose.yml`（独立栈） |
| 前端入口 | 主前端 `/intelligent-query` 内嵌 Widget | 自带 `opendataagent/web` 控制台 |
| 默认端口 | backend 8900 / frontend 8901 / sandbox 8910 / odw-mcp-server 8801 | web 18080 / api 18900 / mysql 13306 |
| 技能来源 | 运行时挂载共享 `skills/` | 运行时挂载共享 `skills/`（只读） |

## 边界要点

- 根 `deploy/` 编排**只覆盖主门户与现有智能问数链路**，明确不包含 `opendataagent`；后者由其自身 compose 独立部署、独立生命周期。
- 二者均通过**运行时挂载** `skills/`（`generic/`、`platform/` 等）复用技能内容，技能**不打包进任一镜像**，因此不存在代码重复。
- 二者**无相互代码依赖**：`backend` 不依赖任一 Agent 代码；两套 Agent 之间也互不依赖，只通过后端 `/api/v1/ai/*` 等 HTTP 接口与共享技能挂载协作。

## 为什么并行而非二选一

- `dataagent` 走 Python + Claude Agent SDK + 系统提示词/技能方法论，是当前生产主链路。
- `opendataagent` 走 Go SDK 路线，作为独立可演进的 Agent 平台并行验证。
- 两条路线刻意解耦，便于各自独立迭代与部署；如未来收敛到单一路线，应先更新本文与根 `deploy/README.md` 再调整编排。

## 相关文档

- 部署边界：`deploy/README.md`、`opendataagent/README.md`
- 智能问数模块规则：仓库根 `AGENTS.md`（Intelligent Query module rules）
- 总体架构：`docs/handbook/architecture.md`
