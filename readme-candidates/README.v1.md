<!--
  README CANDIDATE — Version 1: "Fix + polish + restructure"
  Keeps the existing README's structure and tone, corrects the stale
  `MingkeVan` org, and adds a TOC, an architecture diagram, a tech-stack
  table, and tighter section flow. Compose instructions match the real repo
  layout (deploy/docker-compose.dev.yml from the repo root).
  Once you pick this version, this file is promoted to /README.md.
-->

# OpenDataWorks

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/opendataworks-icon-dark.svg">
    <img src="frontend/public/opendataworks-icon-light.svg" alt="OpenDataWorks icon" width="180">
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

**A unified, open-source data portal for metadata management, workflow orchestration, lineage analysis, and natural-language query.**

English | [简体中文](README_zh-CN.md)

[Website](https://opendataworks.vercel.app/) · [Live Demo](https://opendataworks-demo.vercel.app/) · [Quick Start](https://opendataworks.vercel.app/guide/quick-start.html) · [Architecture](https://opendataworks.vercel.app/architecture/overview.html) · [Configuration](https://opendataworks.vercel.app/guide/configuration.html) · [Contributing](https://opendataworks.vercel.app/guide/contribution.html) · [Slack](https://opendataworkshq.slack.com/)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Why OpenDataWorks](#why-opendataworks)
- [Feature Highlights](#feature-highlights)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Screenshots](#screenshots)
- [Quick Start](#quick-start)
- [Deployment](#deployment)
- [Documentation](#documentation)
- [Community](#community)
- [Contributing](#contributing)
- [License](#license)

## Overview

OpenDataWorks is an open-source data platform portal for teams that need **one place** to manage metadata, orchestrate data workflows, analyze lineage, and ask data questions in natural language.

It brings the core pieces of a modern data platform into a single deployable full-stack application:

- a **Java backend** for metadata, workflow, and lineage APIs,
- a **Vue 3 frontend** for the unified portal,
- a **Python DataAgent service** that powers NL2SQL intelligent query, and
- **Docker Compose** assets for local development and production deployment.

## Why OpenDataWorks

- **Unified data asset management** — organize table metadata, data domains, business domains, and layered data models (ODS → DWD → DIM → DWS → ADS).
- **Workflow orchestration** — configure batch and streaming jobs visually, with deep DolphinScheduler integration.
- **Lineage analysis** — parse SQL lineage automatically and explore upstream/downstream relationships in an interactive graph.
- **Intelligent query (NL2SQL)** — turn natural-language questions into SQL, run the analysis, and review results directly in the portal.
- **Ready to deploy** — bring up the frontend, backend, DataAgent, Redis, MySQL, and Portal MCP from the provided Docker Compose setup.

## Feature Highlights

- Metadata management across ODS, DWD, DIM, DWS, and ADS layers, with visual table design and physical DDL sync
- Table-level soft-delete recycle bin with a configurable grace period
- Storage metrics and access-heat analytics for data assets
- Workflow authoring, publishing, scheduling, and execution monitoring
- SQL and Shell task support
- Data lineage visualization powered by ECharts
- Data Studio: catalog browsing, SQL editing, and table-level metadata context in one workspace
- Built-in NL2SQL intelligent-query entrypoint with streaming results
- Runtime logs, execution history, and operational statistics

## Architecture

```
┌───────────── Frontend (Vue 3 + Vite) ─────────────┐
│  Data Assets · Workflows · Lineage · Data Studio   │
│            Intelligent Query (NL2SQL)              │
└───────────────────────┬────────────────────────────┘
                        │ REST / SSE
┌───────────────────────┴────────────────────────────┐
│            Backend (Spring Boot 2.7)                │
│  Metadata · Workflow · Lineage · Platform APIs      │
└──────┬───────────────────────┬───────────┬──────────┘
       │                       │           │
┌──────┴──────┐    ┌──────────┴───────┐ ┌──┴─────────────┐
│ DataAgent   │    │ DolphinScheduler │ │  MySQL · Redis │
│ (FastAPI,   │    │  (orchestration) │ └────────────────┘
│  NL2SQL)    │    └──────────────────┘
└─────────────┘
```

See the [architecture overview](https://opendataworks.vercel.app/architecture/overview.html) for a detailed breakdown.

## Tech Stack

| Layer | Technologies |
| --- | --- |
| Frontend | Vue 3, Vite 5, Vue Router 4, Pinia, Element Plus, ECharts, Vue Flow, CodeMirror |
| Backend | Java 8, Spring Boot 2.7 (MVC + WebFlux), MyBatis-Plus, MySQL 8, Flyway, Hutool, JSqlParser |
| DataAgent | Python, FastAPI, Pydantic, PyMySQL, Alembic, AnyIO (NL2SQL runtime) |
| Orchestration | Apache DolphinScheduler |
| Infrastructure | MySQL 8, Redis, Docker Compose, Portal MCP |

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

## Quick Start

The fastest way to try OpenDataWorks is the development Docker Compose profile, which starts the frontend, backend, DataAgent backend, Redis, MySQL, and Portal MCP together.

**Prerequisites:** Docker 20.10+ and Docker Compose v2.

```bash
# 1. Clone the repository
git clone https://github.com/opendata-lab/opendataworks.git
cd opendataworks

# 2. Prepare configuration
cp deploy/.env.example deploy/.env
# (optional) edit deploy/.env to set your model provider token, ports, etc.

# 3. Pull images and start services
docker compose -f deploy/docker-compose.dev.yml pull
docker compose -f deploy/docker-compose.dev.yml up -d
```

**Access points**

| Service | URL |
| --- | --- |
| Frontend | http://localhost:8081 |
| Backend API | http://localhost:8080/api |
| DataAgent Backend | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |
| MySQL | localhost:3316 |

> On first start, the MySQL container initializes the `opendataworks` and `dataagent` databases automatically, and the backend runs Flyway migrations to create the schema — no manual import needed.

> Intelligent query requires a model provider. Configure your provider in `deploy/.env` before starting (see the [configuration guide](https://opendataworks.vercel.app/guide/configuration.html)).

## Deployment

- **Development**: use `deploy/docker-compose.dev.yml` as shown above.
- **Production / offline**: use `deploy/docker-compose.prod.yml`. See the [deployment guide](https://opendataworks.vercel.app/guide/deployment.html) for production and offline-package instructions.

## Documentation

Full documentation is available at **https://opendataworks.vercel.app/**

- [Quick Start](https://opendataworks.vercel.app/guide/quick-start.html)
- [Configuration](https://opendataworks.vercel.app/guide/configuration.html)
- [Architecture](https://opendataworks.vercel.app/architecture/overview.html)
- [Intelligent Query](https://opendataworks.vercel.app/guide/intelligent-query.html)
- [FAQ](https://opendataworks.vercel.app/guide/faq.html)

## Community

- Join the [OpenDataWorks Slack community](https://opendataworkshq.slack.com/) to discuss usage, deployment, roadmap ideas, and contributions.
- Open a [GitHub Issue](https://github.com/opendata-lab/opendataworks/issues) for bugs, feature requests, or documentation feedback.

## Contributing

Contributions are welcome! Please read the [contribution guide](https://opendataworks.vercel.app/guide/contribution.html) before opening a pull request.

## License

OpenDataWorks is licensed under the [GNU General Public License v3.0 only](LICENSE).
