# DataAgent API Format 运行时契约设计

## 背景与问题

供应商配置允许用户创建任意稳定 `provider_id`，并选择 `/v1/messages` 或
`/v1/chat/completions`。原实现仍在部分执行路径中把 `provider_id` 当作协议适配器，
导致自定义 ID 无法被 Pi runtime 识别，且 OpenAI-compatible 凭据被写入错误的环境变量。

## 目标与范围

- `provider_id` 只作为注册表身份标识，不参与协议或 URL 选择。
- `api_format` 是模型请求协议的唯一来源，仅允许两种已实现格式。
- 主任务、模型检测和追问建议使用一致的协议、认证和 URL 语义。
- 保持凭据只通过子进程环境传递，不进入 Pi stdio 帧或持久化事件。

## 设计

### Python 控制面

- `normalize_api_format` 对未知值直接报错，不再按供应商 ID 推断。
- 根据 `api_format` 生成实际 API endpoint 和客户端 base URL。
- Anthropic Messages 使用 `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL`；OpenAI
  Completions 使用 `OPENAI_API_KEY` / `OPENAI_BASE_URL`，并清空另一组变量。
- `resolve_runtime_provider_selection` 必须返回 `api_format`。
- 模型检测与追问建议使用统一的非流式 HTTP 调用，按 `api_format` 生成请求体和认证头。
- `claude_code` 引擎只接受 `/v1/messages`；OpenAI-compatible 请求需使用生产默认的
  `pi_agent_core`，不再静默发送错误协议。

### Pi 数据面

- `cell.init.model` 增加必填 `api_format`。
- Pi 按 `api_format` 选择 Anthropic 或 OpenAI stream，而不是查找固定供应商白名单。
- `provider_id` 原样保留在 Model 与任务结果中，便于审计和配置定位。

### 数据迁移

旧 `provider_type` 无法可靠映射新协议。迁移新增列后清空 `da_model_provider`，同时清除
`da_agent_settings` 中旧供应商选择、凭据和 `provider_settings` 副本，避免启动引导逻辑把
已删除记录重新创建；数据库、Skill 和 Widget 等非供应商设置保持不变。管理员需明确重建
供应商、凭据和模型配置，不保留猜测式兼容分支。

## 失败处理与安全性

- 非法 `api_format`、无效 base URL、空凭据和空响应均显式失败。
- 错误日志只记录脱敏 base URL，不记录凭据。
- API 响应错误最多保留 500 字符，避免无界错误内容进入日志。

## 验证

- Python 单元测试覆盖 URL、认证头、响应解析、provider selection 和 Pi 帧契约。
- Pi TypeScript 测试覆盖两种格式与任意自定义 ID。
- 构建 Pi runtime 后运行 Python 与真实 Node Cell 的跨进程协议测试。
- 环境可用时通过真实 HTTP 任务入口执行最小模型 smoke。

## 回滚

回滚代码恢复旧协议实现；供应商注册表在本次迁移后不会自动恢复，需从运维备份或管理页
重新配置。
