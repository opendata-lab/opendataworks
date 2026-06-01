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
  <a href="https://github.com/opendata-lab/opendataworks/releases"><img src="https://img.shields.io/github/downloads/opendata-lab/opendataworks/total" alt="Downloads"></a>
  <a href="https://deepwiki.com/opendata-lab/opendataworks"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <a href="https://opendataworkshq.slack.com/"><img src="https://img.shields.io/badge/Slack-OpenDataWorks-4A154B?logo=slack&logoColor=white" alt="Slack Community"></a>
</p>

**一站式数据任务管理、智能问数与数据血缘可视化平台。**

简体中文 | [English](README_en.md)

[项目主页](https://opendataworks.vercel.app/) · [快速开始](https://opendataworks.vercel.app/guide/quick-start.html) · [功能特性](https://opendataworks.vercel.app/guide/features.html) · [架构设计](https://opendataworks.vercel.app/architecture/overview.html) · [配置说明](https://opendataworks.vercel.app/guide/configuration.html) · [贡献指南](https://opendataworks.vercel.app/guide/contribution.html) · [Slack 社区](https://opendataworkshq.slack.com/)

</div>

---

## 项目简介

OpenDataWorks 是一个面向数据平台团队的开源统一数据门户，提供元数据管理、工作流编排、血缘分析和自然语言智能问数能力。

项目提供可直接部署的全栈实现：Java 后端、Vue 前端、用于智能问数的 Python DataAgent 服务，以及覆盖本地开发和生产部署 of Docker Compose 配置。

## 核心价值

- **统一数据资产管理**：集中管理表元数据、数据域、业务域和分层数据模型。
- **工作流编排**：可视化配置批处理和流处理任务，并深度集成 DolphinScheduler。
- **数据血缘分析**：自动解析 SQL 血缘，在交互式图谱中查看上下游链路。
- **智能问数**：在主门户内通过自然语言生成 SQL、执行分析并查看结果。
- **快速部署**：通过现有 Docker Compose 一次性拉起前端、后端、DataAgent Backend、Redis、MySQL 和 Portal MCP。

## 功能亮点

- ODS、DWD、DIM、DWS、ADS 分层元数据管理
- 工作流创建、发布、调度和执行监控
- SQL 与 Shell 任务支持
- 基于 ECharts 的数据血缘可视化
- Data Studio：目录浏览、SQL 编辑、表级元数据联动分析
- 内置 NL2SQL 智能问数入口
- 运行日志、历史记录与统计分析

## 项目演示

[https://opendataworks-demo.vercel.app](https://opendataworks-demo.vercel.app)

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

## Docker 部署

### 开发环境快速启动

如果希望一次性在本机拉起完整环境（前端、后端、DataAgent Backend、Redis、MySQL、Portal MCP），可使用开发环境 Compose：

```bash
# 1. 准备配置
cp deploy/.env.example deploy/.env

# 2. 拉取最新镜像
docker compose -f deploy/docker-compose.dev.yml pull

# 3. 启动服务
docker compose -f deploy/docker-compose.dev.yml up -d

# 访问地址
# 前端: http://localhost:8081
# 后端: http://localhost:8080/api
# DataAgent Backend: http://localhost:8900
# Portal MCP: http://localhost:8801/mcp
```

### 生产环境与离线部署

请参考 [部署文档](deploy/README.md) 获取生产环境部署和离线包制作指南。

## 快速开始

请参考 [快速开始指南](https://opendataworks.vercel.app/guide/quick-start.html) 部署并启动 OpenDataWorks。

## 文档

详细文档请查看官方文档网站：**https://opendataworks.vercel.app/**

- [快速开始](https://opendataworks.vercel.app/guide/quick-start.html)
- [架构设计](https://opendataworks.vercel.app/architecture/overview.html)
- [配置说明](https://opendataworks.vercel.app/guide/configuration.html)
- [常见问题](https://opendataworks.vercel.app/guide/faq.html)

## 社区

- 加入 [OpenDataWorks Slack 社区](https://opendataworkshq.slack.com/)，交流使用经验、部署问题、路线图想法和贡献计划。
- 如需反馈 Bug、提出功能建议或改进文档，请提交 [GitHub Issue](https://github.com/opendata-lab/opendataworks/issues)。

## 贡献

欢迎提交 PR 或 Issue。开始前请阅读 [贡献指南](https://opendataworks.vercel.app/guide/contribution.html)。

## 许可证

本项目采用 [GNU General Public License v3.0 only](LICENSE) 开源协议。
