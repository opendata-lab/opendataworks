# OpenDataWorks Utility Scripts

This directory contains various utility scripts for building and deploying the OpenDataWorks platform.

## Directory Structure

| Path | Description |
|------|-------------|
| `build/` | Build helpers for Docker images (single arch / multi-arch). |
| `start.sh` | Start the stack with `deploy/docker-compose.prod.yml`, auto-creating `deploy/.env` from the example when missing. |
| `stop.sh` | Stop all services defined in `deploy/docker-compose.prod.yml`. |
| `restart.sh` | Restart services from the compose file. |
| `load-images.sh` | Load tarred images from `deploy/docker-images/` (offline deployment). |
| `load-package-and-start.sh` | Extract an offline package, load images, and optionally start the stack. |
| `create-offline-package.sh` | Produce an offline tarball containing compose files, scripts, images, and only the `.env.example` configuration template. |

## Common Tasks

### Deployment
From the repository root:
```bash
# Start the application (deploy/.env is required)
bash scripts/start.sh

# Stop the application
bash scripts/stop.sh
```

### Build
Build the project using scripts in `build/`.
```bash
# Build images (multi-arch, main frontend, Java backend and ODW MCP server)
bash scripts/build/build-multiarch.sh

# Use a pip mirror for Python image build layers
PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/ \
PIP_TRUSTED_HOST=mirrors.aliyun.com \
bash scripts/build/build-images.sh

# Or configure the multi-arch quick build
cp scripts/build/docker-build.env.example scripts/build/docker-build.env
bash scripts/build/build-quick.sh
```

`PIP_INDEX_URL` and `PIP_TRUSTED_HOST` are build-only settings and are not
persisted as runtime environment variables. Do not put credentials or tokens in
the mirror URL. Prefer HTTPS; set `PIP_TRUSTED_HOST` only when the internal
mirror requires it.

### DataAgent 评测

评测与离线评测包脚本已迁往独立 [opendataagent](https://github.com/opendata-lab/opendataagent) 仓库。
