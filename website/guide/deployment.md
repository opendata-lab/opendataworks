# 安装部署

本指南详细介绍 OpenDataWorks 的部署流程，包括开发环境快速启动、生产环境部署以及离线部署方案。

## 环境要求

- **Docker**: 20.10+
- **Docker Compose**: 2.0+
- **硬件资源**: 建议 4 核 8G 以上

## 目录结构

```
deploy/
├── .env.example              # 环境变量配置示例
├── docker-compose.dev.yml    # 开发环境编排
├── docker-compose.prod.yml   # 生产环境编排
├── docker-images/            # 离线镜像存储目录
└── README.md
```

## 开发环境

```bash
# 准备配置文件
cp deploy/.env.example deploy/.env

# 拉取镜像并启动
docker compose -f deploy/docker-compose.dev.yml pull
docker compose -f deploy/docker-compose.dev.yml up -d
```

服务启动后：

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost:8081 |
| 后端 API | http://localhost:8080/api |
| DataAgent Backend | http://localhost:8900 |
| Portal MCP | http://localhost:8801/mcp |

## 生产环境

### 步骤

1. **配置环境变量**

```bash
cp deploy/.env.example deploy/.env
vim deploy/.env  # 修改数据库密码、端口等
```

2. **启动服务**

```bash
bash scripts/start.sh
```

3. **验证**

- 前端: `http://<服务器IP>:8081`
- 后端: `http://<服务器IP>:8080/api`
- DataAgent: `http://<服务器IP>:8900/api/v1/nl2sql/health`
- Portal MCP: `http://<服务器IP>:8801/health`
