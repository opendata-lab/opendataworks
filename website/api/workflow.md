# 工作流与血缘 API

本模块定义了工作流编排设计、发布审批、任务管理、运行期实例监控以及数据血缘的后端 API。

**Base URL**: `http://<host>:8080/api` (Spring Boot 默认 context-path 为 `/api`)

---

## 1. 工作流管理 (Workflows)

### 查询工作流列表
* **路径**: `GET /api/v1/workflows`
* **参数**:
  * `pageNum` (int, 否): 页码，从 1 开始
  * `pageSize` (int, 否): 每页大小，默认 20
  * `keyword` (string, 否): 名称搜索关键词
  * `status` (string, 否): 状态过滤

---

### 创建工作流
在本地元数据仓定义一个工作流定义。
* **路径**: `POST /api/v1/workflows`
* **请求体 (JSON)**:

```json
{
  "name": "dwd_to_dws_user_aggregation",
  "description": "用户行为汇总指标日跑工作流",
  "schedulerEngine": "dolphinscheduler"
}
```

---

### 获取工作流详情
包含工作流的基础信息以及关联的节点结构。
* **路径**: `GET /api/v1/workflows/{id}`

---

### 更新工作流
* **路径**: `PUT /api/v1/workflows/{id}`

---

### 删除工作流
* **路径**: `DELETE /api/v1/workflows/{id}`
* **参数**:
  * `cascadeDeleteTasks` (boolean, 否): 是否级联删除关联的 Task，默认 `false`。

---

### 版本对比 (Compare)
比对该工作流任意两个历史版本之间的拓扑及任务节点差异。
* **路径**: `POST /api/v1/workflows/{id}/versions/compare`
* **请求体 (JSON)**:

```json
{
  "v1": 1,
  "v2": 2
}
```

---

### 版本回滚 (Rollback)
将工作流重置回选定的历史版本。
* **路径**: `POST /api/v1/workflows/{id}/versions/{versionId}/rollback`

---

### 导出为 JSON
将工作流数据导出为 JSON，用于跨环境迁移。
* **路径**: `GET /api/v1/workflows/{id}/export-json`

---

### 从 DolphinScheduler 导入 (DolphinScheduler Import)
* **获取 DS 系统中可导入的工作流列表**: `GET /api/v1/workflows/import/dolphin`
  * 参数: `projectCode` (long, 否), `keyword` (string, 否)
* **导入预览**: `POST /api/v1/workflows/import/preview`
* **导入提交**: `POST /api/v1/workflows/import/commit`

---

### 发布与审批

#### 提交发布申请
* **路径**: `POST /api/v1/workflows/{id}/publish`
* **请求体 (JSON)**:

```json
{
  "comment": "升级用户行为统计 SQL，过滤异常爬虫流量"
}
```

#### 预览发布差异
* **路径**: `GET /api/v1/workflows/{id}/publish/preview`

#### 审批发布记录
* **路径**: `POST /api/v1/workflows/{id}/publish/{recordId}/approve`
* **请求体 (JSON)**:

```json
{
  "status": "APPROVED",
  "comment": "SQL 审查通过，同意发布上线"
}
```

---

### 运行与调度设置

#### 立即单次执行 (Execute)
在底层调度引擎中触发一次该工作流的立即运行。
* **路径**: `POST /api/v1/workflows/{id}/execute`

#### 补数据执行 (Backfill)
根据指定的日期范围批量回溯执行工作流。
* **路径**: `POST /api/v1/workflows/{id}/backfill`
* **请求体 (JSON)**:

```json
{
  "startRunDate": "2026-05-01 00:00:00",
  "endRunDate": "2026-05-15 00:00:00",
  "parallelism": 2
}
```

#### 配置调度策略
* **路径**: `PUT /api/v1/workflows/{id}/schedule`
* **请求体 (JSON)**:

```json
{
  "crontab": "0 0 2 * * ? *",
  "startTime": "2026-05-24 00:00:00",
  "endTime": "2036-05-24 00:00:00",
  "failureStrategy": "CONTINUE",
  "warningType": "FAILURE"
}
```

