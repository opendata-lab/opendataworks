<!--
  README 候选版本 —— 版本 3：「彻底重写」
  全新叙事：更强的价值主张、问题切入、目标用户、更丰富的功能故事、
  截图画廊，以及干脆利落的三步快速开始。
  Docker Compose 指令与真实仓库布局一致（仓库根目录下的 deploy/docker-compose.dev.yml）。
  选定该版本后，本文件将替换 /README_zh-CN.md。
-->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/opendataworks-icon-dark.svg">
    <img src="frontend/public/opendataworks-icon-light.svg" alt="OpenDataWorks" width="160">
  </picture>
</p>

<h1 align="center">OpenDataWorks</h1>

<p align="center">
  <b>开源、一站式数据门户：把元数据、工作流、血缘与自然语言问数，整合进同一个可部署的平台。</b>
</p>

<p align="center">
  <a href="https://github.com/opendata-lab/opendataworks/stargazers"><img src="https://img.shields.io/github/stars/opendata-lab/opendataworks?style=flat" alt="Stars"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/network/members"><img src="https://img.shields.io/github/forks/opendata-lab/opendataworks?style=flat" alt="Forks"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/issues"><img src="https://img.shields.io/github/issues/opendata-lab/opendataworks" alt="Issues"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/blob/main/LICENSE"><img src="https://img.shields.io/github/license/opendata-lab/opendataworks" alt="License"/></a>
  <a href="https://deepwiki.com/opendata-lab/opendataworks"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <a href="https://opendataworkshq.slack.com/"><img src="https://img.shields.io/badge/Slack-加入-4A154B?logo=slack&logoColor=white" alt="Slack"/></a>
</p>

<p align="center">
  <a href="README.md">English</a> | 简体中文
</p>

<p align="center">
  <a href="https://opendataworks-demo.vercel.app/"><b>🚀 在线 Demo</b></a> ·
  <a href="https://opendataworks.vercel.app/"><b>📖 文档</b></a> ·
  <a href="https://opendataworks.vercel.app/guide/quick-start.html"><b>⚡ 快速开始</b></a> ·
  <a href="https://opendataworkshq.slack.com/"><b>💬 Slack</b></a>
</p>

---

## 痛点

现代数据团队往往要拼接元数据目录、调度系统、血缘工具和 BI/查询层——每个工具都有各自的登录、模型和学习成本。上下文在工具之间不断丢失，血缘逐渐过期，连「这个指标到底从哪来的？」这种简单问题都可能要排查一下午。

## OpenDataWorks 做什么

OpenDataWorks 把这一整套能力收敛进**同一个门户**。在一个应用里完成：编目你的表、编排填充这些表的任务、查看连接它们的血缘，并用自然语言向数据提问。

```
        ┌────────────────────────────────────────────────┐
        │                OpenDataWorks 门户               │
        │                                                  │
        │   数据资产    任务调度    数据血缘    Data Studio  │
        │                                                  │
        │              💬  用自然语言提问                   │
        └────────────────────────────────────────────────┘
           元数据  ·  编排调度  ·  数据血缘  ·  NL2SQL 问数
```

它以可部署的全栈应用形态交付——Vue 3 前端、Spring Boot 后端、用于 NL2SQL 的 Python DataAgent，并通过 Docker Compose 将 MySQL、Redis、DolphinScheduler 与 Portal MCP 串联在一起。

## 适合谁

- **数据平台 / 基础设施团队**：无需购买五套 SaaS，即可搭建内部数据门户。
- **数据工程师**：希望元数据、调度与血缘彼此相邻、协同工作。
- **分析师与业务用户**：不想手写 SQL，也能从数据中得到答案。

## 核心能力

### 🗂️ 统一数据资产
按完整数仓分层模型管理表元数据——**ODS → DWD → DIM → DWS → ADS**——支持可视化建表、物理 DDL 同步、表级软删除回收站，以及存储/访问热度分析。

