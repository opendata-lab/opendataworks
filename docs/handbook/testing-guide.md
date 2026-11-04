# 测试与质量

Java 17 四模块构建、内置 MCP 和本地前后端运行见[后端运行说明](backend-runtime.md)。

整合 `MANUAL_TEST_GUIDE.md`、`workflow-integration-test-guide.md`、`integration-test-summary.md`、`TASK_EXECUTION_WORKFLOW_LIFECYCLE.md`、`BROWSER_TEST_RESULTS.md` 中的信息，形成可执行的质量指引。

## 测试分层

| 层级 | 覆盖内容 | 入口 |
| --- | --- | --- |
| 单元/服务测试 | Mapper、Service、调度编排 | `mvn -f backend/pom.xml -Dtest='*Test,!*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`，可通过 `-Dtest=WorkflowExecutionServiceTest` 定点 |
| 集成测试 | 真正调用 DolphinScheduler OpenAPI、MySQL | `backend/scripts/run-integration-test.sh` |
| 手工回归 | 浏览器操作、工作流生命周期、异常场景 | 本指南 “手工测试剧本” |
| 前端冒烟 | 关键页面渲染、交互 | `docs/reports/BROWSER_TEST_RESULTS.md` 中列出的脚本 |

## 手工测试剧本

### 1. 工作流生命周期

1. 登录 DolphinScheduler (默认 `admin/dolphinscheduler123`)，统计当前工作流数量。
2. 在 Portal 前端执行 `sample_batch_user_daily` 任务；如前端未启动，可调用 API：
   ```bash
   curl -X POST http://localhost:8080/api/v1/workflows/1/execute
   ```
3. 回到 DolphinScheduler → Workflow Definition 页面，确认多出 `test-task-<timestamp>`。
4. 手动删除（OpenAPI 示例）：
   ```bash
   # 从 "系统管理 -> Dolphin 配置" 或 dolphin_config 表获取 URL、Token、ProjectCode
   DOLPHIN_URL="http://localhost:12345/dolphinscheduler"
   DOLPHIN_TOKEN="<从系统配置获取>"
   PROJECT_CODE=<from_dolphinscheduler_ui>
   WORKFLOW_CODE=123456789

   curl -X DELETE "$DOLPHIN_URL/projects/$PROJECT_CODE/process-definition/$WORKFLOW_CODE" \
        -H "token: $DOLPHIN_TOKEN"
   ```
5. 刷新页面确认工作流数量恢复；Portal 侧 `task_execution_log` 存在执行记录，`data_lineage` 不残留孤儿记录。

### 2. SQL 任务端到端

1. 在 Portal 新建任务 (SQL/Shell) 并配置调度策略。
2. 检查 `table_task_relation` 自动写入的 read/write 关系。
3. 在 DolphinScheduler 启动工作流，确认日志回传 Portal；失败场景需写入 `error_message`。
4. 对于 Doris 表，执行 `刷新统计`，确认 `table_statistics_history` 新增记录。

### 3. 前端页面巡检

- 依据 `docs/reports/BROWSER_TEST_RESULTS.md`，在 Chrome/Edge 最新版上手动点击：表详情 → 统计图表 → 血缘 DAG → 任务执行记录 → 巡检中心。
- 若发现 UI 与后端能力不一致，记录在 `docs/reports/TEST_REPORT.md`。

## 自动化脚本

| 脚本 | 功能 |
| --- | --- |
| `backend/scripts/prepare-test-env.sh` | 校验 DolphinScheduler 连接并给出基础环境准备提示（遗留脚本） |
| `backend/scripts/run-integration-test.sh` | 真实 WorkflowRuntimeSync 集成测试入口 |
| `mvn -f backend/pom.xml -Dtest=WorkflowRuntimeDiffServiceTest,WorkflowRuntimeSyncServicePreviewTest,WorkflowVersionOperationServiceTest,WorkflowRuntimeSyncRealIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | 运行运行态同步/版本治理完整集成回归 |

运行示例：

```bash
# 推荐：直接运行完整回归集
mvn -f backend/pom.xml \
  -Dtest=WorkflowRuntimeDiffServiceTest,WorkflowRuntimeSyncServicePreviewTest,WorkflowVersionOperationServiceTest,WorkflowRuntimeSyncRealIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## DolphinScheduler 集成测试

