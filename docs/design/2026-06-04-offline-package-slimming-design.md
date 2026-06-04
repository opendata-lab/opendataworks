# 离线部署包瘦身设计

- 日期: 2026-06-04
- 主题 slug: `offline-package-slimming`
- 影响栈: 部署/打包脚本（`scripts/`）、镜像构建（`Dockerfile.runner`）、CI 发布流程（`.github/workflows/docker-build.yml`）、运维文档

## 现状

主系统离线包由 `scripts/create-offline-package.sh` 生成，内容几乎全部是 `deploy/docker-images/` 下的镜像归档：

- 8 个自建镜像 + `mysql:8.0` + `redis:7.2-alpine`，每个镜像单独 `docker save` 成一个 `*.tar`，最后用 `tar -czf`（gzip 默认级别）压成 `*.tar.gz`。
- 5 个镜像共用 `python:3.11-slim` 基础层、2 个前端共用 `nginx:alpine`；逐个 save 时这些共享层在每个 tar 里各存一份，无法跨镜像去重。
- `opendataworks-dataagent-runner` 镜像通过 `apt-get install docker.io` 安装整套 Docker 引擎（守护进程 + containerd + runc），但它只通过挂载的 socket 调 `docker run/ps/rm`，仅需客户端。
- `opendataworks-dataagent-evals-builtin` / `opendataworks-dataagent-evals-deepeval` 两个评测镜像默认不随服务启动，`deepeval` 还会引入很重的 ML 依赖，却被打进每个主包。

## 问题

离线包体积偏大，主要来自：共享层重复存储、gzip 压缩率一般、runner 携带无用的 Docker 守护进程、以及大多数部署用不到的评测镜像。

## 方案

四个相互独立的优化点：

1. **镜像合并去重**：所有镜像一次性 `docker save`（Podman 用 `--multi-image-archive`）保存到单个 `deploy/docker-images/all-images.tar`，共享 base 层只存一份。
2. **xz 压缩**：整包用 `xz -T0`（多线程）替代 gzip，压缩级别可经 `OPENDATAWORKS_XZ_LEVEL` 覆盖（默认 6），输出名改为 `*.tar.xz`。目标机用 `tar -xJf` 解压（`xz`/`xz-utils` 为常见预装组件）。
3. **runner 去 `docker.io`**：改为从官方 `docker:27-cli` 镜像 `COPY` 静态 docker 客户端二进制，去掉守护进程依赖。
4. **evals 拆为独立附加包**：主包不再包含两个评测镜像；新增 `scripts/create-evals-offline-package.sh` 生成 `opendataworks-evals-offline-<tag>.tar.xz` 附加包，内含两个评测镜像（合并去重 + xz）与 `scripts/load-evals-images.sh` 加载器。需要在线评测的部署单独下载并加载该附加包。

## 接口与兼容

- `scripts/load-images.sh`：优先加载 `all-images.tar`，找不到时回退逐镜像 `*.tar`（兼容旧包）。不再把评测镜像列为必需。
- `scripts/load-package-and-start.sh`：接受 `.tar.xz`（`tar -xJf`），保留 `.tar.gz` 兼容。
- 评测运行脚本 `scripts/run-dataagent-evals.sh` / `run-dataagent-deepeval-evals.sh` 不变：仍按镜像 env 变量执行；只是镜像改由评测附加包提供。
- CI 发布同时产出主包 `opendataworks-deployment-<tag>.tar.xz` 与评测附加包 `opendataworks-evals-offline-<tag>.tar.xz`。
- 独立 `opendataagent` 离线包使用各自脚本，保持 `.tar.gz`，本设计不涉及。

## 取舍

- 评测镜像拆分会让需要评测的用户多下载一个附加包，但显著减小主包，对绝大多数只部署主链路的用户更友好。
- 未将 runner 改为 `FROM` backend：当前 CI 为 matrix 并行、各镜像独立构建，runner 构建时拿不到 backend 镜像，改造需引入串行依赖与镜像引用传参，风险高于收益；合并去重已回收两者共享基础层的大部分重复成本。
- xz 压缩慢于 gzip，但离线包“压一次解多次”，且 `-T0` 多线程已缓解打包耗时。
