# 离线部署包与运行时去冗余实施计划 (Deployment Package Slimming)

- 日期: 2026-11-04
- 主题 slug: `deployment-package-slimming`
- 关联设计: `docs/design/2026-11-04-deployment-package-slimming-design.md`

## 任务拆解

- [x] **任务 1：从 Docker Compose 移除 Redis**
  - 修改 `deploy/docker-compose.prod.yml`：移除 `redis` 服务定义，清理日志注释。
  - 修改 `deploy/docker-compose.dev.yml`：移除 `redis` 服务定义。
- [x] **任务 2：从构建与打包脚本中移除 Redis 并排除/删除 Opik**
  - 彻底删除 `deploy/opik/` 废弃评测编排目录（避免打包与仓库冗余）。
  - 修改 `scripts/create-offline-package.sh`：
    - `deploy/` 拷贝命令添加 `--exclude='opik'` 与 `--exclude='./opik'`。
    - 从 `EXTRA_IMAGES` 中移除 `redis-7.2-alpine.tar`。
    - 移除检查已废弃根目录 `database/mysql` 的历史死代码。
  - 修改 `scripts/load-images.sh`：
    - 从 `LEGACY_IMAGES` 和 `IMAGES` 中移除 `redis:7.2-alpine`。
    - 更新结尾镜像列表过滤命令（去掉 `redis`）。
  - 修改 `scripts/build/build-images.sh`：
    - 移除 `REDIS_IMAGE` 变量定义、拉取与导出逻辑。
    - 修正构建步骤计数显示与导出清单。
    - 移除未使用的 Python `PIP_BUILD_ARGS`。
  - 修改 `scripts/build/docker-build.env.example` 与 `scripts/build/build-quick.sh`：
    - 移除已废弃的 `BUILD_ODW_MCP_SERVER` 与未使用的 `PIP_*` 配置。
  - 修改 `scripts/dump-logs.sh`：
    - 从 `SERVICES` 列表中移除 `redis`。
  - 修改 `scripts/README.md`：
    - 移除过时的 Python pip 镜像源构建说明。
- [x] **任务 3：更新项目与部署文档**
  - 修改 `deploy/README.md`：移除 Redis 描述。
  - 修改 `README.md` 与 `README_en.md`：移除启动命令与特性中拉起 Redis 的描述。
- [x] **任务 4：补充并执行回归验证**
  - 修改 `tests/test_deploy_config_contract.py`：断言 `redis` 服务不存在，断言没有 6379 端口映射。
  - 修改 `tests/test_offline_package_env_policy.py`：断言 `scripts/create-offline-package.sh` 中排除了 `opik`，且不再引用 `redis`。
  - 运行 `pytest` 测试套件，14 项测试全部通过（1 项跳过）。

## 涉及文件

- `deploy/docker-compose.prod.yml`
- `deploy/docker-compose.dev.yml`
- `scripts/create-offline-package.sh`
- `scripts/load-images.sh`
- `scripts/build/build-images.sh`
- `scripts/dump-logs.sh`
- `deploy/README.md`
- `README.md`
- `README_en.md`
- `tests/test_deploy_config_contract.py`
- `tests/test_offline_package_env_policy.py`

## 验证与验收

- `uv run --with pytest --with python-dotenv pytest tests/test_deploy_config_contract.py tests/test_offline_package_env_policy.py` 通过。
- 检查 `git diff` 确认无非预期改动。
