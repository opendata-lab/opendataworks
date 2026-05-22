# 后端架构

## 分层结构

```
Controller Layer    ← REST API 入口
       │
Service Layer       ← 业务逻辑
       │
Mapper Layer        ← MyBatis-Plus 数据访问
       │
MySQL               ← 持久化
```

## 核心模块

### 数据资产（DataTable）

- `DataTableController` — 表 CRUD、分页查询、层级筛选
- `DataTableService` — 表管理业务逻辑
- `DataFieldService` — 字段管理

### 任务管理（DataTask）

- `DataTaskController` — 任务 CRUD、执行触发
- `DataTaskService` — 任务配置与调度逻辑
- `DolphinService` — DolphinScheduler OpenAPI 集成

### 数据血缘（Lineage）

- `LineageController` — 血缘查询 API
- `LineageService` — 血缘关系计算与缓存

### 执行监控

- `TaskExecutionController` — 执行历史查询
- `TaskExecutionService` — 状态同步与日志管理

## DolphinScheduler 集成

后端通过 WebFlux 异步调用 DolphinScheduler OpenAPI：

1. 创建/更新工作流定义
2. 上线/下线工作流
3. 触发工作流执行
4. 查询执行状态和日志

配置存储在数据库 `dolphin_config` 表中，支持运行时动态修改。

## 数据库迁移

使用 Flyway 管理数据库 schema 变更：

- 迁移脚本位于 `backend/src/main/resources/db/migration/`
- 命名规则：`V{version}__{description}.sql`
- 服务启动时自动执行未应用的迁移