#### 开启调度上线 / 暂停调度下线
* **上线**: `POST /api/v1/workflows/{id}/schedule/online`
* **下线**: `POST /api/v1/workflows/{id}/schedule/offline`

---

## 2. 任务管理 (Tasks)

### 分页查询任务列表
* **路径**: `GET /api/v1/tasks`
* **参数**: `pageNum`, `pageSize`, `taskName`, `taskType`, `workflowId`, `upstreamTaskId`, `downstreamTaskId`。

---

### 创建任务 (Task)
在工作流中新增一个任务节点，并绑定输入、输出表。
* **路径**: `POST /api/v1/tasks`
* **请求体 (JSON)**:

```json
{
  "task": {
    "workflowId": 1,
    "taskName": "ods_to_dwd_user_log",
    "taskType": "SQL",
    "datasourceId": 1,
    "sqlContent": "INSERT INTO dwd_user_log SELECT * FROM ods_user_log WHERE user_id IS NOT NULL",
    "priority": "HIGH",
    "timeout": 600,
    "retryTimes": 3,
    "retryInterval": 1
  },
  "inputTableIds": [2, 3],
  "outputTableIds": [4]
}
```

---

### 更新任务
* **路径**: `PUT /api/v1/tasks/{id}`

---

### 删除任务
* **路径**: `DELETE /api/v1/tasks/{id}`

---

### 获取任务最近一次执行状态
* **路径**: `GET /api/v1/tasks/{id}/execution-status`

---

### 查询单个任务的血缘关系 (输入/输出表)
* **路径**: `GET /api/v1/tasks/{id}/lineage`

---

## 3. 执行监控 (Executions)

### 分页查询任务执行历史
* **路径**: `GET /api/v1/executions/history`
* **参数**:
  * `taskId` (long, 否): 任务 ID 过滤
  * `pageNum` (int, 否): 默认 1
  * `pageSize` (int, 否): 默认 10

---

### 获取单个执行记录详情与日志
* **路径**: `GET /api/v1/executions/{id}`

---

### 同步执行状态
主动从 DolphinScheduler 拉取该次运行的最新状态并写入本地缓存。
* **路径**: `POST /api/v1/executions/{id}/sync`

---

### 获取执行统计信息
* **路径**: `GET /api/v1/executions/statistics`
* **参数**:
  * `taskId` (long, 否): 任务 ID
  * `startTime` (string, 否): `yyyy-MM-dd HH:mm:ss`
  * `endTime` (string, 否): `yyyy-MM-dd HH:mm:ss`

---

### 运行中 / 失败任务列表
* **正在运行列表**: `GET /api/v1/executions/running`
* **失败运行列表**: `GET /api/v1/executions/failed?limit=50`

---

## 4. 全局数据血缘 (Lineage)

### 获取全局血缘拓扑图数据
根据指定的节点或范围，获取全链路数据表及依赖的 DAG 数据。

* **路径**: `GET /api/v1/lineage`
* **参数**:

| 参数 | 类型 | 是否必填 | 说明 |
|------|------|----------|------|
| `tableId` | long | 否 | 起始锚点数据表 ID。如果不传，则返回全局拓扑图。 |
| `depth` | int | 否 | 向上游或下游追溯的最大深度（层数） |
| `layer` | string | 否 | 筛选只显示指定数据分层的节点 |
| `businessDomain` | string | 否 | 过滤业务域名称 |
| `dataDomain` | string | 否 | 过滤数据域名称 |
| `dbName` | string | 否 | 过滤数据库名称 |
| `clusterId` | long | 否 | 数据源 ID 过滤 |
| `keyword` | string | 否 | 表名过滤关键词 |

* **响应结构示例 (`data` 内)**:

```json
{
  "nodes": [
    {
      "id": "1",
      "tableName": "ods_user_log",
      "layer": "ODS",
      "dbName": "opendataworks"
    },
    {
      "id": "2",
      "tableName": "dwd_user_log",
      "layer": "DWD",
      "dbName": "opendataworks"
    }
  ],
  "edges": [
    {
      "source": "1",
      "target": "2"
    }
  ]
}
```
