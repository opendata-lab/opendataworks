# Opendataagent

`opendataagent` 是 OpenDataWorks 仓库里的独立智能体平台项目，包含：

- `web/`: Vue 3 + Vite + Element Plus 前端控制台
- `server/`: Go 后端，负责会话、Skill、MCP、模型配置和运行时
- `deploy/`: Docker Compose、环境变量模板和容器配置
- `scripts/`: 本地构建、release 打包和镜像构建脚本

它与主前端中的现有“智能问数”模块长期并行存在：

- 主前端智能问数：Python DataAgent / Claude Agent SDK 路线
- `opendataagent`：Go runtime / `agentsdk-go` 路线

两者互不替换，也不通过主前端菜单互相跳转。

## 本地开发

前端：

```bash
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"
nvm use
npm --prefix opendataagent/web install
npm --prefix opendataagent/web run dev -- --host 127.0.0.1 --port 3012
```

后端：

```bash
unset GOROOT
export PATH="/usr/local/bin:$PATH"
eval "$(/usr/local/bin/goenv init -)"
export GOCACHE=/tmp/odw-go-cache
cd opendataagent/server
go run ./cmd/opendataagent
```

## 容器部署

部署目录：

- [docker-compose.yml](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/deploy/docker-compose.yml)
- [.env.example](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/deploy/.env.example)
- [deploy/README.md](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/deploy/README.md)

典型流程：

```bash
cd opendataagent/deploy
cp .env.example .env
bash ../scripts/start.sh
```

默认会把仓库根目录 `skills/` 以只读方式挂载到容器内，并由服务启动时同步到运行目录。
如果目录位置不同，可在 `opendataagent/deploy/.env` 里调整 `OPENDATAAGENT_SHARED_SKILLS_PATH`。

如需手工执行 Compose，请显式指定项目名：

```bash
docker compose --project-name opendataagent --env-file .env up -d --build
```

如果同机部署 OpenDataWorks 和 opendataagent，避免只对其中一套旧部署执行 `--remove-orphans`；历史上从 `deploy` 目录启动的容器可能共享 `deploy` project label。

默认端口：

- Web: `18080`
- Server API: `18900`
- MySQL: `13306`

## Release 打包

脚本：

- [build-release.sh](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/scripts/build-release.sh)

默认会产出：

- `output/releases/opendataagent/<version>/`
- `output/releases/opendataagent/opendataagent-<version>-<goos>-<goarch>.tar.gz`
- `SHA256SUMS`

示例：

```bash
./opendataagent/scripts/build-release.sh --version 1.2.0
```

## 离线部署包

脚本：

- [create-offline-package.sh](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/scripts/create-offline-package.sh)
- [load-package-and-start.sh](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/scripts/load-package-and-start.sh)

默认会产出：

- `output/releases/opendataagent/opendataagent-deployment-<tag>.tar.gz`

离线包包含：

- `opendataagent-server` 镜像
- `opendataagent-web` 镜像
- `mysql:8.0` 镜像
- `shared-skills/` 快照
- `deploy/` 和 `scripts/` 启动辅助文件

示例：

```bash
./opendataagent/scripts/create-offline-package.sh
```

## 镜像构建

脚本：

- [docker-build.sh](/Users/guoruping/.codex/worktrees/92ff/opendataworks/opendataagent/scripts/docker-build.sh)

示例：

```bash
./opendataagent/scripts/docker-build.sh --version 1.2.0 --tag 1.2.0
```
