# 智能查询 API

Base URL: `http://<host>:8900/api/v1/nl2sql`

## 健康检查

```
GET /api/v1/nl2sql/health
```

## 会话管理

### 创建会话

```
POST /api/v1/nl2sql/topics
```

**请求体**:

```json
{
  "title": "数据分析会话"
}
```

### 查询会话列表

```
GET /api/v1/nl2sql/topics
```

### 获取会话详情

```
GET /api/v1/nl2sql/topics/{topic_id}
```

### 获取会话消息

```
GET /api/v1/nl2sql/topics/{topic_id}/messages
```

## 消息与任务

### 发送消息（创建任务）

```
POST /api/v1/nl2sql/tasks/deliver-message
```

**请求体**:

```json
{
  "topic_id": "uuid",
  "content": "最近 30 天工作流发布次数趋势"
}
```

**响应**:

```json
{
  "accepted": true,
  "task_id": "uuid"
}
```

### 查询任务状态

```
GET /api/v1/nl2sql/tasks/{task_id}
```

**状态流转**: `waiting` → `running` → `success` / `failed` / `suspended`

### 获取任务事件流

```
GET /api/v1/nl2sql/tasks/{task_id}/events/stream
```

返回 SSE 流，实时推送 Agent 执行事件。

### 取消任务

```
POST /api/v1/nl2sql/tasks/{task_id}/cancel
```

## 事件类型

| 类型 | 说明 |
|------|------|
| `thinking` | Agent 思考过程 |
| `tool_call` | 工具调用（SQL 生成等） |
| `tool_result` | 工具执行结果 |
| `message` | 最终回复消息 |
| `error` | 错误信息 |
