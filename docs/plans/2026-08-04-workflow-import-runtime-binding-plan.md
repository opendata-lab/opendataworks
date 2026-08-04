# Workflow Import Runtime Binding Plan

> Design: [2026-08-04-workflow-import-runtime-binding-design.md](../design/2026-08-04-workflow-import-runtime-binding-design.md)

**Goal:** 导入弹窗表单化，目标 Dolphin 环境与运行态关联显式可选，导入不再无条件继承来源平台的 Dolphin 编码。
**Tech Stack:** Java 8 + Spring Boot 2.7 backend, MyBatis-Plus + MySQL, Vue 3 + Element Plus frontend

## Architecture Summary

导入链路仍然是 `WorkflowController` → `WorkflowDefinitionLifecycleService.analyze/commit`。本次在 `analyze` 中新增一段运行态归属解析，把结果通过 `WorkflowImportPreviewResponse.runtimeBinding` 暴露给前端；`commit` 按同一决策落库。运行态清理复用 `WorkflowDefinitionAssembler.refreshRuntimeBindings`，项目编码解析新增只读入口避免预检副作用。

## Task 1: 只读项目解析与单条运行态查询

**Files:**
- backend/src/main/java/com/onedata/portal/service/DolphinSchedulerService.java
- backend/src/main/java/com/onedata/portal/service/DolphinRuntimeDefinitionService.java

**Steps:**
1. 在 `DolphinSchedulerService` 新增 `findProjectCode(Long dolphinConfigId)`：解析配置后先读 `cachedProjectCodeByConfigId`，未命中调用 `openApiClient.getProject(projectName)`，命中则写缓存并返回，找不到或异常返回 `null`。不调用 `createProject`。
2. 在 `DolphinRuntimeDefinitionService` 新增 `findRuntimeWorkflow(Long dolphinConfigId, Long projectCode, Long workflowCode)`：通过 `withConfig` 调用 `openApiClient.getProcessDefinition`，映射为 `DolphinRuntimeWorkflowOption`，并按 `(projectCode, workflowCode)` 查 `data_workflow` 填充 `localWorkflowId` / `localWorkflowName` / `synced`；查不到返回 `null`。

**Expected Result:**
- 预检解析项目编码不会在目标 Dolphin 中创建项目
- 可按编码精确查询单条运行态工作流并知道它是否已被平台占用

## Task 2: 导入 DTO 扩展

**Files:**
- backend/src/main/java/com/onedata/portal/dto/workflow/WorkflowImportRuntimeBinding.java（新增）
- backend/src/main/java/com/onedata/portal/dto/workflow/WorkflowImportPreviewRequest.java
- backend/src/main/java/com/onedata/portal/dto/workflow/WorkflowImportCommitRequest.java
- backend/src/main/java/com/onedata/portal/dto/workflow/WorkflowImportPreviewResponse.java
- backend/src/main/java/com/onedata/portal/dto/workflow/WorkflowImportCommitResponse.java

**Steps:**
1. 新增 `WorkflowImportRuntimeBinding`，字段见 design 的 Interfaces 一节。
2. 两个 request 增加 `Long dolphinConfigId` 与 `Long linkedWorkflowCode`。
3. `WorkflowImportPreviewResponse` 增加 `runtimeBinding`，`WorkflowImportCommitResponse` 增加 `appliedRuntimeBinding`。

**Expected Result:**
- 预检响应能告诉前端本次将采用哪种归属以及原因

## Task 3: 导入服务的校验、判定与落库

**Files:**
- backend/src/main/java/com/onedata/portal/service/WorkflowDefinitionLifecycleService.java

**Steps:**
1. `ImportSource` 与两个 `buildImportSource` 重载补充 `dolphinConfigId`、`linkedWorkflowCode`。
2. `analyze` 中新增 `resolveRuntimeBinding`：
   - `dolphinConfigId` 为空 → 错误「请选择目标 Dolphin 环境」
   - `findProjectCode` 返回空 → 错误「无法解析目标 Dolphin 项目，请检查连接配置」
   - `linkedWorkflowCode` 为空 → `RESET`
   - 否则 `findRuntimeWorkflow`：查不到 → 错误「所选运行态工作流在目标 Dolphin 中不存在，请重新选择」；`localWorkflowId` 非空 → 错误「该 Dolphin 运行态已被平台工作流「{name}」(id={id}) 关联，请直接编辑该工作流，或改为不关联导入」；否则 `ADOPT`
