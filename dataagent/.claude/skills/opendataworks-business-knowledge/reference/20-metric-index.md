# 指标口径索引

先结论：本页只定义指标口径和默认语义映射，不规定 SQL 执行步骤。

## 指标清单

### 数据表数量

- 指标 Key：`table_cnt`
- 公式：`COUNT(data_table.id)`
- 默认时间字段：`created_at`
- 默认映射：`data_table / id / count`
- 别名：表数量、表总数、元数据表数量
- 说明：默认统计 `data_table` 中未删除的数据表记录数。

### 任务数量

- 指标 Key：`task_cnt`
- 公式：`COUNT(data_task.id)`
- 默认时间字段：`created_at`
- 默认映射：`data_task / id / count`
- 别名：调度任务数、任务总数
- 说明：默认统计 `data_task` 中未删除的任务记录数。

### 工作流数量

- 指标 Key：`workflow_cnt`
- 公式：`COUNT(data_workflow.id)`
- 默认时间字段：`created_at`
- 默认映射：`data_workflow / id / count`
- 别名：工作流总数、流程数量
- 说明：默认统计 `data_workflow` 中的工作流定义数量。

### 发布记录数

- 指标 Key：`publish_record_cnt`
- 公式：`COUNT(workflow_publish_record.id)`
- 默认时间字段：`created_at`
- 默认映射：`workflow_publish_record / id / count`
- 别名：发布次数、发布记录数量
- 说明：工作流发布记录总数。

### 失败发布次数

- 指标 Key：`failed_publish_cnt`
- 公式：`SUM(CASE WHEN workflow_publish_record.status = 'failed' THEN 1 ELSE 0 END)`
- 默认时间字段：`created_at`
- 默认映射：`workflow_publish_record / status`
- 别名：发布失败次数、失败发布数
- 说明：按 `workflow_publish_record.status = 'failed'` 过滤后计数。

### 血缘关系数

- 指标 Key：`lineage_edge_cnt`
- 公式：`COUNT(data_lineage.id)`
- 默认时间字段：`created_at`
- 默认映射：`data_lineage / id / count`
- 别名：血缘边数、上下游关系数
- 说明：表级血缘边数量。
