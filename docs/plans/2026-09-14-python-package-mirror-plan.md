# Python 包镜像源配置实施计划

**Date:** 2026-09-14
**Design:** `docs/design/2026-09-14-python-package-mirror-design.md`

## Tasks

1. 清理工作区草稿中与构建镜像源无关的 `mcp[cli]`、Compose 运行时变量和沙箱 `PIP_` 转发。
2. 调整 DataAgent backend、runner、Portal MCP、DeepEval、Opik Dockerfile，只声明 `ARG PIP_INDEX_URL` 与 `ARG PIP_TRUSTED_HOST`，不持久化为 `ENV`。
3. 让 `build-images.sh`、`build-multiarch.sh` 用 Bash 数组向 Python 镜像传递非空 build args。
4. 让 `build-quick.sh` 把配置文件中的两个 PIP 变量显式 export；更新 `docker-build.env.example` 和构建说明。
5. 更新聚焦测试，覆盖 Dockerfile、构建脚本、依赖边界和沙箱边界。
6. 运行 pytest、Shell 语法、Compose 解析、diff 检查和至少一个真实镜像构建；检查最终镜像环境。
7. 只提交本 topic 文件，创建独立 PR，等待 CI 通过后合并到 `main`。

## Touched Files

- `dataagent/dataagent-backend/{Dockerfile,Dockerfile.runner,tests/test_runner_dockerfile.py}`
- `dataagent/portal-mcp/Dockerfile`
- `tools/dataagent-evals/{deepeval,opik}/Dockerfile`
- `scripts/build/{build-images.sh,build-multiarch.sh,build-quick.sh,docker-build.env.example}`
- `scripts/README.md`
- 本 topic 的 design/plan 文档

## Verification

- `dataagent/dataagent-backend/.venv-py313/bin/python -m pytest dataagent/dataagent-backend/tests/test_runner_dockerfile.py -q`
- `bash -n scripts/build/build-images.sh scripts/build/build-multiarch.sh scripts/build/build-quick.sh`
- `docker compose -f deploy/docker-compose.dev.yml config --quiet`
- `docker compose -f deploy/docker-compose.prod.yml config --quiet`
- 使用公开 index 参数构建一个 Python 镜像，并用 `docker image inspect` 确认 PIP 配置未进入最终环境。
- `git diff --check`

## Rollout and Backout

该功能默认关闭；不设置变量即保持原有构建路径。若镜像源不可达，清空两个变量即可回退默认 PyPI。代码级回退可整体 revert 本 topic 提交，不涉及数据库或运行数据迁移。
