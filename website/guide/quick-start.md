# 快速开始

## 环境要求

- **操作系统**: Linux / macOS / Windows
- **JDK**: 8 或更高版本
- **Maven**: 3.6+
- **Node.js**: 20.19.0+（建议使用仓库根目录 `.nvmrc`）
- **MySQL**: 8.0+
- **DolphinScheduler**: 3.2.0+（可选，用于任务调度）

## 安装步骤

### 1. 克隆项目

```bash
git clone https://github.com/opendata-lab/opendataworks.git
cd opendataworks
```

### 2. 准备数据库

项目集成了 Flyway，只需创建数据库和用户，无需手动导入表结构。

::: tip Docker 部署
如果使用 Docker Compose 部署，数据库和用户会自动创建，无需手动执行以下步骤。
:::

```sql
-- 登录 MySQL 后执行
CREATE DATABASE opendataworks
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE USER 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';
GRANT ALL PRIVILEGES ON opendataworks.* TO 'opendataworks'@'%';
FLUSH PRIVILEGES;
```

表结构和初始化数据将在后端服务首次启动时自动通过 Flyway 迁移。

### 3. 启动 DolphinScheduler（可选）

如果需要任务调度功能，请先安装并启动 DolphinScheduler。

参考官方文档：[DolphinScheduler 单机部署](https://dolphinscheduler.apache.org/zh-cn/docs/3.2.0/guide/installation/standalone)

### 4. 启动后端服务

```bash
# 编译并启动
mvn -f pom.xml -pl backend -am clean install
mvn -f pom.xml -pl backend -am spring-boot:run
```

服务启动后，Flyway 会自动执行迁移脚本，服务默认运行在 `http://localhost:8080`。

### 5. 启动前端应用

```bash
cd frontend

# 使用 nvm 切换 Node 版本
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use || nvm install

# 安装依赖并启动
npm install
npm run dev
```

应用将运行在 `http://localhost:3000`，当前默认开启匿名访问，本地开发无需登录。

### 6. 访问应用

打开浏览器访问：`http://localhost:3000`

## Docker 一键部署

使用 Docker Compose 部署时，数据库和用户会自动初始化。

```bash
# 1. 准备配置
cp deploy/.env.example deploy/.env

# 2. 拉取最新镜像
docker compose -f deploy/docker-compose.dev.yml pull

# 3. 启动服务
docker compose -f deploy/docker-compose.dev.yml up -d
```

启动后访问：

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost:8081 |
| 后端 API | http://localhost:8080/api |
| DataAgent | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |
| MySQL | 127.0.0.1:3316 |

::: info 数据库自动初始化
MySQL 容器首次启动时会自动执行初始化脚本，创建 `opendataworks` / `dataagent` 数据库与用户。表结构由后端 Flyway 自动创建，数据保存在 Docker volume 中，重启不丢失。
:::