### 覆盖范围
- 正向发布（设计态 -> 运行态）：创建/更新任务、发布、上线、调度配置同步。
- 反向同步（运行态 -> 设计态）：预检、执行同步、差异比对、同步历史记录。
- 血缘驱动关系重建：基于 SQL 自动解析输入/输出表，重建 `table_task_relation` 和 `workflow_task_relation`。
- 版本治理：版本快照、版本比较、版本回退相关后端契约。
- 真实执行校验：在真实 MySQL 表上执行工作流并核对结果数据。

### 关键测试类
- `backend/app/src/test/java/com/onedata/portal/service/WorkflowRuntimeSyncRealIntegrationTest.java`
- `backend/core/src/test/java/com/onedata/portal/service/WorkflowRuntimeSyncServicePreviewTest.java`
- `backend/core/src/test/java/com/onedata/portal/service/WorkflowRuntimeDiffServiceTest.java`
- `backend/core/src/test/java/com/onedata/portal/service/WorkflowVersionOperationServiceTest.java`

### 测试前准备
1. 启动 DolphinScheduler，本地默认地址 `http://localhost:12345/dolphinscheduler`。
2. 启动 MySQL（可用独立库，也可直接用 `opendataworks` 库）。
3. 测试允许 Dolphin 初始为空：缺失 Token/项目/数据源/工作流由测试启动逻辑自动补齐。
4. 不要把容器重启逻辑写入测试代码；容器异常重启属于测试环境运维动作。
5. 确认本地资源足够，避免 Dolphin 因 OOM 或内存阈值保护进入过载状态。

### 配置参数
集成测试使用独立的 `DolphinConfig` 配置，通常通过环境变量 `DS_BASE_URL` 等注入。

若需调试真实集成场景，可按需导出环境变量：

```bash
export DS_BASE_URL=http://localhost:12345/dolphinscheduler
export DS_USERNAME=admin
export DS_PASSWORD=dolphinscheduler123
export DS_PROJECT_NAME=it_rt_sync_project
export IT_MYSQL_HOST=127.0.0.1
export IT_MYSQL_PORT=3306
export IT_MYSQL_DATABASE=opendataworks
export IT_MYSQL_USERNAME=opendataworks
export IT_MYSQL_PASSWORD=opendataworks123
```

### 执行方式
- 推荐：  
  `mvn -f backend/pom.xml -Dtest=WorkflowRuntimeDiffServiceTest,WorkflowRuntimeSyncServicePreviewTest,WorkflowVersionOperationServiceTest,WorkflowRuntimeSyncRealIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 仅跑真实运行态同步集成：  
  `mvn -f backend/pom.xml -Dtest=WorkflowRuntimeSyncRealIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
- IDE：直接运行上述四个测试类；排查时可打开 `-Dlogging.level.com.onedata.portal=DEBUG`。

### 必测场景清单（运行态同步）
- 至少 5-6 个任务的 DAG，必须同时包含串行、并行、多依赖汇聚。
- 使用真实 SQL、真实表、真实数据；同步后必须执行工作流并校验目标结果表数据变化。
- 工作流演进场景必须覆盖：
  - 新增任务；
  - 删除任务；
  - 修改任务 SQL；
  - 增加依赖表；
  - 减少依赖表；
  - 调度配置变化（cron/timezone/start/end）；
  - 任务组配置变化（taskGroupId/taskGroupName）。
- 同步历史与版本历史必须校验：
  - `workflow_runtime_sync_record` 写入成功/失败记录；
  - 成功同步记录关联 `version_id`；
  - 版本号递增；
  - `trigger_source=runtime_sync`；
  - 快照和 diff 内容与实际变更一致。
