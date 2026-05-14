# 本体索引

先结论：本页描述 OpenDataWorks 平台业务对象和相关物理表，只提供语义映射，不提供执行步骤。

## 数据表元数据

- 相关表：`data_table`、`data_field`
- 说明：记录数据表基础属性、分层、状态、库表归属和字段定义。

## 表血缘与任务关系

- 相关表：`data_lineage`、`table_task_relation`
- 说明：记录上下游表关系，以及数据表与任务之间的读写关系。

## 调度任务

- 相关表：`data_task`、`task_execution_log`
- 说明：记录任务定义、执行引擎、调度状态和执行日志。

## 工作流治理

- 相关表：`data_workflow`、`workflow_task_relation`、`workflow_version`、`workflow_publish_record`
- 说明：记录工作流定义、任务归属、版本快照和发布历史。

## Doris 数据源

- 相关表：`doris_cluster`、`doris_database_users`
- 说明：记录 Doris 集群元信息以及数据库级只读/读写账号。
