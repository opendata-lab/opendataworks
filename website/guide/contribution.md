# 贡献指南

我们欢迎任何形式的贡献，包括但不限于：

- 报告 Bug
- 提交功能请求
- 改进文档
- 提交代码

## 仓库结构

| 目录 | 说明 |
| --- | --- |
| `backend/` | Java + Spring Boot 主后端（元数据、工作流、血缘、平台 API） |
| `frontend/` | Vue 3 + Vite 前端门户 |
| `dataagent/dataagent-backend/` | FastAPI 智能问数（NL2SQL）后端 |
| `deploy/` | Docker Compose 与部署资产 |
| `website/` | 本文档站点（VitePress） |

## 本地开发环境

推荐先用 Docker Compose 拉起依赖（MySQL、Redis 等），再单独运行你正在修改的模块。完整一键启动见[快速开始](/guide/quick-start)。

### 前端

前端使用 `nvm` 管理 Node 版本。运行任何前端命令前先执行 `nvm use`：

```bash
cd frontend
nvm use            # 按 .nvmrc 切换 Node 版本
npm install
npm run dev        # 本地开发
npm run build      # 构建
```

### 后端

后端为 Java 8 + Spring Boot 2.7（Maven 工程）：

```bash
cd backend
mvn clean package         # 编译打包
mvn spring-boot:run       # 本地运行
```

数据库结构由 Flyway 在启动时自动迁移，无需手动建表。

### DataAgent

DataAgent 为 Python（要求 3.10+），建议使用独立虚拟环境：

```bash
cd dataagent/dataagent-backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
alembic upgrade head       # 迁移会话库
uvicorn main:app           # 启动（任务协调器在 main.py 内启动，无需单独运行 worker）
```

### 文档站点

```bash
cd website
npm install
npm run dev        # 本地预览文档
npm run build      # 构建静态站点
```

## 提交规范

- 遵循 [Conventional Commits](https://www.conventionalcommits.org/) 规范。
- 保持改动聚焦、低风险，优先小步提交。
- 改动公共 API、数据模型、部署行为时，请在同一改动中同步更新相关文档。
- 提交前运行与改动范围相关的**最小化**测试或构建检查：
  - 前端：先 `nvm use`，再运行最小相关的 build/test/lint。
  - 后端：运行触及范围的最小测试或编译检查。
  - DataAgent：优先针对触及模块的 `pytest`。
- 代码风格遵循各模块既有约定。

## Pull Request 流程

1. 从 `main` 创建特性分支。
2. 完成改动并通过本地验证。
3. 提交 PR，清晰描述改动动机与影响范围。
4. 通过评审后合并。

感谢你的贡献！如果项目对你有帮助，欢迎点一个 ⭐。
