<!--
  README CANDIDATE — Version 3: "Full rewrite"
  A from-scratch narrative: stronger value proposition, problem framing,
  "who it's for", richer feature story, screenshot gallery, and a punchy
  3-step quick start. Compose instructions match the real repo layout
  (deploy/docker-compose.dev.yml from the repo root).
  Once you pick this version, this file is promoted to /README.md.
-->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="frontend/public/opendataworks-icon-dark.svg">
    <img src="frontend/public/opendataworks-icon-light.svg" alt="OpenDataWorks" width="160">
  </picture>
</p>

<h1 align="center">OpenDataWorks</h1>

<p align="center">
  <b>The open-source data portal that unifies metadata, workflows, lineage, and natural-language query — in one deployable platform.</b>
</p>

<p align="center">
  <a href="https://github.com/opendata-lab/opendataworks/stargazers"><img src="https://img.shields.io/github/stars/opendata-lab/opendataworks?style=flat" alt="Stars"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/network/members"><img src="https://img.shields.io/github/forks/opendata-lab/opendataworks?style=flat" alt="Forks"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/issues"><img src="https://img.shields.io/github/issues/opendata-lab/opendataworks" alt="Issues"/></a>
  <a href="https://github.com/opendata-lab/opendataworks/blob/main/LICENSE"><img src="https://img.shields.io/github/license/opendata-lab/opendataworks" alt="License"/></a>
  <a href="https://deepwiki.com/opendata-lab/opendataworks"><img src="https://deepwiki.com/badge.svg" alt="Ask DeepWiki"></a>
  <a href="https://opendataworkshq.slack.com/"><img src="https://img.shields.io/badge/Slack-Join-4A154B?logo=slack&logoColor=white" alt="Slack"/></a>
</p>

<p align="center">
  English | <a href="README_zh-CN.md">简体中文</a>
</p>

<p align="center">
  <a href="https://opendataworks-demo.vercel.app/"><b>🚀 Live Demo</b></a> ·
  <a href="https://opendataworks.vercel.app/"><b>📖 Documentation</b></a> ·
  <a href="https://opendataworks.vercel.app/guide/quick-start.html"><b>⚡ Quick Start</b></a> ·
  <a href="https://opendataworkshq.slack.com/"><b>💬 Slack</b></a>
</p>

---

## The problem

Modern data teams stitch together a metadata catalog, a scheduler, a lineage tool, and a BI/query layer — each with its own login, model, and learning curve. Context is lost between tools, lineage drifts out of date, and answering a simple "where does this number come from?" turns into an afternoon of spelunking.

## What OpenDataWorks does

OpenDataWorks collapses that stack into **one portal**. Catalog your tables, orchestrate the jobs that populate them, see the lineage that connects them, and ask questions of them in plain language — without leaving the application.

```
        ┌────────────────────────────────────────────────┐
        │                OpenDataWorks Portal             │
        │                                                  │
        │   Data Assets   Workflows   Lineage   Data Studio│
        │                                                  │
        │              💬  Ask in natural language          │
        └────────────────────────────────────────────────┘
            metadata  ·  orchestration  ·  lineage  ·  NL2SQL
```

It ships as a deployable full-stack application — Vue 3 frontend, Spring Boot backend, a Python DataAgent for NL2SQL, with MySQL, Redis, DolphinScheduler, and Portal MCP wired together via Docker Compose.

## Who it's for

- **Data platform & infra teams** standing up an internal data portal without buying five SaaS products.
- **Data engineers** who want metadata, scheduling, and lineage to live next to each other.
- **Analysts & business users** who want answers from data without writing SQL by hand.

## Core capabilities

### 🗂️ Unified data assets
Organize table metadata across the full warehouse model — **ODS → DWD → DIM → DWS → ADS** — with visual table design, physical DDL sync, a table-level soft-delete recycle bin, and storage/access-heat analytics.

