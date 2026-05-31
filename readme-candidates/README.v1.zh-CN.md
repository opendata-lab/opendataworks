<!--
  README 候选版本 —— 版本 1：「修正 + 打磨 + 重构」
  保留现有 README 的结构与风格，修正过期的 MingkeVan 组织名，
  并补充目录、架构图、技术栈表格与更顺畅的章节衔接。
  Docker Compose 指令与真实仓库布局一致（仓库根目录下的 deploy/docker-compose.dev.yml）。
  选定该版本后，本文件将替换 /README_zh-CN.md。
-->

# OpenDataWorks

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/opendataworks-icon-dark.svg">
    <img src="frontend/public/opendataworks-icon-light.svg" alt="OpenDataWorks 图标" width="180">
  </picture>
</p>

<div align="center">

<p align="center">
  <a href="https://github.com/opendata-lab/opendataworks/stargazers"><img src="https://img.shields.io/github/stars/opendata-lab/opendataworks" alt="Stars Badge"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/network/members"><img src="https://img.shields.io/github/forks/opendata-lab/opendataworks" alt="Forks Badge"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/pulls"><img src="https://img.shields.io/github/issues-pr/opendata-lab/opendataworks" alt="Pull Requests Badge"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/issues"><img src="https://img.shields.io/github/issues/opendata-lab/opendataworks" alt="Issues Badge"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/blob/main/LICENSE"><img src="https://img.shields.io/github/license/opendata-lab/opendataworks" alt="License Badge"/></a>
  <a href="https://deepwiki.com/opendata-lab/opendataworks"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <a href="https://opendataworkshq.slack.com/"><img src="https://img.shields.io/badge/Slack-OpenDataWorks-4A154B?logo=slack&logoColor=white" alt="Slack Community"></a>
</p>

**开源、一站式的数据门户：元数据管理、工作流编排、血缘分析与自然语言智能问数。**

[English](README.md) | 简体中文

