# 数据新鲜度（Freshness）检查

## 功能概述

数据新鲜度检查回答一个问题：**这张表的数据，是否在约定的时限内更新了？** 设计对齐 dbt 的 `source freshness`——由用户为表显式声明「用哪个时间字段」和「多久算 warn / 多久算 error」，未配置契约的表不参与检查、不产出结论。

心智模型就是一段契约：

```
表 orders
  时间字段: _etl_loaded_at
  warn_after:  2 小时
  error_after: 4 小时
  filter:      env = 'prod'    # 可选
```

## 为什么不靠元数据更新时间自动判断

一个常见的误解是「看表最后一次被写的时间就行」。实际不行：`information_schema.tables.UPDATE_TIME` 只能**证伪**，不能**证实**——

- 空导入、重跑写入同一批数据、compaction 都会推进它；
- 全量覆盖（`insert overwrite`）把昨天的数据再写一遍，它照样前进。

要判断「新数据到底写没写、还是又是昨天那批」，只有**数据自身携带的时间标记**能做到（业务时间列或分区值），因为 `information_schema` 里根本不包含「这批数据是哪天的」这个信息。因此本功能不做任何从命名规范、调度 cron、行数变化去推断阈值的「猜测」——猜出来的只会是骗人的绿灯。

## 四种取值模式

模式由用户显式选择，没有自动阶梯、没有自动兜底。

| 模式 | 取数方式 | 能发现「写入了旧数据」 | 何时用 |
| --- | --- | --- | --- |
| `column` | `SELECT MAX(\`col\`)` | ✅ | 首选。表有加载时间列或业务时间列（`etl_time` / `order_time` / `event_time`） |
| `custom_sql` | 用户查询包成标量子查询取时间戳 | ✅ | 加载时点需要自定义算法（对齐 dbt 1.10 `loaded_at_query`）。与 `column` 互斥 |
| `partition` | `SHOW PARTITIONS` 解析最新分区业务日期 | ✅ | 大表、按天分区（`ds` / `dt`），`MAX()` 成本高时零扫描 |
| `metadata` | `information_schema.tables.UPDATE_TIME` | ❌ | 兜底档，见下方说明 |

`column` / `custom_sql` 的「快照时间」取自 Doris 侧同一条 SQL 的 `NOW()`，避免门户与 Doris 时钟偏差。

### metadata 模式的局限（重要）

`metadata` 模式**只能发现「没人写了」**（任务挂掉、表被遗弃 → `UPDATE_TIME` 长期不动），**发现不了「写进去的是旧数据」**。选它就意味着接受这个局限。只有当表既没有可用时间列、也没有分区时，才退到这一档；能上其他三种就别用它。

### partition 模式的语义提醒

`partition` 比较的是**分区值解析出的业务日期**（`ds=20260806` → `2026-08-06`），不是分区的物理写入时间。注意它的 age 语义：一张 T+1 日表，正常情况下 `max(ds)` 就是昨天，`age` 恒在 24～48 小时之间。阈值要按「今天几点还没出昨天的数」来设，**不能**照搬「2 小时没更新就告警」的直觉，否则天天误报。`partitionFormat` 应写成匹配分区名数字串的格式，如 `yyyyMMdd`。

## 契约只有表级一层

**每张表各自声明自己的 SLA**（对齐 dbt——每个 source 在 `sources.yml` 里各自写 `freshness`）。没有规则级默认、没有按 layer 兜底、没有继承。

- `table_freshness_config` 一张表一行：`mode` + 时间字段 + `warnAfter` / `errorAfter` + 可选 `filter`。
- `enabled = 0` 显式关闭该表检查（等价 dbt 的 `freshness: null`）。
- `warnAfter` 与 `errorAfter` 都没配 → 该表不检查。

> 初版曾设计「表级 + 规则默认」两层逐字段合并（`rule_config.defaults[]` 按 layer/库给一批表兜底阈值）。评审时去掉了——「一次给整个 layer 设 SLA」的便利，抵不上多一层配置存储与继承推理的复杂度；每张表显式声明更符合事件驱动、每表契约的模型。

运行期参数（不是契约、是运维旋钮）在 `application.yml`：

```yaml
freshness:
  check:
    enabled: true                     # 工作流完成后触发检查的开关
    query-timeout-seconds: 30
    max-concurrent-per-cluster: 4
```

## 状态判定

```
age = snapshotted_at - max_loaded_at

max_loaded_at 为空          -> error        (reason = never_loaded)
age >  errorAfter           -> error
age >  warnAfter            -> warn
否则                        -> pass
SQL 失败 / 超时 / 列不存在  -> runtime_error
```

- 比较用**严格大于**：`age` 恰好等于阈值判 `pass`（对齐 dbt `Time.exceeded`）。先判 error 后判 warn。
- 「从未加载」显式判 `error` 且带 `reason = never_loaded`，问题描述直接说「该表从未产出过数据」，而非甩一个天文数字延迟。
- `runtime_error` 独立于 `pass`：检查没跑成功不等于数据新鲜。

## 触发时机

**数据只在生产任务运行时变动，所以检查绑定到「运行」，不做固定时钟轮询。** 对上次产出后没动过的表反复取数只是浪费——两次产出之间数据年龄只是线性增长，没有新信息。这一点上本实现比 dbt 更进一步：dbt 不掌握外部 source 的加载时机，只能定时跑 `source freshness`；而本平台拥有生产工作流，知道每张表何时被写，可精确在写入后检查。

一个检查，**两个触发点**，只决定何时运行：

