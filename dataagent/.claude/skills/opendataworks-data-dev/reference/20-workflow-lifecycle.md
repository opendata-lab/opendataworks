# 工作流生命周期与状态机

```
draft ──(portal_preview_publish)──> 预览(diff/errors/warnings)
   │                                      │ 有 error 级 issue → 停止，转修复
   │                                      ▼
   └──(portal_publish_workflow deploy, preview_token)──> 已部署(publishStatus=success)
                                          │
                                          ▼
                          (portal_publish_workflow online, preview_token)
                                          │
                                          ▼
                                       online ──(offline)──> offline
```

## 阶段说明

1. **draft**：工作流可改结构（绑定/解绑任务、设置依赖边）。只有 draft 能改结构。
2. **preview**：`portal_preview_publish` 返回：
   - `canPublish` / `requireConfirm`
   - `errors`（阻断项）、`warnings`（提示项）
   - `diffSummary`（与运行态的差异）
   - `preview_token`（一次性短时效凭证，发布/调度上线必须带上）
   存在 `errors` 时不得继续。
3. **deploy（发布，高危）**：`portal_publish_workflow(operation="deploy", preview_token=...)`，把平台定义同步到 DolphinScheduler，分配 `workflow_code`。
4. **online（上线，高危）**：`portal_publish_workflow(operation="online", preview_token=...)`，工作流变为可调度/可执行。
5. **offline（下线）**：`portal_publish_workflow(operation="offline")` 停止新的调度执行。

## 调度

- `portal_upsert_schedule`：配置 cron、时区、失败策略、worker 组等（draft 即可配）。
- `portal_workflow_schedule_online`（高危，需 preview_token）：按 cron 触发。
- `portal_workflow_schedule_offline`：停用调度。

## 约束

- 发布/上线依赖工作流已绑定 DolphinScheduler 配置（`dolphin_config_id`）；缺失时发布会失败，应提示用户先在平台完成调度引擎配置。
- 每次结构保存会生成版本快照；误发布可由用户在平台 UI 回滚（本技能不提供回滚工具）。