### ⚡ Workflow orchestration
Author batch and streaming jobs visually, compose SQL and Shell tasks into DAGs, then publish, schedule, and monitor execution — backed by deep **Apache DolphinScheduler** integration.

### 🔗 Automatic data lineage
Parse SQL to derive lineage automatically and explore upstream/downstream dependencies in an interactive, force-directed graph rendered with ECharts.

### 🤖 Intelligent query (NL2SQL)
Ask a question in natural language; the built-in **DataAgent** generates SQL, runs the analysis, and streams back results and charts — grounded in your real metadata.

### 🧪 Data Studio
A single workspace to browse catalogs, write and run SQL, and inspect table-level metadata in context.

### 📊 Operations
Runtime logs, execution history, and operational statistics keep the platform observable.

## Tech stack

| Layer | Built with |
| --- | --- |
| **Frontend** | Vue 3 · Vite 5 · Pinia · Vue Router · Element Plus · ECharts · Vue Flow · CodeMirror |
| **Backend** | Java 8 · Spring Boot 2.7 (MVC + WebFlux) · MyBatis-Plus · MySQL 8 · Flyway · JSqlParser |
| **DataAgent** | Python · FastAPI · Pydantic · PyMySQL · Alembic (NL2SQL runtime) |
| **Orchestration** | Apache DolphinScheduler |
| **Infrastructure** | MySQL 8 · Redis · Docker Compose · Portal MCP |

## See it in action

| Workflow Orchestration | Data Lineage | Data Studio |
| :---: | :---: | :---: |
| ![Workflows](website/public/readme-workflows.png) | ![Lineage](website/public/readme-lineage.png) | ![Data Studio](website/public/readme-datastudio.png) |

> Prefer to click around? Try the **[live demo](https://opendataworks-demo.vercel.app/)**.

## Get started in 3 steps

You'll need **Docker 20.10+** and **Docker Compose v2**. The dev profile brings up the frontend, backend, DataAgent, Redis, MySQL, and Portal MCP together.

```bash
# 1. Clone the repository
git clone https://github.com/opendata-lab/opendataworks.git
cd opendataworks

# 2. Create your config
cp deploy/.env.example deploy/.env

# 3. Launch
docker compose -f deploy/docker-compose.dev.yml up -d
```

Then open **http://localhost:8081**.

| Service | URL |
| --- | --- |
| Frontend | http://localhost:8081 |
| Backend API | http://localhost:8080/api |
| DataAgent | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |

To enable natural-language query, configure a model provider — see the [configuration guide](https://opendataworks.vercel.app/guide/configuration.html). For production and offline deployment, use `deploy/docker-compose.prod.yml` and follow the [deployment guide](https://opendataworks.vercel.app/guide/deployment.html).

## Documentation

Everything lives at **[opendataworks.vercel.app](https://opendataworks.vercel.app/)**:

- [Quick Start](https://opendataworks.vercel.app/guide/quick-start.html) — your first deployment
- [Configuration](https://opendataworks.vercel.app/guide/configuration.html) — environment variables and providers
- [Architecture](https://opendataworks.vercel.app/architecture/overview.html) — how the pieces fit together
- [Intelligent Query](https://opendataworks.vercel.app/guide/intelligent-query.html) — set up and use NL2SQL
- [FAQ](https://opendataworks.vercel.app/guide/faq.html)

## Community & contributing

OpenDataWorks is built in the open and contributions are welcome — code, docs, bug reports, and ideas alike.

- 💬 **Discuss** in the [Slack community](https://opendataworkshq.slack.com/)
- 🐛 **Report** bugs and request features via [GitHub Issues](https://github.com/opendata-lab/opendataworks/issues)
- 🔧 **Contribute** by reading the [contribution guide](https://opendataworks.vercel.app/guide/contribution.html) first

If OpenDataWorks is useful to you, please consider giving it a ⭐ — it helps others find the project.

## License

Released under the [GNU General Public License v3.0 only](LICENSE).
