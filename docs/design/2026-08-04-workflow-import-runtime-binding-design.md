# Workflow Import Runtime Binding Design

**Date:** 2026-08-04
**Goal:** 让工作流 JSON 导入不再无条件继承来源平台的 Dolphin 运行态编码，改由导入表单显式确定目标 Dolphin 环境与运行态关联关系。
**Tech Stack:** Java 8 + Spring Boot 2.7 backend, MyBatis-Plus + MySQL, Vue 3 + Element Plus frontend

## Scope

- 工作流 JSON 导入与 Dolphin 导入的预检、提交链路
- 导入弹窗表单结构与必填校验
- 导入后 `data_workflow` 运行态字段（`dolphin_config_id` / `project_code` / `workflow_code` / `dolphin_schedule_id`）的写入规则

不在本次范围：

- 工作流导出格式（保持现状）
- 工作流详情页的运行态改绑入口
- Dolphin 项目选择（Dolphin 配置侧已固定 `projectName`，不引入 per-workflow 项目）

## Current State

- `WorkflowDefinitionAssembler.sanitizeDefinitionJsonForExport` 只移除 `processDefinition.releaseState` 与 `status`。`code` / `workflowCode` / `projectCode`、`schedule.id`、`taskGroupId`、`xPlatformWorkflowMeta` 均随导出文件带出。
- `DolphinRuntimeDefinitionService.parseRuntimeDefinitionFromJson` 把这些编码原样解析回 `RuntimeWorkflowDefinition`。
- `WorkflowDefinitionLifecycleService.applyImportedWorkflowFields` 对 `sourceType=json` 无条件继承 `projectCode` / `workflowCode` / `dolphinScheduleId`，并置 `publishStatus="published"`。
- `WorkflowDefinitionLifecycleService.ensureWorkflowConflictAbsent` 在 `commit` 阶段按 `(project_code, workflow_code)` 查 `data_workflow`，命中即抛 `IllegalStateException("工作流已存在（…）")`。该检查不在 `preview` 阶段执行。
- `validateWorkflowNameConflict` 只对 `sourceType=dolphin` 生效，JSON 导入没有平台侧同名校验。
- 导入弹窗 `WorkflowImportDialog.vue` 中，「新工作流名称」输入只在 Dolphin 模式出现；两种模式都无法选择目标 Dolphin 环境。
- `data_workflow` 已有 `dolphin_config_id`、`project_code`、`workflow_code`、`dolphin_schedule_id` 字段，但没有任何接口允许用户直接设置它们。

## Problem

导出携带来源平台运行态身份，导入端无条件继承，导致三种失败模式：

- 目标平台已有工作流占用同一 `(projectCode, workflowCode)`：`commit` 抛 `工作流已存在`。预检显示"可导入"，错误只在点「确认导入」时出现，且没有任何出路。
- 目标平台指向另一个 Dolphin：导入成功但绑着来源集群的 `workflowCode`，`isFirstDeploy` 判为 `false`，发布走更新分支，DolphinScheduler 以同名定义拒绝，错误以 `API Error …` 透出。
- 来源与目标指向同一 Dolphin 集群同一项目且平台侧未占用：`WorkflowDeployService` 的 `checkWorkflowExists` 返回 `true`，发布时先把来源平台的生产工作流 `OFFLINE` 再覆盖，全程无提示。

共同根因是运行态归属在导入时没有被显式决定，而是由文件内容隐式决定。

## Design

把运行态归属变成导入表单上的显式输入，并且必填项在前端校验完成后才允许提交，不再依赖后端后置报错。

### 表单契约

两种导入模式共用：

- **目标 Dolphin 环境**：必填。选项取自 `GET /v1/settings/dolphin/configs`，未启用的配置禁选。默认选中 `isDefault` 的配置；只有一个启用配置时直接选中。选择框右侧常驻一个跳转 Dolphin 管理页的按钮；一条可用配置都没有时在该行以行内提示说明，不额外加横幅。
- **新工作流名称**：必填。默认取解析出的定义名称。
- **关联运行态工作流**：JSON 模式为可搜索、可清空的下拉；Dolphin 模式沿用现有表格选择。

JSON 模式下，前端从待导入 JSON 中解析 `processDefinition.code`，用单条探测接口在目标环境中查找并预选。清空该项即表示"不关联，按全新工作流导入"。

### 运行态归属规则

`linkedWorkflowCode` 是唯一决策输入，后端在事务内复核：

