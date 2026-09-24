# DataAgent 小范围分支整合计划

设计：[2026-09-24-dataagent-branch-consolidation-design.md](../design/2026-09-24-dataagent-branch-consolidation-design.md)。

## Implementation

1. 将独立 DataAgent 前端缺失的 eval 代理路由同步到宿主机配置，确认两份文件一致。
2. 合入附件上传的代理请求体上限，并补齐主门户的 `/api/v1/dataagent/` 路由。
3. 将 Portal MCP 六个只读工具的 `description` 字段移植到当前代码；更新技能说明和
   测试。不要覆盖现有数据开发工具或 schema 展平实现。

## Verification

- 检查 Nginx 配置成对一致、路由落点与请求体限制；有解析器时运行语法检查。
- 用项目虚拟环境运行 Portal MCP 定向测试，覆盖有/无 `description` 的请求及
  schema 展平契约。
- `git diff --check`，再核对整合后的差异只涉及计划内文件。

## Rollout and backout

提交并推送 `main` 后，部署时重新加载相应 Nginx 和 Portal MCP 服务。
如出现兼容问题，回退相关整合提交；不要回滚不相关的当前主线实现。