- 边差异策略必须校验：
  - 运行态显式边与血缘推断边不一致时，预检返回告警；
  - 执行同步需人工确认差异后继续；
  - 同步后以血缘推断边为准。

### 版本比较/回退契约回归项
- `leftVersionId` 可以为空（空基线，展示全新增）。
- `left > right` 时后端自动交换大小。
- `left == right` 返回 `VERSION_COMPARE_INVALID`。
- 回退写入新版本（`trigger_source=version_rollback`），不覆盖旧版本。
- 回退不自动发布到 Dolphin，需单独发布/上线。

### 一次性通过基线
- 完整回归命令执行结果应为：`Failures: 0, Errors: 0`。
- 参考基线：`Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`（四个测试类合计）。

### 常见问题速查
| 症状 | 排查步骤 |
| --- | --- |
| `Connection refused` | 检查服务端口、系统管理中的 Dolphin 配置 URL 与 Token |
| 登录失败 | 确认账号 `admin/dolphinscheduler123`，必要时重置密码 |
| `工作流不存在 (50003)` | Dolphin 重启后 projectCode 可能变化，先重新拉取/重建测试项目与工作流，再执行测试 |
| `权限不足` | 确认使用 admin 或具备权限的租户 |
| `JsonMappingException` | 打开 TRACE 日志，核对 DTO 与 API 响应字段 |
| `The current server is overload, cannot consumes commands` | 检查 Dolphin 日志是否出现 `reserved.memory=0.1` 触发；测试环境可下调 `application.yaml` 中 master/worker `reserved-memory` 并重启容器 |
| `delete process definition ... there are process instances in executing` | 删除前先确认实例已结束；必要时等待或重试清理 |
| `State event handle error ... SUCCESS -> RUNNING` | Dolphin 任务状态事件乱序告警，优先以任务最终状态与结果表校验为准 |

### 调试技巧
- `logging.level.reactor.netty.http.client=DEBUG` 查看 HTTP 请求。
- 在测试中对关键响应设断点，确认 Dolphin API 返回值。
- 使用 Postman/Insomnia 重放脚本中的请求，快速定位接口问题。
- 实时观察 Dolphin 容器日志，重点关键字：`overload`、`reserved.memory`、`process definition ... does not exist`。
- 当宿主机命令环境没有 `docker` 只有 `podman` 时，使用等价 `podman` 命令进行运维操作。

### 测试报告模板
```
测试时间: ______
执行人: ______
DolphinScheduler 版本: ______
测试环境: ______

| 测试用例 | 状态(PASS/FAIL) | 备注 |
| 登录并获取工作流定义 | | |
| 生成任务编码 | | |
| 下线工作流 | | |
| 添加任务到工作流 | | |
| 发布工作流 | | |
| 从工作流删除任务 | | |
| 运行态反向同步（预检/执行） | | |
| 运行态差异比对 | | |
| 工作流演进（增删任务/SQL/依赖） | | |
| 同步历史与版本历史校验 | | |
| 版本比较与回退校验 | | |

问题记录:
- 描述:
- 原因:
- 解决方案:
- 状态: (已解决/待解决)
```

## 数据验证项

- `SELECT COUNT(*) FROM task_execution_log WHERE status='failed';` → 确认失败任务被巡检捕获。
- `SELECT * FROM inspection_issue WHERE status='open';` → 验证命名/Owner/注释规则是否生效。
- `SELECT COUNT(*) FROM data_lineage` → 执行任务前后是否匹配期望。
- `SELECT COUNT(*) FROM table_statistics_history WHERE table_name='dwd_order';` → 数据统计是否持续累积。

## 缺陷复盘

- **WORKFLOW_CODE_MISMATCH_FIX.md**：记录了由于 DolphinScheduler 代码不一致导致的任务删除失败，现已修复；遇到同类问题优先查看该文档。
- **FIX_SUMMARY.md / COMPLETION_REPORT.md**：保存大版本修复/交付记录。

所有遗留问题/新发现缺陷请在 `docs/reports/TEST_REPORT.md` 更新并同步到 issue tracker。