### ⚡ 工作流编排
可视化编写批处理与流处理任务，将 SQL、Shell 任务编排成 DAG，再发布、调度、监控执行——深度集成 **Apache DolphinScheduler**。

### 🔗 自动数据血缘
解析 SQL 自动推导血缘，在基于 ECharts 的交互式力导向图谱中查看上下游依赖。

### 🤖 智能问数（NL2SQL）
用自然语言提问，内置 **DataAgent** 生成 SQL、执行分析并流式返回结果与图表——全部基于你真实的元数据。

### 🧪 Data Studio
一个工作区即可浏览目录、编写并运行 SQL、在上下文中查看表级元数据。

### 📊 运维观测
运行日志、执行历史与运营统计，让平台始终可观测。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| **前端** | Vue 3 · Vite 5 · Pinia · Vue Router · Element Plus · ECharts · Vue Flow · CodeMirror |
| **后端** | Java 8 · Spring Boot 2.7（MVC + WebFlux）· MyBatis-Plus · MySQL 8 · Flyway · JSqlParser |
| **DataAgent** | Python · FastAPI · Pydantic · PyMySQL · Alembic（NL2SQL 运行时） |
| **编排调度** | Apache DolphinScheduler |
| **基础设施** | MySQL 8 · Redis · Docker Compose · Portal MCP |

## 界面预览

| 任务调度 | 数据血缘 | Data Studio |
| :---: | :---: | :---: |
| ![任务调度](website/public/readme-workflows.png) | ![数据血缘](website/public/readme-lineage.png) | ![Data Studio](website/public/readme-datastudio.png) |

> 想直接上手点一点？试试 **[在线 Demo](https://opendataworks-demo.vercel.app/)**。

## 三步开始

你需要 **Docker 20.10+** 与 **Docker Compose v2**。开发环境配置会一次性拉起前端、后端、DataAgent、Redis、MySQL 与 Portal MCP。

```bash
# 1. 克隆仓库
git clone https://github.com/opendata-lab/opendataworks.git
cd opendataworks

# 2. 创建配置
cp deploy/.env.example deploy/.env

# 3. 启动
docker compose -f deploy/docker-compose.dev.yml up -d
```

随后访问 **http://localhost:8081**。

| 服务 | 地址 |
| --- | --- |
| 前端 | http://localhost:8081 |
| 后端 API | http://localhost:8080/api |
| DataAgent | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |

如需启用自然语言问数，请配置模型 Provider，详见[配置说明](https://opendataworks.vercel.app/guide/configuration.html)。生产与离线部署请使用 `deploy/docker-compose.prod.yml`，并参考[部署文档](https://opendataworks.vercel.app/guide/deployment.html)。

## 文档

全部内容都在 **[opendataworks.vercel.app](https://opendataworks.vercel.app/)**：

- [快速开始](https://opendataworks.vercel.app/guide/quick-start.html) —— 你的第一次部署
- [配置说明](https://opendataworks.vercel.app/guide/configuration.html) —— 环境变量与 Provider
- [架构设计](https://opendataworks.vercel.app/architecture/overview.html) —— 各部分如何协同
- [智能查询](https://opendataworks.vercel.app/guide/intelligent-query.html) —— 配置并使用 NL2SQL
- [常见问题](https://opendataworks.vercel.app/guide/faq.html)

## 社区与贡献

OpenDataWorks 在开放中构建，欢迎一切形式的贡献——代码、文档、Bug 反馈与想法。

- 💬 在 [Slack 社区](https://opendataworkshq.slack.com/)讨论
- 🐛 通过 [GitHub Issues](https://github.com/opendata-lab/opendataworks/issues) 反馈 Bug、提出需求
- 🔧 贡献前请先阅读[贡献指南](https://opendataworks.vercel.app/guide/contribution.html)

如果 OpenDataWorks 对你有帮助，欢迎点一个 ⭐ —— 这能帮助更多人发现这个项目。

## 许可证

本项目采用 [GNU General Public License v3.0 only](LICENSE) 开源协议发布。
