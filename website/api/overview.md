# API 概览

OpenDataWorks 提供 RESTful API 用于与平台交互。

## 基础信息

| 项目 | 值 |
|------|-----|
| Base URL | `http://<host>:8080/api` |
| 协议 | HTTP/HTTPS |
| 格式 | JSON |
| 编码 | UTF-8 |

## API 模块

### 主后端 API（:8080）

- [元数据 API](/api/metadata) — 数据表、字段、数据域管理
- [工作流 API](/api/workflow) — 任务创建、执行、监控
- [认证 API](/api/authentication) — 用户认证与权限

### 智能查询 API（:8900）

- [智能查询 API](/api/intelligent-query) — NL2SQL 会话、消息、任务

## 通用响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": { }
}
```

## 分页参数

| 参数 | 类型 | 说明 |
|------|------|------|
| `pageNum` | int | 页码，从 1 开始 |
| `pageSize` | int | 每页条数，默认 10 |

## 错误码

| 码 | 说明 |
|----|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |
