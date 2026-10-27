# 任务字段契约（DataTask）

`odw_create_task` / `odw_update_task` 的 `task` 字段对齐平台 `DataTask` 实体。后端 agent 写接口是字段的权威来源；本表用于指导填写。

## 常用字段

| 字段 | 说明 | 示例 |
| --- | --- | --- |
| `taskName` | 任务名（唯一，遵循命名规范） | `dwd_user_order_di` |
| `taskCode` | 任务编码（可选，未填由后端生成） | `task_user_order_001` |
| `taskType` | `batch`（批）或 `stream`（流） | `batch` |
| `engine` | `dolphin` 或 `dinky` | `dolphin` |
| `dolphinNodeType` | 节点类型：`SQL` / `SHELL` / `PYTHON` / `SPARK` / `FLINK` | `SQL` |
| `taskSql` | SQL 节点的 SQL 文本（含写操作的 SQL 只进这里） | `INSERT INTO ... SELECT ...` |
| `datasourceName` | 数据源名 | `doris_prod` |
| `datasourceType` | `MYSQL` / `DORIS` / ... | `DORIS` |
| `taskDesc` | 描述 | `用户订单明细每日加工` |
| `taskGroupName` | 任务组（可选） | `etl_user` |
| `priority` | 优先级（数字，可选） | `5` |
| `timeoutSeconds` | 超时（秒，可选） | `3600` |
| `retryTimes` / `retryInterval` | 重试次数 / 间隔（可选） | `3` / `60` |
| `owner` | 负责人（agent 调用时由后端按 operator 审计标注） | — |
| `status` | 创建一律 `draft` | `draft` |

## 血缘字段（与 task 平级）

- `input_table_ids`: 输入表 ID 列表（来自 `odw_analyze_sql` 的输入表）。
- `output_table_ids`: 输出表 ID 列表（来自 analyze 的输出表）。

用 `odw_search_tables` 把表名解析为表 ID。

### 全量列表语义

这两个字段是**全量列表，不是增量**。后端按传入内容整体替换该侧血缘。

| 传法 | `odw_create_task` | `odw_update_task` |
| --- | --- | --- |
| 省略字段 | 不允许，两个字段都必须显式提供 | 保留该侧原有血缘 |
| `[]` | 输入可以为 `[]`；输出不允许 | 清空该侧；输出仍不允许清空 |
| 非空数组 | 全量写入 | 全量替换该侧 |

要点：

- **只想改一侧就省略另一侧**，不要为了凑参数而回传一个不完整的列表——漏掉的表 ID 会被删除。
- **不确定该侧现有血缘时，一律省略该字段。** `odw_get_task` 返回的是任务本身，不含血缘列表，读不到现状；只有在本次能给出该侧完整表 ID 的前提下才传数组。
- SQL 任务保存时后端会用 `odw_analyze_sql` 的高可信匹配结果做校验：SQL 里已明确解析出的表如果不在最终血缘中，保存会被拒绝并列出缺失的表。按提示补齐后重试即可。
- 未匹配（unmatched）和歧义（ambiguous）的表不会阻断保存，只在发布预检里提示。

## 本期范围

- 本期 playbook 只保证 `dolphinNodeType: SQL` 的完整开发流；其他节点类型接口可用，但参数细节请向用户确认或参考平台文档。
