# 工具配方（调用顺序与参数）

全部为 portal MCP 工具。高危工具（`portal_publish_workflow`、`portal_workflow_schedule_online`）在 default/acceptEdits 权限模式下触发对话内确认。

## 1. 探查表与 SQL

```
portal_search_tables {database?, table?, keyword?, table_limit?}
portal_get_table_ddl {database, table}   # 或 {table_id}
portal_analyze_sql   {sql, database?, cluster_id?}
  -> 返回输入/输出表、操作类型、风险告警
```

## 2. 创建任务（draft）

```
portal_create_task {
  task: { taskName, taskType:"batch", engine:"dolphin",
          dolphinNodeType:"SQL", taskSql, datasourceName, datasourceType,
          taskDesc, status:"draft" },
  input_table_ids:  [<来自 analyze>],
  output_table_ids: [<来自 analyze>]
}
```
更新：`portal_update_task {task_id, task, input_table_ids, output_table_ids}`（仅 draft）。

## 3. 组装工作流（draft）

```
portal_create_workflow { workflow: { workflowName, tasks:[...], edges:[...], globalParams? } }
portal_update_workflow { workflow_id, workflow: {...} }
```
绑定后向用户复述 DAG，确认后进入发布。

## 4. 发布与上线（强制顺序）

```
portal_preview_publish { workflow_id }
  -> 展示 diffSummary / errors / warnings；有 error 则停止
  -> 取得 preview_token

# 把操作目标与差异摘要放进 title/summary，便于确认卡片展示
portal_publish_workflow {
  workflow_id, operation:"deploy", preview_token,
  title?:"发布工作流 #<id>", summary?:"<diff 摘要>"
}
# 成功后
portal_publish_workflow { workflow_id, operation:"online", preview_token }
```
下线：`portal_publish_workflow { workflow_id, operation:"offline" }`。

## 5. 调度

```
portal_upsert_schedule { workflow_id, schedule: { scheduleCron, scheduleTimezone, ... } }
portal_workflow_schedule_online  { workflow_id, preview_token }   # 高危
portal_workflow_schedule_offline { workflow_id }
```

## 失败恢复

- `portal_publish_workflow` 失败 → 读取返回报错；结构问题回 draft 改后重走 preview；引擎问题提示检查 DolphinScheduler 配置。
- preview 的 `errors` 非空 → 不发布，逐条转成修复建议。
- preview_token 过期或工作流版本已变 → 重新 `portal_preview_publish` 取新 token。
