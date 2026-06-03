# OpenDataWorks 版本发布流程

本文档描述 OpenDataWorks 的版本发布流程，后续发版请按此流程执行。

## 发布前检查

- [ ] 确认所有计划功能已合并到 `main` 分支
- [ ] 本地 `main` 与远程同步
- [ ] 通过 CI 构建

---

## 一、明确版本号

按 [语义化版本](https://semver.org/lang/zh-CN/) 确定新版本号，例如 `1.0.0`：

- **主版本号（Major）**：不兼容的 API 变更
- **次版本号（Minor）**：向下兼容的功能新增
- **修订号（Patch）**：向下兼容的问题修正

---

## 二、修改版本号与配置

### 2.1 代码中的版本号

| 文件 | 修改内容 |
|------|----------|
| `pom.xml` | Reactor 聚合版本，如 `<version>1.0.0</version>` |
| `backend-agent-api/pom.xml` | Agent API 模块版本，如 `<version>1.0.0</version>` |
| `backend/pom.xml` | 后端模块版本，如 `<version>1.0.0</version>` |
| `frontend/package.json` | 前端版本，如 `"version": "1.0.0"` |
| `frontend/package-lock.json` | 锁文件顶层版本，如 `"version": "1.0.0"` |
| `dataagent/dataagent-frontend/package.json` | DataAgent 前端版本，如 `"version": "1.0.0"` |
| `dataagent/dataagent-frontend/package-lock.json` | DataAgent 前端锁文件顶层版本，如 `"version": "1.0.0"` |
| `dataagent/dataagent-backend/main.py` | DataAgent 对外暴露版本，如 `version="1.0.0"` |
| `opendataagent/VERSION` | 独立 `opendataagent` 发布版本，如 `1.0.0` |
| `opendataagent/web/package.json` | `opendataagent` Web 版本，如 `"version": "1.0.0"` |
| `opendataagent/web/package-lock.json` | `opendataagent` Web 锁文件顶层版本，如 `"version": "1.0.0"` |

### 2.2 Docker Compose 正式部署

| 文件 | 说明 |
|------|------|
| `deploy/docker-compose.prod.yml` | 更新 frontend、backend、dataagent-frontend、dataagent-backend、portal-mcp 五个正式镜像默认 tag，例如 `${OPENDATAWORKS_BACKEND_IMAGE:-mikefan2019/opendataworks-backend:1.0.0}` |
| `deploy/.env.example` | 更新离线部署时的镜像变量示例 |
| `opendataagent/deploy/docker-compose.yml` | 更新 `opendataagent-server` 与 `opendataagent-web` 默认 tag |
| `opendataagent/deploy/.env.example` | 更新独立 `opendataagent` 部署版本示例 |

### 2.3 构建相关（如有）

- `scripts/build/docker-build.env.example` 中 `VERSION`
- 其他引用版本号的地方

---

## 三、构建与发布

### 3.1 本地验证

```bash
# 前端构建
export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use
cd frontend && npm run build
cd ../dataagent/dataagent-frontend && npm run build && npm run build:widget

# 后端构建
cd ../../ && mvn -pl backend -am clean package -DskipTests

# 可选：本地构建 Docker 镜像
scripts/build/build-images.sh
```

### 3.2 打 Tag 并推送

```bash
git add -A
git commit -m "chore: release v1.0.0"
git tag v1.0.0
git push origin main
git push origin v1.0.0
```

推送 `v*` 标签会触发 GitHub Actions 的 [Build and Release](../../.github/workflows/docker-build.yml) 工作流。

### 3.3 CI 自动执行

工作流将依次执行：

1. **构建并推送 Docker 镜像**：`mikefan2019/opendataworks-frontend`、`mikefan2019/opendataworks-backend`、`mikefan2019/opendataworks-dataagent-frontend`、`mikefan2019/opendataworks-dataagent-backend`、`mikefan2019/opendataworks-portal-mcp`、`mikefan2019/opendataagent-server`、`mikefan2019/opendataagent-web`，打上版本 tag
2. **生成离线部署包**：调用 `scripts/create-offline-package.sh --tag 1.0.0`
3. **生成 Opendataagent 离线部署包**：调用 `opendataagent/scripts/create-offline-package.sh --tag 1.0.0`
4. **创建 GitHub Release**：上传两份离线包为 Release 附件，并自动生成 Release Notes（含提交记录）
5. **同步 Gitee Release**：上传同一组离线包附件，并在附件上传完成后用 Gitee 附件 id 回写下载链接

---

## 四、离线部署包与 Release

### 4.1 离线包生成（CI 中执行）

```bash
scripts/create-offline-package.sh --tag 1.0.0 --output opendataworks-deployment-1.0.0.tar.gz
```

- 使用 `--tag` 指定版本，对应 Docker Hub 上的镜像 tag
- 输出文件名建议为 `opendataworks-deployment-{version}.tar.gz`

### 4.2 Release 内容

GitHub Release 中应包含：

- **Release Notes**：GitHub Actions 自动生成（包含提交记录）
- **下载区块**：由工作流写入 Release 正文，包含 Docker 镜像拉取命令与两份离线包链接
- **Docker 镜像区块**：包含 `opendataworks-*` 和 `opendataagent-*` 的 Docker Hub 链接与 `docker pull` 示例
- **附件**：
  - `opendataworks-deployment-1.0.0.tar.gz`（离线部署包）
  - `opendataagent-deployment-1.0.0.tar.gz`（独立 `opendataagent` 离线部署包）
  - 可选：`opendataworks-deployment-1.0.0.tar.gz.sha256`（校验和）
  - 可选：`opendataagent-deployment-1.0.0.tar.gz.sha256`（校验和）

Gitee Release 复用同一组离线包附件，但不能复用 GitHub 下载 URL。同步步骤会先创建占位正文，上传附件后根据 Gitee 返回的 `attach_files/{id}/download` 地址生成最终正文并 PATCH 回 Release。

### 4.3 用户下载方式

1. **Docker 镜像**：
   ```bash
   docker pull mikefan2019/opendataworks-frontend:1.0.0
   docker pull mikefan2019/opendataworks-backend:1.0.0
   docker pull mikefan2019/opendataworks-dataagent-frontend:1.0.0
   docker pull mikefan2019/opendataworks-dataagent-backend:1.0.0
   docker pull mikefan2019/opendataworks-portal-mcp:1.0.0
   docker pull mikefan2019/opendataagent-server:1.0.0
   docker pull mikefan2019/opendataagent-web:1.0.0
   ```

2. **主系统离线部署包**：在 Release 页面下载 `opendataworks-deployment-1.0.0.tar.gz`，按 [deploy/README.md](../../deploy/README.md) 进行离线部署。
3. **Opendataagent 离线部署包**：在 Release 页面下载 `opendataagent-deployment-1.0.0.tar.gz`，按 [opendataagent/deploy/README.md](../../opendataagent/deploy/README.md) 进行离线部署。

---

## 五、发布后

- [ ] 在 GitHub Releases 页面检查附件是否上传成功
- [ ] 验证 Release Notes 中的下载链接可用
- [ ] 在 Gitee Releases 页面检查附件是否上传成功，并确认正文下载链接指向 `gitee.com/.../attach_files/.../download`
- [ ] 如有文档站，更新「最新版本」等说明

---

## 流程概览

```
1. 明确版本号 (如 1.0.0)
       ↓
2. 修改 Maven、前端、DataAgent、Compose 与构建配置中的版本号
       ↓
3. git commit、git tag v1.0.0、git push
       ↓
4. CI 构建镜像、生成两份离线包、创建 Release 并上传附件
       ↓
5. 检查 Release 页面，确认下载可用
```