[项目主页](https://opendataworks.vercel.app/) · [在线 Demo](https://opendataworks-demo.vercel.app/) · [快速开始](https://opendataworks.vercel.app/guide/quick-start.html) · [架构设计](https://opendataworks.vercel.app/architecture/overview.html) · [配置说明](https://opendataworks.vercel.app/guide/configuration.html) · [贡献指南](https://opendataworks.vercel.app/guide/contribution.html) · [Slack 社区](https://opendataworkshq.slack.com/)

</div>

---

## 目录

- [项目简介](#项目简介)
- [核心价值](#核心价值)
- [功能亮点](#功能亮点)
- [系统架构](#系统架构)
- [技术栈](#技术栈)
- [界面预览](#界面预览)
- [快速开始](#快速开始)
- [部署](#部署)
- [文档](#文档)
- [社区](#社区)
- [贡献](#贡献)
- [许可证](#许可证)

## 项目简介

OpenDataWorks 是一个面向数据平台团队的开源统一数据门户，让你在**同一个平台**完成元数据管理、工作流编排、血缘分析和自然语言智能问数。

它将现代数据平台的核心能力整合进一套可直接部署的全栈应用：

- **Java 后端**：提供元数据、工作流与血缘 API；
- **Vue 3 前端**：统一的数据门户界面；
- **Python DataAgent 服务**：驱动 NL2SQL 智能问数；
- **Docker Compose**：覆盖本地开发与生产部署。

## 核心价值

- **统一数据资产管理**：集中管理表元数据、数据域、业务域，以及分层数据模型（ODS → DWD → DIM → DWS → ADS）。
- **工作流编排**：可视化配置批处理与流处理任务，深度集成 DolphinScheduler。
- **数据血缘分析**：自动解析 SQL 血缘，在交互式图谱中查看上下游链路。
- **智能问数（NL2SQL）**：用自然语言生成 SQL、执行分析并直接在门户中查看结果。
- **开箱即部署**：通过现有 Docker Compose 同时拉起前端、后端、DataAgent、Redis、MySQL 和 Portal MCP。

## 功能亮点

- ODS、DWD、DIM、DWS、ADS 分层元数据管理，支持可视化建表与物理 DDL 同步
- 表级软删除回收站，带可配置的保留宽限期
- 数据资产的存储指标与访问热度分析
- 工作流创建、发布、调度与执行监控
- SQL 与 Shell 任务支持
- 基于 ECharts 的数据血缘可视化
- Data Studio：目录浏览、SQL 编辑、表级元数据联动分析
- 内置 NL2SQL 智能问数入口，支持流式结果
- 运行日志、历史记录与统计分析

## 系统架构

```
┌───────────── 前端 (Vue 3 + Vite) ──────────────────┐
│   数据资产 · 任务调度 · 数据血缘 · Data Studio       │
│              智能问数 (NL2SQL)                      │
└───────────────────────┬─────────────────────────────┘
                        │ REST / SSE
┌───────────────────────┴─────────────────────────────┐
│              后端 (Spring Boot 2.7)                  │
│    元数据 · 工作流 · 血缘 · 平台 API                 │
└──────┬───────────────────────┬───────────┬───────────┘
       │                       │           │
┌──────┴──────┐    ┌──────────┴───────┐ ┌──┴──────────────┐
│ DataAgent   │    │ DolphinScheduler │ │   MySQL · Redis │
│ (FastAPI,   │    │     (编排调度)    │ └─────────────────┘
│  NL2SQL)    │    └──────────────────┘
└─────────────┘
```

更多细节见[总体架构](https://opendataworks.vercel.app/architecture/overview.html)。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 前端 | Vue 3、Vite 5、Vue Router 4、Pinia、Element Plus、ECharts、Vue Flow、CodeMirror |
| 后端 | Java 8、Spring Boot 2.7（MVC + WebFlux）、MyBatis-Plus、MySQL 8、Flyway、Hutool、JSqlParser |
| DataAgent | Python、FastAPI、Pydantic、PyMySQL、Alembic、AnyIO（NL2SQL 运行时） |
| 编排调度 | Apache DolphinScheduler |
| 基础设施 | MySQL 8、Redis、Docker Compose、Portal MCP |

## 界面预览

### 任务调度

![OpenDataWorks 任务调度界面](website/public/readme-workflows.png)

工作流列表、发布状态与常用操作入口。

### 数据血缘

![OpenDataWorks 数据血缘界面](website/public/readme-lineage.png)

围绕中心表查看上下游链路与层级关系。

### Data Studio

![OpenDataWorks Data Studio 界面](website/public/readme-datastudio.png)

目录浏览、SQL 编辑与表级元数据联动分析。

## 快速开始

体验 OpenDataWorks 最快的方式是使用开发环境的 Docker Compose，它会一次性拉起前端、后端、DataAgent Backend、Redis、MySQL 和 Portal MCP。

**前置条件**：Docker 20.10+ 与 Docker Compose v2。

```bash
# 1. 克隆仓库
git clone https://github.com/opendata-lab/opendataworks.git
cd opendataworks

# 2. 准备配置
cp deploy/.env.example deploy/.env
# （可选）编辑 deploy/.env，配置模型 Provider Token、端口等

# 3. 拉取镜像并启动服务
docker compose -f deploy/docker-compose.dev.yml pull
docker compose -f deploy/docker-compose.dev.yml up -d
```

**访问地址**

| 服务 | 地址 |
| --- | --- |
| 前端 | http://localhost:8081 |
| 后端 API | http://localhost:8080/api |
| DataAgent Backend | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |
| MySQL | localhost:3316 |

> 首次启动时，MySQL 容器会自动初始化 `opendataworks` 与 `dataagent` 数据库，后端通过 Flyway 自动生成表结构，无需手动导数。

> 智能问数需要配置模型 Provider。启动前请在 `deploy/.env` 中完成 Provider 配置（详见[配置说明](https://opendataworks.vercel.app/guide/configuration.html)）。

## 部署

- **开发环境**：按上文使用 `deploy/docker-compose.dev.yml`。
- **生产 / 离线**：使用 `deploy/docker-compose.prod.yml`，生产部署与离线包制作请参考[部署文档](https://opendataworks.vercel.app/guide/deployment.html)。

## 文档

完整文档请访问 **https://opendataworks.vercel.app/**

- [快速开始](https://opendataworks.vercel.app/guide/quick-start.html)
- [配置说明](https://opendataworks.vercel.app/guide/configuration.html)
- [架构设计](https://opendataworks.vercel.app/architecture/overview.html)
- [智能查询](https://opendataworks.vercel.app/guide/intelligent-query.html)
- [常见问题](https://opendataworks.vercel.app/guide/faq.html)

## 社区

- 加入 [OpenDataWorks Slack 社区](https://opendataworkshq.slack.com/)，交流使用经验、部署问题、路线图想法和贡献计划。
- 如需反馈 Bug、提出功能建议或改进文档，请提交 [GitHub Issue](https://github.com/opendata-lab/opendataworks/issues)。

## 贡献

欢迎提交 PR 或 Issue！开始前请阅读[贡献指南](https://opendataworks.vercel.app/guide/contribution.html)。

## 许可证

本项目采用 [GNU General Public License v3.0 only](LICENSE) 开源协议。
