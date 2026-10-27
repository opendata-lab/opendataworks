# 工具配方（调用顺序与参数）

全部为 ODW MCP 工具。`default` 权限模式下所有写工具都会触发对话内确认；`acceptEdits` 下草稿写操作自动执行，高危工具（`odw_create_table`、`odw_publish_workflow`、`odw_workflow_schedule_online`）仍触发确认；`bypassPermissions` 下写工具自动执行，但 deploy/online/schedule-online 仍必须提供后端 preview token。

## 1. 探查表与 SQL

```
odw_search_tables {database?, table?, keyword?, table_limit?}
odw_get_table_ddl {database, table}   # 或 {table_id}
odw_analyze_sql   {sql, database?, cluster_id?}
  -> 返回输入/输出表、操作类型、风险告警
```

## 2. 建表（新目标表）

建表 DDL 规范见 `reference/40-ddl-standards.md`，默认值见 `assets/engine-ddl-rules.json`。先预览、核对，再执行。表名由分层等组件生成，不用手填 `tableName`。

```
# 需要目标 Doris 集群/数据源时，用 odw_resolve_datasource 得到 cluster_id
odw_preview_create_table {
  db_name, layer, business_domain?, data_domain?, custom_identifier?,
  statistics_cycle?, update_type?, table_comment?,
  table_model?:"DUPLICATE",            # 明细=DUPLICATE(默认)/去重=UNIQUE/聚合=AGGREGATE
  key_columns?:[...], distribution_columns?:[...], bucket_num?:10, replica_num?:3,
  partition_column?,
  columns:[ { column_name, data_type, type_params?, nullable?, primary_key?,
              partition_column?, default_value?, comment? }, ... ]
}
  -> 返回生成的表名 + 规范化 DDL；把表名/DDL 展示给用户核对

# 用户确认后执行(高危,批准即建表)。执行必须带 doris_cluster_id。
odw_create_table {
  <同上字段>, doris_cluster_id,
  title?:"新建表 <层>_<主题>", summary?:"<DDL 摘要/字段概览>"
}
```

高级:可直接传 `doris_ddl` 提供完整 Doris CREATE TABLE(表名仍以生成结果为准,避免名称与 DDL 不一致)。建成后进入建任务环节维护血缘。

## 3. 创建任务（draft）

```
odw_create_task {
  task: { taskName, taskType:"batch", engine:"dolphin",
          dolphinNodeType:"SQL", taskSql, datasourceName, datasourceType,
          taskDesc, status:"draft" },
  input_table_ids:  [<来自 analyze>],
  output_table_ids: [<来自 analyze>]
}
```
创建时两个血缘字段都必须显式给出；无输入表就传 `[]`，输出表至少一个。

更新：`odw_update_task {task_id, task, input_table_ids?, output_table_ids?}`（仅 draft）。

血缘字段是**全量列表**：省略表示保留原值，传数组表示整体替换该侧。只改一侧时省略另一侧，
不要回传不完整的列表——漏掉的表 ID 会被删除。

```
# 只改 SQL，不动血缘
odw_update_task { task_id, task: {...} }

# 只重写输入表，输出保持不变
odw_update_task { task_id, task: {...}, input_table_ids: [<全部输入>] }
```

SQL 任务保存时后端会校验 `odw_analyze_sql` 的高可信匹配：SQL 中已明确解析出的表
必须出现在最终血缘里，否则保存被拒并列出缺失表，按提示补齐后重试。

## 4. 组装工作流（draft）

```
odw_create_workflow { workflow: { workflowName, tasks:[...], edges:[...], globalParams? } }
odw_update_workflow { workflow_id, workflow: {...} }
```
绑定后向用户复述 DAG，确认后进入发布。

## 5. 发布与上线（强制顺序）

```
odw_preview_publish { workflow_id }
  -> 展示 diffSummary / errors / warnings；有 error 则停止
  -> 取得 preview_token

# 把操作目标与差异摘要放进 title/summary，便于确认卡片展示
odw_publish_workflow {
  workflow_id, operation:"deploy", preview_token,
  title?:"发布工作流 #<id>", summary?:"<diff 摘要>"
}
# 成功后
odw_publish_workflow { workflow_id, operation:"online", preview_token }
```
下线：`odw_publish_workflow { workflow_id, operation:"offline" }`。

## 6. 调度

```
odw_upsert_schedule { workflow_id, schedule: { scheduleCron, scheduleTimezone, ... } }
odw_workflow_schedule_online  { workflow_id, preview_token }   # 高危
odw_workflow_schedule_offline { workflow_id }
```

## 7. 完善已有表的元数据（批量扫描 + 逐个完善）

给一张**已存在**的表补齐元数据：表描述、字段注释、受控属性（分层/业务域/数据域）、数据新鲜度契约。写工具是 `odw_update_table_metadata`（草稿级，`default` 下触发确认）。这不是建表——建新表仍走第 2 节。

批量扫描找缺口（复用读工具，无专门扫描接口）：

```
odw_search_tables  { database, table_limit }          # 或 odw_export_metadata { kind:"tables", database }
  -> 据「表注释为空 / 与表名相同、字段注释缺失」挑出元数据薄弱的表，排出待完善清单
```

逐表完善：

```
odw_get_table_ddl { database, table }   # 或 { table_id }  -> 字段清单、现有注释、DDL
# 基于 DDL/字段/血缘提案。受控值只能取平台清单内编码；时间列必须是真实字段
odw_update_table_metadata {
  table_id,                                                  # 或 database + table
  table_comment?,
  attributes?: { layer?, business_domain?, data_domain? },   # 清单外的编码服务端一律丢弃
  fields?: [ { field_name, comment }, ... ],                 # field_name 必须是真实字段
  freshness?: {                                              # 数据新鲜度契约，mode 默认 column
    mode?:"column", loaded_at_field,                         # loaded_at_field 必须是真实时间列
    warn_after_count?:1, warn_after_period?:"day",           # 默认 T-1：预警/过期各 1 天
    error_after_count?:1, error_after_period?:"day"
  },
  title?:"完善元数据 <库>.<表>", summary?:"<本次补了哪些>"
}
  -> 返回逐段 applied / skipped / failed；skipped 多为「值不在平台清单」或「字段不存在」
```

要点：

- 逐个完善 = 对清单里的表逐张调用，一张一确认，不要指望一次把整批自动写完。
- 受控属性与时间列都在**服务端硬校验**：编造的编码/列会被丢弃并写进 `skipped`，据此纠正后只重试该表。
- `data_domain` 必须归属所选 `business_domain`，否则被丢弃（业务域被丢时数据域一并丢）。
- 枚举取值本工具不写；`fields[].comment` 只写字段业务含义。
- 缺 `layer` 时服务端回落到表上已有分层；表本身也没有分层则该段进 `skipped`，需一并给出 `attributes.layer`。

## 失败恢复

- `odw_create_table` 失败 → 读取报错(表已存在/字段或类型非法/集群不可达);回 `40-ddl-standards.md` 修正后重新预览,不要盲目重试同一请求。
- `odw_publish_workflow` 失败 → 读取返回报错；结构问题回 draft 改后重走 preview；引擎问题提示检查 DolphinScheduler 配置。
- preview 的 `errors` 非空 → 不发布，逐条转成修复建议。
- preview_token 过期或工作流版本已变 → 重新 `odw_preview_publish` 取新 token。
