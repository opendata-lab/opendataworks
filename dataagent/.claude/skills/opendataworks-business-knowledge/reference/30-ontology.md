# 平台对象映射

先结论：本页描述 OpenDataWorks 平台通用业务对象、关键字段和排查关系，只提供语义映射，不提供执行步骤，也不承接领域专属本体。详细 JSON 见 `assets/ontology.json`。

## 平台管理数据表

- 主表：`data_table`
- 关键字段：`id`、`cluster_id`、`db_name`、`table_name`、`table_comment`、`table_type`、`layer`、`business_domain`、`data_domain`、`owner`、`status`、`row_count`、`storage_size`、`doris_update_time`
- 说明：`data_table` 是平台元数据，不是目标表的真实业务数据。排查真实数据样例时，应使用 `db_name + table_name` 交给平台工具访问真实数据源。

## 字段定义

- 主表：`data_field`
- 关联：`data_field.table_id = data_table.id`
- 关键字段：`field_name`、`field_type`、`field_comment`、`is_partition`、`is_primary`、`field_order`
- 说明：用于确认平台已登记的字段、类型、注释、分区字段和主键/Key 字段。

## 表统计快照

- 主表：`table_statistics_history`
- 关联：`table_statistics_history.table_id = data_table.id`
- 关键字段：`row_count`、`data_size`、`partition_count`、`table_last_update_time`、`statistics_time`
- 说明：这是平台采集的历史统计快照；若用户问实时数据，需要转到真实数据源只读查询。

## 表任务读写关系

- 主表：`table_task_relation`
- 关联：`table_task_relation.table_id = data_table.id`，`table_task_relation.task_id = data_task.id`
- 关键字段：`relation_type`
- 方向规则：`relation_type='read'` 表示任务读取该表；`relation_type='write'` 表示任务写入该表。
- 排查口径：当前表的写入任务用于定位“谁产出这张表”；当前表的读取任务用于定位“谁消费这张表”。

## 调度任务与任务 SQL

- 主表：`data_task`
- 相关表：`task_execution_log`
- 关键字段：`task_name`、`task_code`、`task_type`、`engine`、`dolphin_node_type`、`datasource_name`、`datasource_type`、`task_sql`、`status`、`dolphin_flag`
- 说明：排查任务逻辑时优先看 `data_task.task_sql`。DataX 任务还要看 `source_table`、`target_table`、`target_datasource_name`、`column_mapping`。
- 最近执行：通过 `task_execution_log.task_id = data_task.id` 查看 `status`、`start_time`、`end_time`、`rows_output`、`error_message`、`log_url`。

## 表级血缘

- 主表：`data_lineage`
- 关键字段：`task_id`、`upstream_table_id`、`downstream_table_id`、`lineage_type`
- 上游规则：目标表上游使用 `data_lineage.downstream_table_id = 目标表 ID`，再取 `upstream_table_id`。
- 下游规则：目标表下游使用 `data_lineage.upstream_table_id = 目标表 ID`，再取 `downstream_table_id`。
- 说明：直接血缘优先使用 `data_lineage`；由任务读写关系推导的上下游可作为补充解释。

## 工作流治理

- 相关表：`data_workflow`、`workflow_task_relation`、`workflow_version`、`workflow_publish_record`
- 说明：记录工作流定义、任务归属、版本快照和发布历史。任务所属工作流通过 `workflow_task_relation.task_id = data_task.id` 关联。

## Doris 数据源

- 相关表：`doris_cluster`、`doris_database_users`
- 说明：记录 Doris/MySQL 数据源元信息以及数据库级只读/读写账号。账号密码字段只用于平台内部路由，不应在回答中暴露。

## 表问题排查语义路径

当用户要求排查平台管理的某张表的字段、数据、关联任务、任务 SQL、上下游表时，语义顺序是：

1. 先用 `table_id` 或 `cluster_id + db_name + table_name` 定位唯一 `data_table`。
2. 用 `data_field` 查看字段定义。
3. 用 `data_table.row_count` 和 `table_statistics_history` 判断平台统计快照；真实数据样例交给平台工具访问目标 `db_name.table_name`。
4. 用 `table_task_relation` 区分写入任务和读取任务。
5. 用 `data_task.task_sql`、任务引擎、数据源和状态解释任务逻辑。
6. 用 `task_execution_log` 查看最近执行状态和错误。
7. 用 `data_lineage` 还原直接上游表和直接下游表。
