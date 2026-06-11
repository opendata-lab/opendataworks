# 离线部署包瘦身计划

- 日期: 2026-06-04
- 主题 slug: `offline-package-slimming`
- 关联设计: [docs/design/2026-06-04-offline-package-slimming-design.md](../design/2026-06-04-offline-package-slimming-design.md)

## 任务与触达文件

### 已完成

1. 镜像合并去重 + xz 压缩
   - `scripts/create-offline-package.sh`：一次性 `save_images` 到 `all-images.tar`，`tar | xz -T0` 输出 `.tar.xz`
   - `scripts/build/build-images.sh`：导出合并归档 `all-images.tar`
   - `scripts/load-images.sh`：优先加载 `all-images.tar`，回退逐镜像
   - `scripts/load-package-and-start.sh`：接受 `.tar.xz` / `.tar.gz`
   - 文档：`deploy/README.md`、`docs/handbook/operations-guide.md`
2. runner 去 `docker.io`
   - `dataagent/dataagent-backend/Dockerfile.runner`：`COPY --from=docker:27-cli` 静态客户端
3. 发布产物命名修正
   - `.github/workflows/docker-build.yml`、`docs/handbook/release-process.md`：主包改 `.tar.xz`

### 本次执行（evals 拆分）

4. 主包剔除评测镜像
   - `scripts/create-offline-package.sh`：从合并 save 列表移除两个 evals 镜像；env 重写保留评测镜像名（供附加包加载后使用）
5. 新增评测附加包生成器
   - `scripts/create-evals-offline-package.sh`：拉取两个 evals 镜像 → `evals-images.tar`（合并去重）→ `xz` → `opendataworks-evals-offline-<tag>.tar.xz`，内含加载器与评测工具脚本
6. 新增评测附加包加载器
   - `scripts/load-evals-images.sh`：`docker/podman load -i evals-images.tar`
7. CI 产出并附加评测附加包
   - `.github/workflows/docker-build.yml`：`create-release` 与 `create-latest-release` 增加生成与上传 `opendataworks-evals-offline-*.tar.xz`
8. 文档
   - `deploy/README.md` 第 3 节、`docs/handbook/operations-guide.md`、`docs/handbook/release-process.md`：说明评测镜像改由附加包提供

## 验证

- `bash -n` 校验所有改动脚本
- `python3 -c "import yaml; ..."` 校验工作流 YAML
- 无 Docker 守护进程，无法本地实跑打包/加载端到端；在交付说明中明确标注需在构建机实测：主包与评测附加包均能生成、`tar -xJf` 解包、`load-images.sh` / `load-evals-images.sh` 成功加载

## 回滚

- 各项改动彼此独立，按 commit 粒度可单独 revert
- 评测拆分回滚：恢复 `create-offline-package.sh` 中两个 evals 镜像条目、删除附加包脚本与 CI 步骤即可
