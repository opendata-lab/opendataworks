# 工作流数据新鲜度支持读取表（输入依赖表）执行计划

**Date:** 2026-09-17
**Design:** `docs/design/2026-09-17-workflow-freshness-include-read-tables-design.md`

## 1. 任务拆解与涉及文件

### Task 1: Mapper 查询与 DTO 增强
- `backend/src/main/java/com/onedata/portal/dto/WorkflowTableRelationItem.java`:
  新建或声明工作流关联表 DTO（包含 `tableId` 与 `relationType`）。
- `backend/src/main/java/com/onedata/portal/mapper/TableTaskRelationMapper.java`:
  添加 `selectWorkflowTableRelations(Long workflowId)` 查询所有未删除任务的 read / write 关联。
- `backend/src/main/java/com/onedata/portal/dto/WorkflowFreshnessResponse.java`:
  `TableStatus` 扩展 `relationType`；`Summary` 扩展 `writeCount` 与 `readCount`。
- `backend/src/test/java/com/onedata/portal/mapper/TableTaskRelationMapperWorkflowTablesSqlTest.java`:
  编写 DuckDB 内存测试，锁定 `selectWorkflowTableRelations` 注解 SQL。

### Task 2: 业务逻辑与触发器扩展
- `backend/src/main/java/com/onedata/portal/service/freshness/TableFreshnessService.java`:
  在 `workflowFreshness(workflowId)` 中基于关联项计算各表的 `relationType`（`write` / `read` / `both`），统计 `writeCount` 与 `readCount`。
- `backend/src/main/java/com/onedata/portal/service/freshness/WorkflowFreshnessTrigger.java`:
  将触发待检查表范围扩展为工作流关联的所有有效表（读 + 写）。
- `backend/src/test/java/com/onedata/portal/service/freshness/WorkflowFreshnessTriggerTest.java`:
  更新单测以覆盖读表与写表并存时的批量下发。
- `backend/src/test/java/com/onedata/portal/service/freshness/TableFreshnessServiceTest.java`:
  更新单测以覆盖读取表、写出表及统计指标的正确性。

### Task 3: 前端视图与交互更新
- `frontend/src/views/workflows/WorkflowDetail.vue`:
  - 更新 Summary 标签（全部 / 写出 / 读取）。
  - 更新标题为「关联表最新状态」，增加「全部 / 仅写出 / 仅读取」筛选器。
  - 增加「角色/类型」Tag 列。

### Task 4: 手册文档更新
- `docs/handbook/features/data-freshness.md`:
  同步更新使用手册。

## 2. 验证与回滚
- DuckDB SQL 测试 + 后端单元测试：`mvn test -Dtest=TableTaskRelationMapperWorkflowTablesSqlTest,TableTaskRelationMapperWriteTablesSqlTest,WorkflowFreshnessTriggerTest,TableFreshnessServiceTest`。
- 前端测试：`npm --prefix frontend run test:unit`。
- 若需回滚，仅需恢复上述改动文件，数据库模式无任何 DDL 变更，无迁移风险。