3. `validateWorkflowNameConflict` 去掉 `sourceType=dolphin` 早退，改为对所有来源生效。
4. `commit` 中 `workflowRequest.setProjectCode` 改用 `runtimeBinding.getProjectCode()`。
5. `applyImportedWorkflowFields` 增加 `WorkflowImportRuntimeBinding` 参数，按 ADOPT / RESET 写入运行态字段，两种情况都写 `dolphinConfigId`。
6. RESET 时把 `normalizedJson` 交给 `workflowDefinitionAssembler.refreshRuntimeBindings(json, dolphinConfigId, projectCode)` 后再落库。
7. `ensureWorkflowConflictAbsent` 保留为事务内兜底，错误文案改为带冲突工作流名称与出路的版本。

**Expected Result:**
- 预检阶段就能看到归属结论与冲突，提交不再是唯一的失败点
- 不关联导入的工作流 `workflowCode` 为空、`publishStatus="never"`，发布走首次部署

## Task 4: 控制器接口

**Files:**
- backend/src/main/java/com/onedata/portal/controller/WorkflowController.java

**Steps:**
1. `GET /import/dolphin` 增加可选 `dolphinConfigId`，透传到 `WorkflowDefinitionLifecycleService.listDolphinWorkflows` 的新重载。
2. 新增 `GET /import/dolphin/{workflowCode}`，参数 `dolphinConfigId`，返回 `DolphinRuntimeWorkflowOption`。

**Expected Result:**
- 前端可按所选环境列出运行态工作流，并按编码精确探测一条

## Task 5: 后端测试

**Files:**
- backend/src/test/java/com/onedata/portal/service/WorkflowDefinitionLifecycleServiceTest.java

**Steps:**
1. 补充预检用例：缺少 Dolphin 配置、ADOPT、RESET、所选运行态不存在、所选运行态已被占用。
2. 补充提交用例：不关联时清空运行态字段与定义 JSON 中的编码；关联时保留编码并写 `dolphinConfigId`。
3. 扩展既有同名冲突用例覆盖 JSON 来源。

**Expected Result:**
- `mvn -pl backend test -Dtest=WorkflowDefinitionLifecycleServiceTest` 通过

## Task 6: 前端表单

**Files:**
- frontend/src/views/workflows/importFormHelper.js（新增）
- frontend/src/views/workflows/__tests__/importFormHelper.spec.js（新增）
- frontend/src/views/workflows/WorkflowImportDialog.vue
- frontend/src/api/workflow.js

**Steps:**
1. `importFormHelper.js` 提供纯函数：从 JSON 文本解析 `{workflowCode, workflowName}`、构建导入 payload、把 `runtimeBinding` 映射为提示文案与告警类型。
2. `api/workflow.js` 的 `listImportDolphinWorkflows` 透传 `dolphinConfigId`，新增单条探测方法。
3. `WorkflowImportDialog.vue` 改为带 `rules` 的 `el-form`：目标 Dolphin 环境（必填，带跳转 `/settings?tab=dolphin` 的新增入口）、新工作流名称（必填，两模式共用）、JSON 模式的关联运行态工作流下拉（可清空，自动预选）。提交前 `validate()`。
4. 预检面板内按 `runtimeBinding` 渲染一条 `el-alert`。

**Expected Result:**
- 必填项未填时按钮禁用或校验拦截，不发请求
- 关联关系解析出来后用户可用、可清空、可换

## Verification

- 后端：`mvn -pl backend test -Dtest=WorkflowDefinitionLifecycleServiceTest`，并回归 `WorkflowServiceMetadataPersistenceTest`（导出行为不变）
- 前端：先 `export NVM_DIR="$HOME/.nvm" && [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh" && nvm use`，再跑 `importFormHelper` 单测与 `WorkflowDetail.smoke.spec.js`
- 手工端到端（需两个 Dolphin 环境）：
  1. 同环境、文件编码在目标项目存在且未被占用 → 自动预选，导入后流程编码保持原值，发布走更新分支
  2. 同上但已被平台工作流占用 → 预检阶段阻断并指出冲突工作流
  3. 清空关联 → 导入后 `publishStatus=never`、流程编码为空，发布生成新定义，原 Dolphin 工作流未被下线
  4. 切换到另一个 Dolphin 环境 → 无匹配项，按新建导入
  5. 无可用 Dolphin 配置 → 提示前往设置页，预检禁用
- 若手工端到端未执行，需在验证说明中写明未覆盖的层级

## Rollout / Backout

- 无数据库变更，无需数据迁移
- 前后端需同版本发布：后端新增必填 `dolphinConfigId`，旧前端不传会在预检阶段收到明确错误而非静默错绑
- 回滚即回退代码，已导入工作流的运行态字段保持导入时写入的值，不需要额外清理
