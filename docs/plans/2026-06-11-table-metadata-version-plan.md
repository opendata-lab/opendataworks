# 表资产元数据版本历史实施计划

- 日期：2026-06-11
- 主题：table-metadata-version
- 设计文档：`docs/design/2026-06-11-table-metadata-version-design.md`

## 任务清单

1. Schema 与实体
   - 新增 `backend/src/main/resources/db/migration/V45__create_data_table_version.sql`
   - 新增 `entity/DataTableVersion.java`、`mapper/DataTableVersionMapper.java`
2. 核心服务
   - 新增 `service/TableMetadataVersionService.java`：`captureVersion`（白名单快照 + canonical JSON + SHA-256 哈希比对，吞异常）、`listVersions`（排除快照大字段）、`getVersion`、`compare`
   - 新增 `dto/table/TableVersionCompareRequest.java`、`dto/table/TableVersionCompareResponse.java`
3. 单元测试
   - 新增 `backend/src/test/java/com/onedata/portal/service/TableMetadataVersionServiceTest.java`（Mockito + `TableInfoHelper.initTableInfo` 模式）
   - 用例：首捕获→v1；相同重捕获→不插入；volatile 字段变化→不插入；字段注释变化→v2；compare 结构化结果；已删表→no-op；capture 不抛异常
4. Hook 接入
   - `service/DataTableService.java`：create / update / restoreDeprecatedTable / createField / updateField / deleteField
   - `service/TableCreateService.java`：create 末尾
   - `service/InspectionService.java`：fixReplicaCountIssue
   - `service/DorisMetadataSyncService.java`：syncNewTable 末尾、syncExistingTable 末尾（守卫）
   - 删除路径不动（版本永不清理）
5. API
   - 新增 `controller/DataTableVersionController.java`
6. 前端
   - `frontend/src/api/table.js`：listVersions / getVersion / compareTableVersions
   - 新增 `frontend/src/views/datastudio/components/TableVersionHistoryPanel.vue`、`TableVersionCompareDialog.vue`
   - `DataStudioRightPanel.vue` 新增"版本"标签页（懒加载）

## 触达文件

- 新增：V45 迁移、DataTableVersion 实体/Mapper、TableMetadataVersionService、compare DTO×2、DataTableVersionController、服务单测、前端组件×2、本设计/计划文档
- 修改：DataTableService、TableCreateService、InspectionService、DorisMetadataSyncService、DataStudioRightPanel.vue、api/table.js

## 验证

- 后端：`cd backend && mvn -q compile`；`mvn -q test -Dtest=TableMetadataVersionServiceTest`；回归 `mvn -q test -Dtest='WorkflowVersion*Test'`
- 前端：`nvm use` 后 `npm --prefix frontend run build`
- 跨层 smoke（环境可用时）：dev MySQL(127.0.0.1:3316) + 后端（Flyway 应用 V45）+ 前端。改表注释→版本 tab 出现 v1(manual_edit)；保存相同值→无新版本；同表 sync-metadata 两次→最多一个新版本；统计-only 同步→无版本；对比弹窗正常。无本地 Doris 时同步路径仅由单测覆盖，验证说明中如实声明。

## 发布与回退

- 发布：迁移为纯新增表，零停机；功能被动生效，无需开关。
- 回退：移除各 hook 调用即可停止记录；`data_table_version` 表可保留，不影响业务。
