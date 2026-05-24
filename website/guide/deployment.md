# 安装部署

本指南详细介绍 OpenDataWorks 的部署流程，包括基于 Docker 的快速部署（开发环境与生产环境）以及基于本地源码的编译运行指南（供二次开发使用）。

---

## 1. 基于容器的快速部署 (推荐)

### 环境要求
* **Docker**: 20.10+
* **Docker Compose**: 2.0+
* **硬件资源**: 建议 4 核 8G 以上

### 目录结构
所有编排文件均维护在仓库根目录的 `deploy/` 文件夹中：
```
deploy/
├── .env.example              # 环境变量配置示例
├── docker-compose.dev.yml    # 开发环境编排（包含本地 MySQL 映射端口 3316 等）
├── docker-compose.prod.yml   # 生产环境编排
├── docker-images/            # 离线镜像存储目录
└── README.md
```

### 开发环境容器启动
```bash
# 准备配置文件
cp deploy/.env.example deploy/.env

# 启动服务
docker compose -f deploy/docker-compose.dev.yml up -d
```
启动后访问地址参见 **[快速开始](file:///Users/guoruping/project/bigdata/opendataworks/website/guide/quick-start.md)**。

### 生产环境容器启动
1. **配置环境变量**：
   ```bash
   cp deploy/.env.example deploy/.env
   vim deploy/.env  # 更改默认的数据库 Root 密码、外部端口及挂载存储路径
   ```
2. **通过脚本运行生产编排**：
   ```bash
   bash scripts/start.sh
   ```
3. **服务状态验证**：
   * 前端 UI: `http://<服务器IP>:8081`
   * 后端 API: `http://<服务器IP>:8080/api`
   * DataAgent: `http://<服务器IP>:8900/api/v1/nl2sql/health`
   * Portal MCP: `http://<服务器IP>:8801/health`

---

## 2. 基于源码的本地编译启动 (开发专用)

如果您需要对项目进行定制开发或排查源码问题，请按照以下步骤配置本地开发环境：

### 环境要求
* **操作系统**: Linux / macOS / Windows
* **Java SDK**: JDK 8 或更高版本
* **构建工具**: Maven 3.6+
* **Node.js 运行时**: 20.19.0+（建议使用 NVM 配合项目根目录的 `.nvmrc`）
* **MySQL 关系型库**: 8.0+

---

### 第一步：准备本地数据库
OpenDataWorks 使用 Flyway 管理表结构。您只需在本地 MySQL 中创建对应的数据库和数据库账号权限，服务启动时将自动初始化所有表结构和初始记录。

登录本地 MySQL 并运行：
```sql
-- 1. 创建业务数据库
CREATE DATABASE opendataworks
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 2. 创建专用账号
CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';

-- 3. 创建 DataAgent 辅助数据库 (用于会话存储)
CREATE DATABASE dataagent
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
GRANT ALL PRIVILEGES ON dataagent.* TO 'opendataworks'@'%';

FLUSH PRIVILEGES;
```

---

### 第二步：启动 DolphinScheduler（可选）
如果您需要在本地调试工作流调度，请先启动本地 DolphinScheduler 单机或集群实例。
* 参考指南：[DolphinScheduler 官方部署文档](https://dolphinscheduler.apache.org/zh-cn/docs/3.2.0/guide/installation/standalone)
* 启动后，请在平台管理后台配置正确的 DolphinScheduler API URL 以及 Token 凭证。

---

### 第三步：编译并启动 Java 后端
后端为主 Spring Boot 服务，负责处理元数据、 lineage 拓扑及调度逻辑协调。

```bash
# 在项目根目录下，执行 Maven 编译打包
mvn -f pom.xml -pl backend -am clean install

# 运行后端 Spring Boot 实例
mvn -f pom.xml -pl backend -am spring-boot:run
```
启动成功后，后端服务将运行在 `http://localhost:8080/api`。

---

### 第四步：启动前端应用
前端基于 Vue 3 + Vite，请在运行命令前确保通过 `.nvmrc` 切换到兼容的 Node.js 运行时版本。

```bash
cd frontend

# 加载 NVM 并切换至项目推荐的 Node 版本
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use || nvm install

# 安装 npm 依赖项
npm install

# 启动本地热更新开发服务器
npm run dev
```
启动成功后，您可直接访问 [http://localhost:3000](http://localhost:3000) 进入本地开发页面。前端 Vite 配置文件已配置好 proxy，会自动将 `/api/*` 请求转发到后端的 `localhost:8080` 服务，以及将 `/api/v1/nl2sql/*` 转发到 `localhost:8900` 的 DataAgent 后端。
