# 工作流 API

## 任务管理

### 查询任务列表

```
GET /api/dataTask/page
```

**参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| pageNum | int | 页码 |
| pageSize | int | 每页条数 |
| taskName | string | 任务名模糊搜索 |

### 创建任务

```
POST /api/dataTask
```

**请求体**:

```json
{
  "taskName": "ods_to_dwd_user",
  "taskType": "SQL",
  "datasourceId": 1,
  "sqlContent": "INSERT INTO dwd_user SELECT * FROM ods_user_info",
  "priority": "MEDIUM",
  "timeout": 300,
  "retryTimes": 3
}
```

### 执行任务

```
POST /api/dataTask/{id}/execute
```

### 查询执行历史

```
GET /api/taskExecution/page?taskId={taskId}
```

## 工作流

### 同步工作流到 DolphinScheduler

```
POST /api/workflow/sync
```

### 上线工作流

```
POST /api/workflow/{id}/online
```

### 下线工作流

```
POST /api/workflow/{id}/offline
```

## 血缘

### 查询血缘关系

```
GET /api/lineage/graph
```

**参数**:

| 参数 | 类型 | 说明 |
|------|------|------|
| tableId | int | 起始表 ID |
| direction | string | upstream / downstream / both |
| depth | int | 追踪深度 |