1. **工作流完成后（主，事件驱动）**：`WorkflowExecutionSyncJob` 识别新变为成功的实例，检查该工作流关联的表（包括写出表与读取表）——未配置契约的表自动跳过；对配置契约的表发起探针，回答「任务报成功了，数据真的到了吗，消费的上游数据是否也是新鲜的」。**手动和定时（调度）成功都触发；补数（`COMPLEMENT_DATA`）不触发**——补数写的是过去的调度日期，不改变当前新鲜度，还会在 `metadata` 模式下造成假 `pass`。「补数有没有把目标日期补上」是完整性校验、不是新鲜度。
2. **按需**：Data Studio「数据新鲜度」页签「立即检查」（`POST /v1/tables/{id}/freshness/check`）。

**没有墙钟轮询，也没有每日巡检。** 数据只在任务跑的时候变，工作流关联表在运行后即时检查即可；非工作流关联的表用按需触发。开关 `freshness.check.enabled`（默认 true，控制工作流触发；按需路径不受影响）。

## freshness 不是巡检规则，不产生巡检问题

新鲜度是独立于巡检的事件驱动子系统。它**不产生 `inspection_issue`**、不参与每日巡检、`inspection_rule` 里也没有 `data_freshness` 规则。红/黄状态活在两个地方：

- `data_table.freshness_status`（每表最新态，供表列表按状态过滤）；
- `table_freshness_result`（每次检查一行，含历史，带 `workflow_instance_id` 可反查是哪次运行触发的）。

这对齐 dbt——`dbt source freshness` 只写 `sources.json` 状态，不建"问题"；红了靠流水线里的下游动作（我们这里是页面可见 + 后续可接的告警）。

**在哪看**：
- **工作流详情页「数据新鲜度」页签** —— 顶部汇总（关联表总数 / 写出 / 读取 / 正常 / 预警 / 过期 / 检查失败 / 未配置），「每次运行的问题表数」表（检查时间 / 触发实例 / `问题数/总数`）一眼看出每次运行有几张表出问题，下方展示关联表最新状态，通过「写出 / 读取 / 读写」标签区分角色并支持快速筛选。**不在巡检页**（放巡检页语义错位，已移除）。
- **Data Studio 表级「数据新鲜度」页签** —— 单表的生效契约、最近结果、历史与「立即检查」。

## 缺少可判定字段的表怎么办

全量覆盖、没有任何时间列、也没有分区的表，当前**无法可靠判定新鲜度**。对这类表，正确做法不是编一个假的 `pass`，而是：

- 面板上它就是「未纳入新鲜度管理」，不给假绿灯；
- 推动补一列写入时间戳（如 `etl_time DATETIME`），一次 DDL 换永久可检。这也是 dbt 生态里 `_etl_loaded_at` 成为惯例的原因。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/tables/{id}/freshness` | 生效契约（含字段来源）与最近一次结果 |
| PUT | `/v1/tables/{id}/freshness` | upsert 表级契约 |
| DELETE | `/v1/tables/{id}/freshness` | 删除表级契约 |
| POST | `/v1/tables/{id}/freshness/check` | 按需单表检查 |
| GET | `/v1/tables/{id}/freshness/history?limit=` | 结果历史 |
| GET | `/v1/workflows/{id}/freshness` | 工作流关联表（读取与写出）：状态汇总 + 每次运行问题表数 + 逐表最新结果（只读） |

所有 freshness 端点都不写 `inspection_issue`。

保存契约时的校验：`loadedAtField` 必须命中该表真实列名；`loadedAtQuery` 必须以 `SELECT` 开头且不含分号/注释符（上限 2048）；`filter` 不含分号/注释符（上限 512）；`loadedAtField` 与 `loadedAtQuery` 互斥；`partition` 模式要求表有分区列。

## 与 dbt 的对齐与偏离

| 维度 | dbt | 本实现 |
| --- | --- | --- |
| 未配置的资源 | 不检查 | 一致 |
| 阈值比较 | `age > threshold` | 一致（严格大于） |
| 判定顺序 | 先 error 后 warn | 一致 |
| 配置继承 | `merge_freshness` 合并 source/table 两层 | 简化：只有表级一层，每表各自声明 SLA |
| 结果产物 | `target/sources.json` | 落 `table_freshness_result` 表 + `freshness_status` |
| 问题追踪 | 无（红了靠 CI 失败） | 不建 inspection_issue，靠状态与结果列表 |
| 自定义取数 | 1.10 `loaded_at_query` 标量子查询 | 一致（`custom_sql`） |
| 元数据取数 | 无字段时自动走 metadata | 偏离：metadata 为显式可选项，不自动兜底，并标注局限 |
| 分区取数 | 无（可用表达式塞进字段） | 补充：`partition` 模式零扫描 |
| 从未加载 | 哨兵值让 age 溢出成 error | 偏离：显式 `error` + `reason = never_loaded` |
| 结果产物 | `target/sources.json` | 偏离：落 `table_freshness_result` 表 |
| 触发方式 | 独立命令，不随 `dbt build` | 补充：定时 / 按需 / 工作流完成后 |

## 常见故障排查

- **结果一直 `runtime_error`**：检查契约的时间字段/分区格式/自定义查询是否正确、检查账号是否有该表读取权限、Doris 连通性与表是否存在。
- **`partition` 模式总是 error**：确认 `partitionFormat` 匹配分区名中的数字串，以及阈值是否按 T+1 的 age 语义设置（日表 age 恒 ≥ 24h）。
- **`metadata` 模式看着正常但数据其实是旧的**：这是该模式的固有局限，换用 `column` / `custom_sql` / `partition`。
- **配了契约却不检查**：确认 `enabled` 未被置为关闭、且至少配置了一档阈值（warn 或 error），否则视为未配置。
