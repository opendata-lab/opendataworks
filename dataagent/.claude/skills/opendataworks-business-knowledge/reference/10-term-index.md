# 术语索引

先结论：遇到平台术语、别名或歧义时，先用本页确认业务含义；仍不明确时，再查看 `assets/term_explanations.json`。

## 数据层级

- 别名：表层级、层级、ODS/DWD/DIM/DWS/ADS
- 解释：数据层级描述表在数仓中的分层位置。OpenDataWorks 当前使用 ODS、DWD、DIM、DWS、ADS 五类层级保存到 `data_table.layer`。
- 易混术语：业务域、数据域
- 歧义消解：请确认要看的是 `layer` 分布，还是 `business_domain` / `data_domain` 维度。
- 相关指标：数据表数量
- 相关表：`data_table`

## 血缘关系

- 别名：上下游血缘、lineage
- 解释：血缘关系描述数据表之间的输入输出依赖，平台表 `data_lineage` 记录 `upstream_table_id` 和 `downstream_table_id`。
- 易混术语：任务依赖、工作流依赖
- 歧义消解：请提供明确表名；同名表可能存在于多个数据库时，一并提供 `db_name`。
- 相关指标：血缘关系数
- 相关表：`data_lineage`、`data_table`

## 工作流发布记录

- 别名：发布记录、工作流发布流水
- 解释：`workflow_publish_record` 记录平台将某个 workflow 版本发布到目标引擎的动作、状态、操作人和时间。
- 易混术语：工作流状态、发布状态
- 歧义消解：请确认要看历史发布记录，还是 `data_workflow.publish_status` 上的当前发布状态。
- 相关指标：发布记录数、失败发布次数
- 相关表：`workflow_publish_record`、`data_workflow`

## 任务依赖

- 别名：上下游任务、任务上下游
- 解释：任务依赖通常结合 `workflow_task_relation` 的上下游计数，以及 `table_task_relation` / `data_lineage` 推导出的读写关系来理解。
- 易混术语：血缘关系、工作流关系
- 歧义消解：请确认要看任务所在工作流、上下游任务数量，还是任务读写了哪些表。
- 相关指标：任务数量、工作流任务数
- 相关表：`data_task`、`workflow_task_relation`、`table_task_relation`

## Doris 只读账号

- 别名：只读账号、数据库只读用户
- 解释：`doris_database_users` 按 `cluster_id + database_name` 保存数据库级只读账号和读写账号，供平台只读查询路由使用。
- 易混术语：Doris 集群、数据库路由
- 歧义消解：请提供明确的 `database_name`；同一数据库在多个集群出现时，需要进一步确认 `cluster_id`。
- 相关表：`doris_cluster`、`doris_database_users`

## Doris 引擎

- 别名：doris、Doris 数据源、Doris 查询引擎
- 解释：Doris 在语义中表示查询引擎类型，不是 SQL 的 schema 名。真正写 SQL 时，应使用 metadata 返回的 `db_name` / schema 作为库名前缀。
- 易混术语：database_name、db_name、schema
- 歧义消解：请确认用户给的是引擎类型，还是实际数据库 / schema 名。
- 相关表：`doris_cluster`、`doris_database_users`、`data_table`

## DF快照表

- 别名：df表、快照表、每日全量快照表
- 解释：数仓中若表名体现 `df`，通常表示按 `ds` 存储的每日全量快照表。
- 易混术语：增量表、趋势分析
- 歧义消解：请确认要看最新快照，还是要按 `ds` 回看历史区间。
- 相关表：`data_table`、`data_field`

## DI增量表

- 别名：di表、增量表、每日增量表
- 解释：数仓中若表名体现 `di`，通常表示按 `ds` 存储的每日增量表。
- 易混术语：DF快照表、趋势分析
- 歧义消解：请提供要查询的时间范围，优先明确 `ds` 起止日期。
- 相关表：`data_table`、`data_field`
