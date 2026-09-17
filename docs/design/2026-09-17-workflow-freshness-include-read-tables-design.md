# 工作流数据新鲜度支持读取表（输入依赖表）设计

**Date:** 2026-09-17
**Goal:** 扩展工作流「数据新鲜度」模块，将工作流读取的表（输入表/上游依赖表，`relation_type = 'read'`）纳入新鲜度体系，支持在工作流维度统一观测输入数据与产出数据的新鲜度状态，并在工作流成功时支持对配置了契约的关联表统一触发检查。
**Tech Stack:** Java 8 · Spring Boot 2.7 · MyBatis-Plus · Vue 3 · Element Plus · DuckDB (测试)。

---

## 1. 背景与现状 (Current State)

在当前实现中（参见 `docs/design/2026-08-05-dbt-style-freshness-check-design.md` 与 `docs/handbook/features/data-freshness.md`）：
- 新鲜度检查契约是**表级显式契约**（`table_freshness_config`）。
- 检查机制采用**事件驱动**（工作流完成后自动触发）与**按需手动触发**。
- 工作流层面的关联仅聚焦在**写出表**：
  - `WorkflowFreshnessTrigger.onWorkflowSucceeded`: 通过 `tableTaskRelationMapper.selectWriteTableIdsByWorkflow(workflowId)` 只查询并检查 `relation_type = 'write'` 的表。
  - `TableFreshnessService.workflowFreshness(workflowId)`: 只拉取写出表及其状态，构建 `WorkflowFreshnessResponse`。
  - 工作流详情页 `WorkflowDetail.vue`: 页签标题、统计与表格仅展示「写出表最新状态」，文案为「写出表 X 正常 Y 预警 Z 过期 W」。

## 2. 问题与诉求 (Problem & Requirements)

在实际数仓与 ETL/ELT 运维场景中：
1. **上游输入依赖是数据质量与新鲜度事故的高发区**：
   下游工作流即使自身执行成功，如果消费的**上游输入表（`relation_type = 'read'`）**本身未按时更新或数据过期，下游产出的报表或指标同样会失真（Garbage In, Garbage Out）。
2. **工作流运维视角割裂**：
   工作流负责人无法在工作流详情页一站式查看该任务所有依赖的输入源表是否新鲜，必须逐个跳去 Data Studio 单表查看，体验割裂。
3. **外部输入表的刷新留痕**：
   对于部分上游是由外部同步工具（如 DataX/Flink CDC）直接写入、在平台没有对应上游工作流的源表，当下游工作流执行完成时，若能一并对这些输入表进行新鲜度探针，可以有效留存下游消费时点输入表的新鲜度快照。

## 3. 详细设计 (Design)

### 3.1 关系模型与分类 (Relation Model)

一个工作流可能包含多个任务。同一张表与该工作流的关系可能有三种情况：
- **`write` (写出/产出表)**：工作流中仅有任务向该表写入。
- **`read` (读取/输入表)**：工作流中仅有任务从该表读取。
- **`both` (读写表)**：工作流中既有任务读取该表，也有任务（或后续节点）写入该表（例如分阶段自更新或回写）。

### 3.2 触发机制 (Workflow Trigger Scope)

在 `WorkflowFreshnessTrigger.onWorkflowSucceeded(workflowId, instanceId)` 中：
- 将待检查表的范围由「仅写出表」扩展为「工作流有效关联的所有表（写出表 + 读取表）」。
- 由于 `FreshnessCheckService.checkBatch` 内部天然会对表进行契约解析（`contractResolver.resolve(table, config)`），**未配置契约的表会自动忽略、不发起探针、不落库结果**，因此扩展关联表不会对未配置新鲜度契约的普通输入表带来额外的数据库连接开销或探针负载。
- 对配置了契约的输入表执行只读探针，能真实留痕「本次工作流运行成功时，输入源表的年龄与时效状态」，其实例 ID 落库便于追溯。

### 3.3 数据传输契约 (DTO Contract)

在 `WorkflowFreshnessResponse` 中增加关系类型并细化 Summary：

1. **`TableStatus` 实体**：
   ```java
   public static class TableStatus {
       private Long tableId;
       private String dbName;
       private String tableName;
       /** 关联类型：write (写出) | read (读取) | both (读写) */
       private String relationType;
       private boolean configured;
       private String status; // pass | warn | error | runtime_error
       private LocalDateTime maxLoadedAt;
       private Long ageSeconds;
       private LocalDateTime checkedAt;
   }
   ```

2. **`Summary` 实体**：
   ```java
   public static class Summary {
       private int total;          // 关联表总数
       private int writeCount;     // 写出表数 (含 write 与 both)
       private int readCount;      // 读取表数 (含 read 与 both)
       private int pass;
       private int warn;
       private int error;
       private int runtimeError;
       private int unconfigured;
   }
   ```

3. **`Run` 实体**：
   保序聚合实例对应的所有关联表检查结果，准确反映该实例运行下的总问题表数与覆盖表数。

### 3.4 前端界面呈现 (UI Presentation)

遵循前端设计规范（扁平结构、不引入无意义嵌套卡片、保持两层边框）：
1. **统计工具栏**：
   - 标签展示：`全部关联表 {total}`、`写出 {writeCount}`、`读取 {readCount}`、`正常 {pass}`、`预警 {warn}`、`过期 {error}` 等。
2. **列表区域**：
   - 标题调整为「关联表最新状态」。
   - 新增一列「角色/关系」：
     - `写出`（Success 浅绿 Tag）
     - `读取`（Info 浅蓝 Tag）
     - `读写`（Primary 浅紫/深蓝 Tag）
   - 在表头工具区提供快捷筛选：`全部 / 仅写出 / 仅读取`，用户可一键聚焦输出或输入。

---

## 4. 接口与 SQL 变更

### 4.1 Mapper 查询
在 `TableTaskRelationMapper` 中保留现有的 `selectWriteTableIdsByWorkflow`（确保兼容），新增关联关系全量查询：
```java
@Select("SELECT r.table_id, r.relation_type " +
    "FROM table_task_relation r " +
    "JOIN workflow_task_relation wtr ON r.task_id = wtr.task_id " +
    "JOIN data_task t ON r.task_id = t.id " +
    "WHERE wtr.workflow_id = #{workflowId} " +
    "  AND wtr.deleted = 0 " +
    "  AND r.deleted = 0 " +
    "  AND t.deleted = 0")
List<WorkflowTableRelationItem> selectWorkflowTableRelations(Long workflowId);
```
服务层根据返回项合并计算每个 `table_id` 的 `relationType`（`"write"` / `"read"` / `"both"`）。

### 4.2 单元测试保障
使用内存 DuckDB（Java 8 兼容的 `duckdb_jdbc`）编写 `TableTaskRelationMapperWorkflowTablesSqlTest`，校验：
1. 包含 `read`、`write` 关系的正常映射；
2. 软删除的任务、工作流关联或表关系均被精准过滤；
3. 多任务交叉读写同一张表时能够正确查出并聚合。
