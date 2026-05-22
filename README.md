# OpenDataWorks

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/opendataworks-icon-dark.svg">
    <img src="frontend/public/opendataworks-icon-light.svg" alt="OpenDataWorks icon" width="180">
  </picture>
</p>

<div align="center">

<p align="center">
  <a href="https://github.com/MingkeVan/opendataworks/stargazers"><img src="https://img.shields.io/github/stars/MingkeVan/opendataworks" alt="Stars Badge"/></a>
  <a href="https://github.com/MingkeVan/opendataworks/network/members"><img src="https://img.shields.io/github/forks/MingkeVan/opendataworks" alt="Forks Badge"/></a>
  <a href="https://github.com/MingkeVan/opendataworks/pulls"><img src="https://img.shields.io/github/issues-pr/MingkeVan/opendataworks" alt="Pull Requests Badge"/></a>
  <a href="https://github.com/MingkeVan/opendataworks/issues"><img src="https://img.shields.io/github/issues/MingkeVan/opendataworks" alt="Issues Badge"/></a>
  <a href="https://github.com/MingkeVan/opendataworks/blob/main/LICENSE"><img src="https://img.shields.io/github/license/MingkeVan/opendataworks" alt="License Badge"/></a>
  <a href="https://github.com/MingkeVan/opendataworks/releases"><img src="https://img.shields.io/github/downloads/MingkeVan/opendataworks/total" alt="Downloads"></a>
  <a href="https://deepwiki.com/MingkeVan/opendataworks"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <a href="https://opendataworkshq.slack.com/"><img src="https://img.shields.io/badge/Slack-OpenDataWorks-4A154B?logo=slack&logoColor=white" alt="Slack Community"></a>
</p>

**A unified data portal for workflow orchestration, intelligent query, and data lineage visualization.**

English | [简体中文](README_zh-CN.md)

[Website](https://opendataworks.vercel.app/) · [Quick Start](https://opendataworks.vercel.app/guide/quick-start) · [Features](https://opendataworks.vercel.app/guide/features) · [Architecture](https://opendataworks.vercel.app/architecture/overview) · [Configuration](https://opendataworks.vercel.app/guide/configuration) · [Contributing](https://opendataworks.vercel.app/guide/contribution) · [Slack](https://opendataworkshq.slack.com/)

</div>

---

## Overview

OpenDataWorks is an open-source data platform portal for teams that need one place to manage metadata, orchestrate data workflows, analyze lineage, and ask data questions with natural language.

It brings the core pieces of a modern data platform into a deployable full-stack application: a Java backend, a Vue frontend, a Python DataAgent service for intelligent query, and Docker Compose assets for local and production environments.

## Why OpenDataWorks

- **Unified data asset management**: organize table metadata, data domains, business domains, and layered data models.
- **Workflow orchestration**: configure batch and streaming jobs visually, with deep DolphinScheduler integration.
- **Lineage analysis**: parse SQL lineage automatically and explore upstream/downstream relationships in an interactive graph.
- **Intelligent query**: use natural language to generate SQL, execute analysis, and review results from the main portal.
- **Ready to deploy**: run the frontend, backend, DataAgent backend, Redis, MySQL, and Portal MCP from the provided Docker Compose setup.

## Feature Highlights

- Metadata management for ODS, DWD, DIM, DWS, and ADS layers
- Workflow authoring, publishing, scheduling, and execution monitoring
- SQL and Shell task support
- Data lineage visualization with ECharts
- Data Studio with catalog browsing, SQL editing, and table-level metadata context
- Built-in NL2SQL intelligent-query entrypoint
- Runtime logs, execution history, and operational statistics

## Demo

[https://opendataworks-demo.vercel.app](https://opendataworks-demo.vercel.app)

## Screenshots

### Workflow Orchestration

![OpenDataWorks workflow orchestration screen](website/public/readme-workflows.png)

Manage workflow lists, publishing status, and common workflow actions.

### Data Lineage

![OpenDataWorks data lineage screen](website/public/readme-lineage.png)

Explore upstream and downstream table relationships around a selected table.

### Data Studio

![OpenDataWorks Data Studio screen](website/public/readme-datastudio.png)

Browse catalogs, write SQL, and inspect table metadata in one workspace.

## Docker Deployment

### Start the Development Environment

Use the development Docker Compose profile to start the frontend, backend, DataAgent backend, Redis, MySQL, and Portal MCP together:

```bash
# 1. Prepare configuration
cp deploy/.env.example deploy/.env

# 2. Pull the latest images
docker compose -f deploy/docker-compose.dev.yml pull

# 3. Start services
docker compose -f deploy/docker-compose.dev.yml up -d

# Access points
# Frontend: http://localhost:8081
# Backend: http://localhost:8080/api
# DataAgent Backend: http://localhost:8900
# Portal MCP: http://localhost:8801/mcp
```

### Production and Offline Deployment

See the [deployment guide](deploy/README.md) for production deployment and offline package instructions.

## Quick Start

Follow the [quick start guide](https://opendataworks.vercel.app/guide/quick-start) to deploy and run OpenDataWorks locally.

## Documentation

Full documentation is available at: **https://opendataworks.vercel.app/**

- [Quick Start](https://opendataworks.vercel.app/guide/quick-start)
- [Architecture](https://opendataworks.vercel.app/architecture/overview)
- [Configuration](https://opendataworks.vercel.app/guide/configuration)
- [FAQ](https://opendataworks.vercel.app/guide/faq)

## Community

- Join the [OpenDataWorks Slack community](https://opendataworkshq.slack.com/) to discuss usage, deployment, roadmap ideas, and contributions.
- Open a [GitHub Issue](https://github.com/opendata-lab/opendataworks/issues) for bugs, feature requests, or documentation feedback.

## Contributing

Contributions are welcome. Please read the [contribution guide](https://opendataworks.vercel.app/guide/contribution) before opening a pull request.

## License

OpenDataWorks is licensed under the [GNU General Public License v3.0 only](LICENSE).
