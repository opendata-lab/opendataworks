# DataAgent API Format 运行时契约实施计划

对应设计：`docs/design/2026-09-14-dataagent-api-format-runtime-design.md`

## 实施任务

- [x] 严格校验 `api_format`，移除按 `provider_id` 推断协议的逻辑。
- [x] 统一 endpoint、客户端 base URL、认证头和运行时环境变量生成。
- [x] 将 `api_format` 从注册表选择结果传入任务执行与 Pi `cell.init`。
- [x] 将 Pi provider 白名单改为基于 `api_format` 的两种协议 profile。
- [x] 让模型检测和追问建议复用统一 HTTP 协议调用。
- [x] 清空无法可靠迁移的旧供应商注册记录，并更新部署说明。
- [x] 补 Python、TypeScript 与跨进程契约回归测试。
- [ ] 完成真实 HTTP 任务 smoke 并记录环境与结果。

## 发布与回退

1. 构建新的 Pi runtime 与 DataAgent backend 镜像。
2. Alembic 升级后，在管理页重建供应商配置。
3. 先执行模型检测，再提交最小任务 smoke。
4. 如协议请求失败，回滚应用镜像；供应商配置需手动恢复。
