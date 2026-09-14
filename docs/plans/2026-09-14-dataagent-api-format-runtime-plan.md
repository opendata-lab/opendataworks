# DataAgent API Format 运行时契约实施计划

对应设计：`docs/design/2026-09-14-dataagent-api-format-runtime-design.md`

## 实施任务

- [x] 严格校验 `api_format`，移除按 `provider_id` 推断协议的逻辑。
- [x] 统一 endpoint、客户端 base URL、认证头和运行时环境变量生成。
- [x] 将 `api_format` 从注册表选择结果传入任务执行与 Pi `cell.init`。
- [x] 将 Pi provider 白名单改为基于 `api_format` 的两种协议 profile。
- [x] 让模型检测和追问建议复用统一 HTTP 协议调用。
- [x] 清空无法可靠迁移的旧供应商注册记录与设置副本，阻止启动时回灌，并更新部署说明。
- [x] 补 Python、TypeScript 与跨进程契约回归测试。
- [x] 完成真实 HTTP 任务 smoke 并记录环境与结果。

## 验证记录

- Python：项目 `.venv-py313`，全量 `pytest -q` 为 713 passed。
- Pi runtime：Node 22.19.0，`npm test` 为 146 passed。
- 前端：Node 20.19.0，供应商配置与智能体详情测试合计 19 passed。
- 数据库迁移：Podman MySQL `127.0.0.1:3306`，会话库 `dataagent`；升级后确认 4 条旧供应商记录全部删除，设置表中的旧选择、凭据和供应商副本已清除，非供应商设置保留；再次执行启动引导后供应商仍为 0 条，迁移版本为 `20260914_add_api_format`。
- 真实 HTTP smoke：复用 Podman Redis `127.0.0.1:6379` 和真实模型凭据，临时显式配置 `/v1/messages` 后通过 `POST /api/v1/nl2sql/tasks` 以 `execution_mode=auto` 提交；状态从 `waiting/running` 进入 `finished`，SDK 事件与 SSE 终止事件可读取，最终 assistant 消息持久化为 `smoke-ok`，记录的 `engine_kind` 为 `pi_agent_core`。
- 清理：smoke topic 与临时供应商配置已删除；供应商表保持迁移后的空表状态。

## 发布与回退

1. 构建新的 Pi runtime 与 DataAgent backend 镜像。
2. Alembic 升级后，在管理页重建供应商配置。
3. 先执行模型检测，再提交最小任务 smoke。
4. 如协议请求失败，回滚应用镜像；供应商配置需手动恢复。
