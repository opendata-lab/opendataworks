# 建表 DDL 规范（引擎感知）

新建目标表的 DDL **必须匹配目标数据库引擎的建表规范**——不同引擎（Doris、MySQL 等）的
建表语法与约定不同,不能用同一套 DDL。平台建表的权威实现是后端
`TableCreateService` + `DorisTableEngineHandler`,本规范与其保持一致。

**首选**:用 `odw_create_table` 传结构化字段,让后端产出并执行规范 DDL(见
`reference/30-tool-recipes.md`)。**需要手写或核对 DDL 时**,严格遵循本规范。默认值见
`assets/engine-ddl-rules.json`,与后端默认严格一致。

## 通用规则

- 命名遵循 `assets/dev-policies.json`:任务/表名 `^[a-z][a-z0-9_]{2,63}$`,层前缀
  `ods/dwd/dws/dim/ads`;建议 `<层>_<主题>_<刷新方式>`(`di`=按日增量,`df`=按日全量)。
- 每一列都写 `COMMENT`;显式声明 `NOT NULL` / `NULL`;需要默认值时显式写 `DEFAULT`。
- 先用 `odw_get_table_ddl` / `odw_search_tables` 核实上游表与字段,再决定目标表字段
  与类型,不臆造。
- 避免使用引擎保留字作为库/表/列名。

## Doris（ENGINE=OLAP）

### 骨架（对齐后端 `DorisTableEngineHandler.buildCreateDdl`）

```sql
CREATE TABLE `db_name`.`table_name` (
  `col_a` BIGINT NOT NULL COMMENT '...',
  `col_b` VARCHAR(64) NULL COMMENT '...'
) ENGINE=OLAP
<表模型> KEY(`k1`, `k2`)
COMMENT '表注释'
PARTITION BY RANGE(`dt`) ()
DISTRIBUTED BY HASH(`k1`) BUCKETS 10
PROPERTIES (
  "replication_num" = "3"
);
```

### 表模型（三选一,建表即固定）

| 模型 | 关键字 | 用途 |
| --- | --- | --- |
| **明细表** | `DUPLICATE KEY(...)`（默认） | 保留每行明细、不去重。ODS/DWD 明细层首选。 |
| 聚合表 | `AGGREGATE KEY(...)` | 建表即固定聚合方式(SUM/REPLACE/…),写入自动预聚合;建后列变更受限。 |
| 主键表 | `UNIQUE KEY(...)` | 按 KEY 去重/ upsert,同键覆盖。需要主键唯一或更新语义时用。 |

- 未指定时后端默认 `DUPLICATE`。
- **KEY 列(`keyColumns`)必须前置且顺序敏感**——它同时是排序键与前缀索引,把高频等值/
  范围过滤列按选择性从高到低放前面。

### 分区

- 大表(尤其带时间维度)用 `PARTITION BY RANGE(<时间列>)` 按时间分区;分区列通常是
  `dt`/`event_date` 等 DATE/DATETIME 列。
- 可选动态分区,用 `PROPERTIES` 的 `dynamic_partition.*`(如 `dynamic_partition.enable`、
  `dynamic_partition.time_unit`、`dynamic_partition.end`、`dynamic_partition.replication_num`)
  自动滚动创建/回收分区。
- 小维表/字典表可不分区。

### 分桶(DISTRIBUTED BY)与分桶数量建议

- `DISTRIBUTED BY HASH(<分桶键>) BUCKETS <n>`;后端默认 `n=10`。
- 分桶键选**高基数、查询高频的等值列**(常为 join / 过滤键),避免用低基数或倾斜列,否则
  数据分布不均、单 tablet 过大。
- **分桶数量建议**:
  - 目标单 bucket 数据量约 `1–10GB`。
  - **非分区表**按全表体量估算桶数;**分区表**按**单个分区**体量估算(总桶数 = 分区数 × 每分区桶数)。
  - 小表最少 `1–10` 桶即可,不要过度分桶(桶过多会放大元数据与调度开销)。
  - 桶数一般取 BE 节点数的整数倍,便于均衡。

### 副本数(replication_num)

- 生产默认 `3`(高可用);本地/测试单 BE 环境用 `1`。
- 后端 `buildCreateDdl` 写 `"replication_num" = "<n>"`;部分较新 Doris 集群用
  `"replication_allocation" = "tag.location.default: <n>"` 表达副本,二者语义等价,平台解析
  两种写法。

### PROPERTIES

- 一般只需 `"replication_num"`。`storage_format` / `compression` 用 Doris 默认即可,**无需显式写**。

### 数据类型注意

- **无 `UNSIGNED`**;超过 BIGINT 范围用 `LARGEINT`。
- 时间用 `DATE` / `DATETIME`(无隐式默认,需要默认值显式写)。
- `DECIMAL(p, s)` 显式精度;金额类避免用浮点。
- 变长字符串 `VARCHAR(n)` 按**字节**计长;超长文本用 `STRING`。

### 变更约束(供后续改表时参考)

- `AGGREGATE` 表的字段变更需指定聚合方式,平台不支持在线自动同步这类变更。
- Doris 不支持在线新增/修改主键(KEY)列。
- 修改分桶数需已存在分桶键。

### 完整示例（dwd 每日增量明细表）

```sql
CREATE TABLE `dwd`.`dwd_user_order_di` (
  `dt` DATE NOT NULL COMMENT '分区日期',
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `amount` DECIMAL(18, 2) NULL COMMENT '订单金额',
  `status` VARCHAR(32) NULL COMMENT '订单状态'
) ENGINE=OLAP
DUPLICATE KEY(`dt`, `order_id`)
COMMENT '用户订单明细（每日增量）'
PARTITION BY RANGE(`dt`) ()
DISTRIBUTED BY HASH(`order_id`) BUCKETS 10
PROPERTIES (
  "replication_num" = "3"
);
```

## MySQL（知识先行）

> 后端建表工具当前仅执行 Doris DDL;MySQL 目标表的 DDL 规范用于**生成/核对 SQL、写入
> 任务定义**,暂不经 `odw_create_table` 直接执行。

### 规则

- `ENGINE=InnoDB`、`DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci`。
- 显式 `PRIMARY KEY`;高频过滤/join 列建二级索引(`KEY idx_xxx (col)`),避免冗余索引。
- 自增主键用 `BIGINT UNSIGNED AUTO_INCREMENT`。
- 时间戳:业务时间用 `DATETIME`;记录行创建/更新时间可用 `TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
  (注意时区语义)。
- 布尔用 `TINYINT(1)`;需要非负整数用 `UNSIGNED`(与 Doris 相反,MySQL 支持)。
- 大文本用 `TEXT`/`LONGTEXT`,不要塞进 `VARCHAR`。

### 完整示例

```sql
CREATE TABLE `ods_user_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
  `nickname` VARCHAR(64) NULL COMMENT '昵称',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户档案';
```

## 与建表工具的关系

- 优先 `odw_preview_create_table` 预览(后端按本规范生成表名 + 规范化 DDL)→ 确认 →
  `odw_create_table` 执行(高危,批准即建表)。
- 直接给 `dorisDdl` 时,DDL 内的表名需与按命名规范生成的表名一致,否则以结构化字段为准由
  后端构建,避免名称与 DDL 不一致。
