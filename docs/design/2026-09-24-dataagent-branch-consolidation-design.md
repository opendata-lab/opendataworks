# DataAgent 小范围分支整合设计

日期：2026-09-24

## Current state and problem

`main` 的独立 DataAgent 前端已经代理 `/api/v1/nl2sql-eval/`，但宿主机挂载的
`deploy/docker/nginx/dataagent-frontend.conf` 尚未同步。两套前端 Nginx 配置仍使用
默认的 1 MiB 请求体上限，因此 20 MiB 的 DataAgent 会话附件会先被代理拒绝。
Portal MCP 的六个只读工具也无法接受供工具轨迹展示的简短调用意图。

## Scope and solution

- 合并两个独立的 Nginx 修复：宿主机和镜像内的 DataAgent 配置保持一致；仅在
  代理至 DataAgent 的路由上放宽请求体上限。独立 DataAgent 前端在 server 级使用
  `client_max_body_size 25m`；主门户仅在 `/api/v1/dataagent/`、
  `/api/v1/nl2sql-admin/` 和 `/api/v1/nl2sql/` 三个 DataAgent 路由上使用该值。
  `/api/` 到 Java 后端的限制不变。25 MiB 高于当前后端 20 MiB 上限，超限请求
  仍由后端返回结构化错误。
- 将旧 Portal MCP 分支的可取部分移植到当前实现：六个只读工具的入参允许可选
  `description`，用于前端工具轨迹；该字段绝不转发给平台 API。数据开发写工具
  的入参契约本轮不变。
- 保留当前 Pi runtime、权限等待和评测实现，不合并其旧架构分支。Ossie 和网站
  大改动不属于这次小范围整合。

## Interfaces and tradeoffs

MCP 的 `description` 是可选、仅展示的字段；旧调用继续有效，后端请求形状不变。
Nginx 的 25 MiB 是请求体上限而非文件内容上限；后端仍按自己的
`DATAAGENT_UPLOAD_MAX_BYTES` 执行最终校验。若后端上限将来提高，代理值也需同步。
部署配置变化需要重新加载 Nginx 才会生效；代码合并本身不会改变正在运行的代理。

## Verification and backout

验证四份 Nginx 配置的目标路由和成对一致性；运行 Portal MCP 的契约测试，确认
带 `description` 的工具调用不会把字段传给平台 API，且工具 schema 仍无悬空引用。
回退时可还原本次提交，但独立前端的 eval 路由同步修复应优先保留。

计划：[2026-09-24-dataagent-branch-consolidation-plan.md](../plans/2026-09-24-dataagent-branch-consolidation-plan.md)。
