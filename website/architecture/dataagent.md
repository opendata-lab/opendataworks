# 智能查询架构

## 系统组成

```
前端 (SSE)
    │
    ▼
DataAgent Backend (FastAPI :8900)
    ├── API Layer        ← 会话/任务/消息 REST API
    ├── Task Coordinator ← 异步任务调度
    ├── NL2SQL Agent     ← Claude Agent SDK
    └── Session Store    ← MySQL (dataagent schema)
    │
    ├── Redis            ← 任务队列
    └── MySQL            ← 会话与消息持久化
```

## 核心流程

### 同步问答

1. 用户发送消息 → `POST /api/v1/nl2sql/topics/{id}/messages`
2. 创建异步任务 → Task Coordinator 接管
3. Agent 执行 NL2SQL → 生成 SQL、执行查询
4. SSE 流式返回结果 → 前端实时渲染

### 异步任务

1. 请求接受 → 返回 `task_id`
2. Coordinator 拾取任务 → 状态 `waiting → running`
3. Agent 执行 → 事件流持久化
4. 完成 → 状态 `success/failed`，消息写入会话

## 会话管理

- **Topic** — 一个对话主题，包含多条消息
- **Message** — 用户或助手的单条消息
- **Task** — 一次 Agent 执行的生命周期

## 数据存储

| 表 | Schema | 说明 |
|----|--------|------|
| `da_topics` | dataagent | 会话主题 |
| `da_messages` | dataagent | 消息记录 |
| `da_tasks` | dataagent | 任务状态 |
| `da_task_events` | dataagent | 任务事件流 |
| `da_agent_settings` | dataagent | Agent 配置 |

## 部署模式

DataAgent Backend 作为独立服务部署：

- 端口：8900
- 依赖：MySQL（会话存储）、Redis（任务协调）
- Task Coordinator 内嵌于 `main.py`，无需单独启动 worker
