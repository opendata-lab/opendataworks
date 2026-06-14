# 任务字段契约（DataTask）

`portal_create_task` / `portal_update_task` 的 `task` 字段对齐平台 `DataTask` 实体。后端 agent 写接口是字段的权威来源；本表用于指导填写。

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

- `inputTableIds`: 输入表 ID 列表（来自 `portal_analyze_sql` 的输入表）。
- `outputTableIds`: 输出表 ID 列表（来自 analyze 的输出表）。

血缘 ID 必填以保证影响分析与血缘图正确；用 `portal_search_tables` 把表名解析为表 ID。

## 本期范围

- 本期 playbook 只保证 `dolphinNodeType: SQL` 的完整开发流；其他节点类型接口可用，但参数细节请向用户确认或参考平台文档。
