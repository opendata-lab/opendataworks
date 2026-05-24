# 元数据 API

本模块定义了数据表设计、物理同步、软删除生命周期回收站、以及存储/访问热度分析相关的接口。

**Base URL**: `http://<host>:8080/api` (Spring Boot 默认 context-path 为 `/api`)

---

## 1. 数据表管理

### 分页查询表列表
查询已注册或同步的元数据表列表，支持按物理层级、搜索词及 Doris 数据源集群筛选。

* **路径**: `GET /api/v1/tables`
* **参数**:

| 参数 | 类型 | 是否必填 | 说明 |
|------|------|----------|------|
| `pageNum` | int | 否 | 页码，默认 1 |
| `pageSize` | int | 否 | 每页条数，默认 20 |
| `layer` | string | 否 | 数据层级过滤 (如 ODS, DWD, ADS) |
| `keyword` | string | 否 | 库名或表名模糊检索词 |
| `clusterId` | long | 否 | Doris 集群数据源 ID |
| `sortField` | string | 否 | 排序字段 (如 `tableName`, `row_count`, `data_size`) |
| `sortOrder` | string | 否 | 排序顺序 (`asc` / `desc`) |

---

### 创建数据表
在本地元数据仓中创建一张逻辑表。

* **路径**: `POST /api/v1/tables`
* **请求体 (JSON)**:

```json
{
  "tableName": "ods_user_log",
  "dbName": "opendataworks",
  "tableComment": "用户行为日志表",
  "layer": "ODS",
  "clusterId": 1,
  "tableModel": "DUPLICATE KEY",
  "distributionColumn": "user_id",
  "bucketNum": 10,
  "replicaNum": 3,
  "keyColumns": "user_id, event_time",
  "partitionColumn": "event_time"
}
```

---

### 获取数据表详情
根据 ID 查询该表的完整定义，包括分表参数、生命周期与集群配置。

* **路径**: `GET /api/v1/tables/{id}`

---

### 更新数据表
更新表的模型定义，支持自动向下同步更新 Doris 物理表的 bucket/replica 参数。

* **路径**: `PUT /api/v1/tables/{id}`
* **参数**:
  * `clusterId` (long, 否): 操作同步的物理数据源 ID。

---

### 修改表注释
直接更新表注释，同步执行 Doris ALTER 语句。

* **路径**: `PUT /api/v1/tables/{id}/comment`
* **参数**:
  * `clusterId` (long, 否): 数据源 ID。
* **请求体 (JSON)**:

```json
{
  "comment": "新用户信息及访问审计表"
}
```

---

## 2. 软删除回收站生命周期

### 软删除表 (Soft-Delete)
将表逻辑标记为废弃（`deprecated`），并在 Doris 中重命名为 `tableName_deprecated_时间戳`，启动 30 天安全保留倒计时。

* **路径**: `POST /api/v1/tables/{id}/soft-delete`
* **参数**:

| 参数 | 类型 | 是否必填 | 说明 |
|------|------|----------|------|
| `clusterId` | long | 否 | 目标物理数据源 ID |
| `confirmTableName` | string | 是 | 强制安全校验，必须与要废弃的物理表名完全一致 |

---

### 待删除表列表
获取回收站中所有处于废弃待清理阶段的表。

* **路径**: `GET /api/v1/tables/pending-deletion`
* **参数**:
  * `clusterId` (long, 否): Doris 集群数据源 ID。
* **响应结构示例 (`data` 内)**:

```json
[
  {
    "id": 12,
    "clusterId": 1,
    "dbName": "opendataworks",
    "tableName": "ods_user_log_deprecated_20260524220000",
    "originTableName": "ods_user_log",
    "tableComment": "用户行为日志表",
    "status": "deprecated",
    "deprecatedAt": "2026-05-24 22:00:00",
    "purgeAt": "2026-06-23 22:00:00",
    "remainingDays": 30
  }
]
```

---

### 恢复待删除表 (Restore)
从回收站中恢复表，将其还原为原表名，并清空清理倒计时。

* **路径**: `POST /api/v1/tables/{id}/restore`
* **参数**:
  * `clusterId` (long, 否): Doris 集群数据源 ID。

---

### 立即物理清除表 (Purge Now)
绕过 30 天宽限期，在 Doris 中执行物理 `DROP TABLE` 彻底删除，并擦除本地元数据。

* **路径**: `POST /api/v1/tables/{id}/purge-now`
* **参数**:
  * `clusterId` (long, 否): Doris 集群数据源 ID。
  * `confirmTableName` | string | 是 | 必须输入当前带 deprecated 后缀的完整物理表名。

---

## 3. 字段管理

### 查询表字段
获取指定表关联的字段配置元数据。

* **路径**: `GET /api/v1/tables/{id}/fields`

---

### 新增字段
向表中添加字段，若该表对应 Doris 物理表，则会在 Doris 中同步执行 `ALTER TABLE ... ADD COLUMN`。

* **路径**: `POST /api/v1/tables/{id}/fields`
* **参数**:
  * `clusterId` (long, 否): 物理数据源 ID。
* **请求体 (JSON)**:

```json
{
  "fieldName": "new_column_desc",
  "fieldType": "VARCHAR(256)",
  "fieldComment": "新列注释",
  "isPrimaryKey": false,
  "isNullable": true
}
```

---

### 更新字段
修改字段名称、类型或注释，若为 Doris 表则自动在 Doris 执行物理变更。

* **路径**: `PUT /api/v1/tables/{id}/fields/{fieldId}`

---

### 删除字段
从表元数据中移除字段，同时在 Doris 中执行 `DROP COLUMN`。

* **路径**: `DELETE /api/v1/tables/{id}/fields/{fieldId}`

---

## 4. 表分析与度量监控

### 获取表统计指标 (Statistics)
返回数据表行数、存储空间大小与索引空间。内设 **5分钟** Redis 缓存。

* **路径**: `GET /api/v1/tables/{id}/statistics`
* **参数**:
  * `clusterId` (long, 否): 数据源 ID。
  * `forceRefresh` (boolean, 否): 设为 `true` 将绕过 Redis 直接查询 Doris 真实物理数据并更新缓存，默认 `false`。

---

### 获取表访问统计 (Access Stats)
获取表的热度分析，包括 30 天内访问热度趋势及高频访问用户。

* **路径**: `GET /api/v1/tables/{id}/access-stats`
* **参数**:
  * `clusterId` (long, 否): 数据源 ID。
  * `recentDays` (int, 否): 总访问量窗口天数，默认 30。
  * `trendDays` (int, 否): 趋势折线统计天数，默认 14。
  * `topUsers` (int, 否): Top 高频用户返回数量，默认 5。

---

### 获取历史大小/行数趋势记录 (History)
查询表的归档指标变动历史，用于渲染图表。

* **路径**: `GET /api/v1/tables/{id}/statistics/history`
* **参数**:
  * `limit` (int, 否): 获取记录的最大条数，默认 30。
* **路径 2 (快捷周期)**:
  * `GET /api/v1/tables/{id}/statistics/history/last7days` (获取最近 7 天)
  * `GET /api/v1/tables/{id}/statistics/history/last30days` (获取最近 30 天)

---

### 获取 DDL 语句
直接调取物理数据源获取其 `SHOW CREATE TABLE` DDL 构建文本。

* **路径**: `GET /api/v1/tables/{id}/ddl`
* **参数**:
  * `clusterId` (long, 否): 数据源 ID。
