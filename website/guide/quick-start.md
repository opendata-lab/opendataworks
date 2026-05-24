# 快速开始

OpenDataWorks 推荐使用 Docker Compose 进行本地一键启动。本指南将帮助您在 1 分钟内跑通整个一站式智能数据工作台。

---

## 1. 快速启动

### 准备环境
确保您的系统已安装了以下基础依赖：
* **Docker**: 20.10+
* **Docker Compose**: 2.0+

### 一键部署运行

1. **克隆项目并进入根目录**：
   ```bash
   git clone https://github.com/opendata-lab/opendataworks.git
   cd opendataworks
   ```

2. **准备配置文件**：
   ```bash
   cp deploy/.env.example deploy/.env
   ```

3. **拉取镜像并启动**：
   ```bash
   # 拉取最新的数据堆栈镜像（可选）
   docker compose -f deploy/docker-compose.dev.yml pull
   
   # 后台启动所有容器服务
   docker compose -f deploy/docker-compose.dev.yml up -d
   ```

---

## 2. 访问应用

服务全部启动就绪后，打开浏览器直接访问：

* **前端 Web UI**: [http://localhost:8081](http://localhost:8081)
* **后端 API**: `http://localhost:8080/api`
* **智能查询 DataAgent**: `http://localhost:8900`
* **MySQL 物理库**: `127.0.0.1:3316`（用户 `dataagent`/`opendataworks`）

::: info 数据库自动初始化
首次启动时，MySQL 容器会自动执行初始化脚本，创建 `opendataworks` 与 `dataagent` 数据库与对应的服务用户。后端 Spring Boot 首次加载时会自动运行 Flyway 生成表结构，无需任何手动导数操作。
:::

---

## 3. 功能验证 (Smoke Test)

启动后，您可以进行以下快速验证以确保服务链路全线畅通：

1. 浏览器打开 [http://localhost:8081](http://localhost:8081)，进入系统。
2. 导航到 **「智能查询」** 模块。
3. 新建一个会话，在对话框中输入问题：`你好，请直接回复 smoke-ok。`
4. 看到 AI 助手流式返回回复后，说明 DataAgent 容器、Redis 异步任务队列、FastAPI 与前端 SSE 交互链路完全正常。

---

## 4. 深入阅读

如果您需要了解如何通过源码在本地启动开发环境，或者在生产环境下部署，请参考以下指南：

* **本地源码编译与生产环境部署**：参见 **[安装部署](file:///Users/guoruping/project/bigdata/opendataworks/website/guide/deployment.md)**。
* **自定义配置属性详解**：参见 **[配置说明](file:///Users/guoruping/project/bigdata/opendataworks/website/guide/configuration.md)**。