- **ADOPT**（选中了运行态工作流）：`projectCode` 取目标环境解析出的项目编码，`workflowCode` 取所选编码，`dolphinScheduleId` 沿用文件值，`publishStatus="published"`，`dolphinConfigId` 取所选环境。
- **RESET**（未选中）：`projectCode` 取目标环境项目编码，`workflowCode` / `dolphinScheduleId` / `scheduleState` 置空，`status="draft"`，`publishStatus="never"`，`dolphinConfigId` 取所选环境。调度 cron、时区、告警等属于定义内容，保留。

RESET 时定义 JSON 走 `WorkflowDefinitionAssembler.refreshRuntimeBindings`，复用既有的 `resetDefinitionRuntimeBinding` 与 `enrichMetadataFromCatalog`，清除运行态编码并按目标 Dolphin 目录重新解析 datasource 与 task group 编码。该原语此前只有 `WorkflowExecutionService.switchSchedulerEngine` 使用。

复核失败直接报错，不静默改判：

- 所选编码在目标项目中不存在
- 所选编码已被其它平台工作流关联（错误信息带出冲突工作流名称与 id，并给出"改为不关联导入"的出路）

### 项目编码解析

目标项目编码由所选 Dolphin 配置的 `projectName` 反查得到。预检阶段使用只读解析，项目不存在时返回失败，不自动创建项目 —— 现有 `DolphinSchedulerService.getProjectCode` 在项目缺失时会调用 `createProject`，预检不应产生该副作用。

### 名称冲突

`validateWorkflowNameConflict` 扩展到 JSON 导入。DolphinScheduler 本身会在发布时拒绝同名定义，把这个失败提前到预检更有价值，且表单已提供名称输入供当场修改。

## Interfaces / Data Model

无数据库变更，复用 `data_workflow` 既有字段。

### 请求/响应

`WorkflowImportPreviewRequest` 与 `WorkflowImportCommitRequest` 新增：

- `Long dolphinConfigId`：目标 Dolphin 环境，必填
- `Long linkedWorkflowCode`：关联的运行态工作流编码，空表示不关联

`WorkflowImportPreviewResponse` 新增 `WorkflowImportRuntimeBinding runtimeBinding`：

```
decision            ADOPT | RESET
dolphinConfigId     目标环境 id
projectCode         目标项目编码
workflowCode        关联的运行态编码（RESET 时为空）
runtimeWorkflowName 运行态工作流名称
releaseState        运行态发布状态
conflictWorkflowId  占用该运行态的平台工作流 id
conflictWorkflowName 占用该运行态的平台工作流名称
message             面向用户的说明文案
```

`WorkflowImportCommitResponse` 新增 `String appliedRuntimeBinding`。

### HTTP

- `GET /v1/workflows/import/dolphin` 新增可选 `dolphinConfigId`
- `GET /v1/workflows/import/dolphin/{workflowCode}` 新增，参数 `dolphinConfigId`，返回单条 `DolphinRuntimeWorkflowOption` 或空

### 服务层

- `DolphinSchedulerService.findProjectCode(Long dolphinConfigId)`：只读解析项目编码，不创建项目
- `DolphinRuntimeDefinitionService.findRuntimeWorkflow(Long dolphinConfigId, Long projectCode, Long workflowCode)`：单条运行态查询，带平台占用信息

## Risks / Alternatives

- JSON 导入新增同名校验会让此前可通过的重名导入变为预检失败。这是有意收紧，缓解措施是同一改动内为 JSON 模式提供名称输入。
- 打开弹窗即访问目标 Dolphin（解析项目、列出定义），Dolphin 不可达时下拉加载失败。`DolphinOpenApiClient` 已有 10s 超时，失败路径明确提示连接问题。
- 备选方案：导出时彻底剥离运行态编码。该方案最简单，但会永久失去"同集群导出再导入以保留运行态"的能力，且对已经导出的历史文件无效，因此不采纳。
- 备选方案：完全由后端自动判定归属，不给用户选择。自动判定在同集群多项目、同名不同 code 等场景下容易判错且无法纠偏，因此改为"自动预选 + 用户可覆盖"。
- 备选方案：新增 Dolphin 项目选择器。Dolphin 配置侧已固定 `projectName`，再引入 per-workflow 项目会与 `getProjectCode` 的既有解析路径冲突，复杂度不划算，因此不采纳。
- Dolphin 导入模式的绑定语义保持不变（`applyImportedWorkflowFields` 对该来源刻意不写 `workflowCode`），本轮只为其补充环境选择与表单校验。

## Verification

- 后端针对预检决策、复核失败、提交落库分支补充单测，其中 `ensureWorkflowConflictAbsent` 此前无任何用例覆盖
- 前端把 JSON 解析、payload 构建、运行态提示文案抽为纯函数并单测
- 环境可用时用两个 Dolphin 配置做手工端到端：关联导入、占用阻断、不关联导入、跨环境导入、无配置提示
