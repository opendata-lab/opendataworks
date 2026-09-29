# MCP 可用性检测实施计划

配套设计：[`../design/2026-09-27-mcp-availability-detection-design.md`](../design/2026-09-27-mcp-availability-detection-design.md)

## Tasks

1. 在 Pi runtime MCP 模块增加可抛错的单服务 probe，并增加只通过 stdin/stdout JSON 通信的 CLI。
2. 在 DataAgent 后端增加 probe 子进程调度、超时、结果归一化和检测响应 schema。
3. 增加管理员检测路由并锁定成功、失败、404 的接口语义。
4. 在 MCP 管理列表增加检测按钮、逐行 loading 和成功/失败结果展示。
5. 增加 Pi、后端服务/路由、前端组件测试，并执行相关构建。

## Verification

- Pi runtime：真实 SDK + 本地 stdio fixture 完成 initialize 与 `tools/list`；失联 fixture 按时失败。
- 后端：mock probe 子进程结果验证 registry 配置转换、超时和错误归一化；路由测试验证响应契约。
- 前端：组件测试验证检测请求、逐行状态和成功/失败反馈。
- 构建：Pi TypeScript build、DataAgent 前端 Vite build。

本次检测功能本身会通过自动化测试覆盖完整协议连接；若本地没有可访问的真实外部 MCP 服务，验证
报告将明确说明未执行外部服务的人工 smoke，不把 fixture 测试描述为真实外部连通性验证。

## Backout

删除检测路由、probe CLI 和前端入口即可；无数据库变更或数据回退动作。

## Verification results

- Pi runtime：Node `22.19.0`，`npm test` 通过（148 tests）；真实 stdio fixture 覆盖
  initialize、`tools/list` 和超时终止。
- 后端：`dataagent-backend/.venv-py313`，定向 pytest 通过（38 tests），覆盖配置传递、失败归一化、
  未知 ID、HTTP 路由和 event-loop 并发契约。
- 前端：Node `20.19.0`，MCP 组件 Vitest 通过（17 tests），Vite production build 成功。
- 本地真实 smoke：Podman MySQL `127.0.0.1:3306`（业务库 `opendataworks`、session/registry
  库 `dataagent`），Podman Redis `127.0.0.1:6379`，后端临时监听 `127.0.0.1:8901`；对
  `127.0.0.1:8801` 的真实 Portal MCP 调用检测接口，返回 `verified`、23 个工具、1702 ms。
- 未使用模型/provider，也未执行任何 MCP 业务工具；本次 smoke 只验证管理接口到真实 MCP
  initialize + 工具发现的完整检测链路。未执行浏览器人工点击，UI 行为由组件测试和 production
  build 覆盖。
