# Docker CI 构建优化

## 现状与范围

现有 GitHub Actions 在非 PR 事件中先构建、再调用一次构建动作推送镜像；前后端构建阶段还跟随目标架构执行。此次仅调整 `.github/workflows/docker-build.yml`、`backend/Dockerfile` 和 `frontend/Dockerfile`，不涉及业务代码、接口或独立 DataAgent 仓库。

## 方案

- 每个服务只执行一次 Buildx 构建；PR 仅构建，现有发布事件构建并推送。保留 frontend、backend、odw-mcp-server 三服务及 amd64/arm64 双架构。
- 使用按服务隔离的 GHA 缓存，单次构建同时读取和写入缓存。
- 前后端 builder 使用 `$BUILDPLATFORM` 原生运行，复制可跨架构使用的静态文件和 Java JAR；运行阶段仍使用目标架构镜像。DuckDB 是测试依赖，不进入生产 JAR。
- 构建任务超时为 60 分钟。同一引用的任务串行，仅 PR 可取消旧任务；main 和版本标签发布不主动中断。
- 保留现有镜像标签、凭据、离线包及 Release 行为，不引入新的部署入口。
- 静态验证发现原有 `softprops/action-gh-release@v1` 使用已过期的动作运行时；将两处引用升级为使用 Node 20 的 v2，保留原有输入参数（已核对上游 v2 的 action.yml）。

## 验证与取舍

使用 actionlint 检查工作流语法、表达式和步骤引用，审查构建/推送条件及 Dockerfile 阶段。实际双架构构建由 GitHub 执行；本机没有 Docker Buildx，不将静态检查表述为镜像构建成功。通过 PR 先验证三服务双架构构建，再合并生效。回退时还原本次提交即可恢复原有构建路径。
