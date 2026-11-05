package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.DolphinPageData;
import com.onedata.portal.dto.dolphin.DolphinProject;
import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.dolphin.DolphinTaskGroup;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowExportJsonResponse;
import com.onedata.portal.dto.workflow.WorkflowImportCommitRequest;
import com.onedata.portal.dto.workflow.WorkflowImportCommitResponse;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewRequest;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncErrorCodes;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDiffResponse;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * 真实 Dolphin + 真实 MySQL 集成测试： - 自动修复 token 失效（登录 + 重新生成 token） - 自动创建缺失项目 - 自动创建缺失数据源 -
 * 覆盖正向发布、反向预检/同步、差异比对
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
@TestPropertySource(
        properties = {
            "workflow.runtime-sync.enabled=true",
            "workflow.runtime-sync.ingest-mode=export_only",
            "spring.task.scheduling.enabled=false"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("运行态反向同步真实集成测试")
class WorkflowRuntimeSyncRealIntegrationTest {

    private static final String IT_PREFIX = "it_rt_sync_";
    private static final String IT_PROJECT_NAME = IT_PREFIX + "project";
    private static final String IT_DATASOURCE_NAME = IT_PREFIX + "mysql";
    private static final String DEFAULT_DS_URL = "http://localhost:12345/dolphinscheduler";
    private static final String DEFAULT_DS_USERNAME = "admin";
    private static final String DEFAULT_DS_PASSWORD = "dolphinscheduler123";

    @Autowired private DolphinConfigService dolphinConfigService;

    @Autowired private DolphinSchedulerService dolphinSchedulerService;

    @Autowired private DolphinOpenApiClient dolphinOpenApiClient;

    @Autowired private WorkflowRuntimeSyncService workflowRuntimeSyncService;

    @Autowired private DolphinRuntimeDefinitionService dolphinRuntimeDefinitionService;

    @Autowired private WorkflowService workflowService;

    @Autowired private WorkflowPublishService workflowPublishService;

    @Autowired private WorkflowDefinitionLifecycleService workflowDefinitionLifecycleService;

    @Autowired private DataTaskService dataTaskService;

    @Autowired private DataWorkflowMapper dataWorkflowMapper;

    @Autowired private DataTaskMapper dataTaskMapper;

    @Autowired private DataTableMapper dataTableMapper;

    @Autowired private DataLineageMapper dataLineageMapper;

    @Autowired private TableTaskRelationMapper tableTaskRelationMapper;

    @Autowired private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Autowired private DorisClusterMapper dorisClusterMapper;

    @Autowired private WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;

    @Autowired private WorkflowVersionMapper workflowVersionMapper;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private DataSource dataSource;

    private String effectiveBaseUrl;
    private String effectiveToken;
    private Long effectiveProjectCode;
    private String effectiveDatasourceName;
    private Long effectiveClusterId;

    private final Set<Long> createdWorkflowIds = new LinkedHashSet<>();
    private final Set<Long> createdTaskIds = new LinkedHashSet<>();
    private final Set<Long> createdTableIds = new LinkedHashSet<>();
    private final Set<Long> createdRuntimeWorkflowCodes = new LinkedHashSet<>();
    private final List<String> createdPhysicalTables = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // 先清理本地工作流（会联动删除 Dolphin 工作流）
        for (Long workflowId : new ArrayList<>(createdWorkflowIds)) {
            try {
                DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
                if (workflow != null && workflow.getWorkflowCode() != null) {
                    createdRuntimeWorkflowCodes.remove(workflow.getWorkflowCode());
                }
                workflowService.deleteWorkflow(workflowId);
            } catch (Exception ignored) {
            }
        }
        createdWorkflowIds.clear();

        // 再兜底清理运行态工作流（比如未落本地的 shell 测试工作流）
        for (Long workflowCode : new ArrayList<>(createdRuntimeWorkflowCodes)) {
            try {
                dolphinSchedulerService.deleteWorkflow(workflowCode);
            } catch (Exception ignored) {
            }
        }
        createdRuntimeWorkflowCodes.clear();

        // 清理任务血缘与任务关系
        for (Long taskId : new ArrayList<>(createdTaskIds)) {
            try {
                dataLineageMapper.delete(
                        Wrappers.<DataLineage>lambdaQuery().eq(DataLineage::getTaskId, taskId));
                tableTaskRelationMapper.hardDeleteByTaskId(taskId);
                workflowTaskRelationMapper.delete(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getTaskId, taskId));
                dataTaskMapper.deleteById(taskId);
            } catch (Exception ignored) {
            }
        }
        createdTaskIds.clear();

        for (Long tableId : new ArrayList<>(createdTableIds)) {
            try {
                dataTableMapper.deleteById(tableId);
            } catch (Exception ignored) {
            }
        }
        createdTableIds.clear();

        for (int i = createdPhysicalTables.size() - 1; i >= 0; i--) {
            try {
                executeJdbc("DROP TABLE IF EXISTS " + createdPhysicalTables.get(i));
            } catch (Exception ignored) {
            }
        }
        createdPhysicalTables.clear();
    }

    @Test
    @DisplayName("导入预检 + 提交 + 导出：基于 Dolphin 导出 JSON 的生命周期回环")
    void importPreviewCommitAndExportShouldWorkWithRealDolphinExport() throws Exception {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";
        String srcTable = IT_PREFIX + "imp_src_" + suffix;
        String dwdTable = IT_PREFIX + "imp_dwd_" + suffix;
        String dwsTable = IT_PREFIX + "imp_dws_" + suffix;

        Long srcTableId = createMetadataTable(dbName, srcTable, "ODS");
        Long dwdTableId = createMetadataTable(dbName, dwdTable, "DWD");
        Long dwsTableId = createMetadataTable(dbName, dwsTable, "DWS");

        DataTask taskExtract =
                createSqlTask(
                        IT_PREFIX + "imp_task_extract_" + suffix,
                        IT_PREFIX + "imp_task_extract_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, dwdTable, dbName, srcTable),
                        Collections.singletonList(srcTableId),
                        Collections.singletonList(dwdTableId));
        DataTask taskLoad =
                createSqlTask(
                        IT_PREFIX + "imp_task_load_" + suffix,
                        IT_PREFIX + "imp_task_load_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, COUNT(1) AS total FROM %s.%s"
                                        + " GROUP BY user_id",
                                dbName, dwsTable, dbName, dwdTable),
                        Collections.singletonList(dwdTableId),
                        Collections.singletonList(dwsTableId));

        String sourceWorkflowName = IT_PREFIX + "wf_import_source_" + suffix;
        DataWorkflow sourceWorkflow =
                createWorkflow(
                        sourceWorkflowName,
                        Arrays.asList(taskExtract.getId(), taskLoad.getId()),
                        effectiveProjectCode);
        createdWorkflowIds.add(sourceWorkflow.getId());

        WorkflowPublishPreviewResponse sourcePreview =
                workflowPublishService.previewPublish(sourceWorkflow.getId());
        assertTrue(
                sourcePreview != null && Boolean.TRUE.equals(sourcePreview.getCanPublish()),
                "源工作流发布预检应通过");
        assertTrue(Boolean.TRUE.equals(sourcePreview.getRequireConfirm()), "首次发布应要求确认差异");

        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setRequireApproval(false);
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(sourceWorkflow.getId(), deployRequest);
        assertEquals("success", deployRecord.getStatus(), "源工作流发布失败");

        DataWorkflow publishedWorkflow = dataWorkflowMapper.selectById(sourceWorkflow.getId());
        assertNotNull(publishedWorkflow);
        Long runtimeWorkflowCode = publishedWorkflow.getWorkflowCode();
        assertTrue(
                runtimeWorkflowCode != null && runtimeWorkflowCode > 0,
                "发布后应写入 Dolphin workflowCode");
        createdRuntimeWorkflowCodes.add(runtimeWorkflowCode);

        JsonNode exported =
                dolphinOpenApiClient.exportDefinitionByCode(
                        dolphinSchedulerService.getConfig(null),
                        effectiveProjectCode,
                        runtimeWorkflowCode);
        assertNotNull(exported, "Dolphin 导出 JSON 不能为空");

        JsonNode processNode = exported.path("processDefinition");
        if (processNode == null || processNode.isMissingNode() || processNode.isNull()) {
            processNode = exported.path("workflowDefinition");
        }
        assertTrue(
                processNode != null && processNode.isObject(),
                "导出 JSON 缺少 processDefinition/workflowDefinition");
        ObjectNode processObject = (ObjectNode) processNode;
        processObject.put("name", IT_PREFIX + "wf_import_target_" + suffix);
        processObject.remove("code");
        processObject.remove("workflowCode");
        processObject.remove("projectCode");

        String importJson = objectMapper.writeValueAsString(exported);
        WorkflowImportPreviewRequest previewRequest = new WorkflowImportPreviewRequest();
        previewRequest.setDefinitionJson(importJson);
        previewRequest.setOperator("it-runtime-sync");
        previewRequest.setDolphinConfigId(dolphinConfigService.getActiveConfig().getId());
        WorkflowImportPreviewResponse preview =
                workflowDefinitionLifecycleService.preview(previewRequest);
        assertTrue(
                Boolean.TRUE.equals(preview.getCanImport()),
                "导入预检应通过, errors=" + preview.getErrors());

        WorkflowImportCommitRequest commitRequest = new WorkflowImportCommitRequest();
        commitRequest.setDefinitionJson(importJson);
        commitRequest.setOperator("it-runtime-sync");
        commitRequest.setDolphinConfigId(dolphinConfigService.getActiveConfig().getId());
        if (Boolean.TRUE.equals(preview.getRelationDecisionRequired())) {
            commitRequest.setRelationDecision(preview.getSuggestedRelationDecision());
        }
        WorkflowImportCommitResponse commitResponse =
                workflowDefinitionLifecycleService.commit(commitRequest);
        assertNotNull(commitResponse.getWorkflowId(), "导入后应生成新工作流");
        assertEquals(2, commitResponse.getCreatedTaskCount(), "应导入两条 SQL 任务");
        createdWorkflowIds.add(commitResponse.getWorkflowId());

        DataWorkflow importedWorkflow =
                dataWorkflowMapper.selectById(commitResponse.getWorkflowId());
        assertNotNull(importedWorkflow, "导入工作流应落库");
        assertTrue(
                StringUtils.hasText(importedWorkflow.getDefinitionJson()), "导入后应保存 definitionJson");

        WorkflowExportJsonResponse exportResponse =
                workflowDefinitionLifecycleService.exportJson(commitResponse.getWorkflowId());
        assertTrue(StringUtils.hasText(exportResponse.getContent()), "导出 JSON 内容不能为空");

        JsonNode exportedBack = objectMapper.readTree(exportResponse.getContent());
        assertTrue(
                exportedBack.path("taskDefinitionList").isArray(), "导出 JSON 缺少 taskDefinitionList");
        assertEquals(2, exportedBack.path("taskDefinitionList").size(), "导出任务数应与导入一致");
        assertTrue(
                exportedBack.path("processTaskRelationList").isArray(),
                "导出 JSON 缺少 processTaskRelationList");

        WorkflowImportPreviewRequest roundTripPreviewRequest = new WorkflowImportPreviewRequest();
        roundTripPreviewRequest.setDefinitionJson(exportResponse.getContent());
        roundTripPreviewRequest.setWorkflowName(IT_PREFIX + "wf_roundtrip_" + suffix);
        roundTripPreviewRequest.setOperator("it-runtime-sync");
        roundTripPreviewRequest.setDolphinConfigId(dolphinConfigService.getActiveConfig().getId());
        WorkflowImportPreviewResponse roundTripPreview =
                workflowDefinitionLifecycleService.preview(roundTripPreviewRequest);
        assertTrue(
                Boolean.TRUE.equals(roundTripPreview.getCanImport()),
                "导出结果再次预检应通过, errors=" + roundTripPreview.getErrors());
    }

    @Test
    @DisplayName("正向发布 + 反向同步 + 差异比对 + 6任务真实执行校验")
    void forwardReverseSyncAndDiffShouldWork() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        // 1) 构建真实数据表与种子数据（含 6 任务 DAG：并行 + 串行 + 多依赖）
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";
        String srcUserTable = IT_PREFIX + "src_user_" + suffix;
        String srcOrderTable = IT_PREFIX + "src_order_" + suffix;
        String srcPayTable = IT_PREFIX + "src_pay_" + suffix;
        String stgUserTable = IT_PREFIX + "stg_user_" + suffix;
        String stgOrderTable = IT_PREFIX + "stg_order_" + suffix;
        String stgPayTable = IT_PREFIX + "stg_pay_" + suffix;
        String dwdOrderUserTable = IT_PREFIX + "dwd_order_user_" + suffix;
        String dwdOrderPayTable = IT_PREFIX + "dwd_order_pay_" + suffix;
        String dwsUserAmountTable = IT_PREFIX + "dws_user_amount_" + suffix;

        preparePhysicalTablesAndSeedData(
                dbName,
                srcUserTable,
                srcOrderTable,
                srcPayTable,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable);

        Long srcUserTableId = createMetadataTable(dbName, srcUserTable, "ODS");
        Long srcOrderTableId = createMetadataTable(dbName, srcOrderTable, "ODS");
        Long srcPayTableId = createMetadataTable(dbName, srcPayTable, "ODS");
        Long stgUserTableId = createMetadataTable(dbName, stgUserTable, "DWD");
        Long stgOrderTableId = createMetadataTable(dbName, stgOrderTable, "DWD");
        Long stgPayTableId = createMetadataTable(dbName, stgPayTable, "DWD");
        Long dwdOrderUserTableId = createMetadataTable(dbName, dwdOrderUserTable, "DWD");
        Long dwdOrderPayTableId = createMetadataTable(dbName, dwdOrderPayTable, "DWD");
        Long dwsUserAmountTableId = createMetadataTable(dbName, dwsUserAmountTable, "DWS");

        DataTask task1 =
                createSqlTask(
                        IT_PREFIX + "task_1_user_stage_" + suffix,
                        IT_PREFIX + "task_1_user_stage_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, stgUserTable, dbName, srcUserTable),
                        Collections.singletonList(srcUserTableId),
                        Collections.singletonList(stgUserTableId));

        DataTask task2 =
                createSqlTask(
                        IT_PREFIX + "task_2_order_stage_" + suffix,
                        IT_PREFIX + "task_2_order_stage_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT order_id, user_id, order_amount FROM"
                                        + " %s.%s",
                                dbName, stgOrderTable, dbName, srcOrderTable),
                        Collections.singletonList(srcOrderTableId),
                        Collections.singletonList(stgOrderTableId));

        DataTask task3 =
                createSqlTask(
                        IT_PREFIX + "task_3_pay_stage_" + suffix,
                        IT_PREFIX + "task_3_pay_stage_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT pay_id, order_id, pay_amount FROM %s.%s",
                                dbName, stgPayTable, dbName, srcPayTable),
                        Collections.singletonList(srcPayTableId),
                        Collections.singletonList(stgPayTableId));

        DataTask task4 =
                createSqlTask(
                        IT_PREFIX + "task_4_join_user_order_" + suffix,
                        IT_PREFIX + "task_4_join_user_order_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT o.order_id, o.user_id, u.user_name,"
                                        + " o.order_amount FROM %s.%s o JOIN %s.%s u ON o.user_id ="
                                        + " u.user_id",
                                dbName,
                                dwdOrderUserTable,
                                dbName,
                                stgOrderTable,
                                dbName,
                                stgUserTable),
                        Arrays.asList(stgOrderTableId, stgUserTableId),
                        Collections.singletonList(dwdOrderUserTableId));

        DataTask task5 =
                createSqlTask(
                        IT_PREFIX + "task_5_join_order_pay_" + suffix,
                        IT_PREFIX + "task_5_join_order_pay_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT ou.order_id, ou.user_id, ou.user_name,"
                                    + " ou.order_amount, p.pay_id, p.pay_amount FROM %s.%s ou JOIN"
                                    + " %s.%s p ON ou.order_id = p.order_id",
                                dbName,
                                dwdOrderPayTable,
                                dbName,
                                dwdOrderUserTable,
                                dbName,
                                stgPayTable),
                        Arrays.asList(dwdOrderUserTableId, stgPayTableId),
                        Collections.singletonList(dwdOrderPayTableId));

        DataTask task6 =
                createSqlTask(
                        IT_PREFIX + "task_6_user_agg_" + suffix,
                        IT_PREFIX + "task_6_user_agg_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s "
                                        + "SELECT user_id, SUM(pay_amount) AS total_pay "
                                        + "FROM %s.%s GROUP BY user_id",
                                dbName, dwsUserAmountTable, dbName, dwdOrderPayTable),
                        Collections.singletonList(dwdOrderPayTableId),
                        Collections.singletonList(dwsUserAmountTableId));

        DataWorkflow workflow =
                createWorkflow(
                        IT_PREFIX + "wf_" + suffix,
                        Arrays.asList(
                                task1.getId(),
                                task2.getId(),
                                task3.getId(),
                                task4.getId(),
                                task5.getId(),
                                task6.getId()),
                        effectiveProjectCode);

        createdWorkflowIds.add(workflow.getId());

        // 2) 正向发布到 Dolphin（设计态 -> 运行态）
        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setRequireApproval(false);
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(workflow.getId(), deployRequest);

        assertEquals("success", deployRecord.getStatus());
        DataWorkflow deployedWorkflow = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(deployedWorkflow.getWorkflowCode(), "发布后必须产生 workflowCode");
        assertTrue(deployedWorkflow.getWorkflowCode() > 0, "workflowCode 必须大于0");

        Long runtimeWorkflowCode = deployedWorkflow.getWorkflowCode();
        createdRuntimeWorkflowCodes.add(runtimeWorkflowCode);

        JsonNode runtimeDef =
                dolphinOpenApiClient.getProcessDefinition(
                        dolphinSchedulerService.getConfig(null),
                        effectiveProjectCode,
                        runtimeWorkflowCode);
        assertNotNull(runtimeDef, "Dolphin 运行态工作流定义必须存在");
        assertExportDefinitionAvailable(runtimeWorkflowCode, "首次发布后");

        // 3) 反向预检（运行态 -> 设计态）
        RuntimeSyncPreviewRequest previewRequest = new RuntimeSyncPreviewRequest();
        previewRequest.setProjectCode(effectiveProjectCode);
        previewRequest.setWorkflowCode(runtimeWorkflowCode);
        previewRequest.setOperator("it-runtime-sync");
        RuntimeSyncPreviewResponse preview = workflowRuntimeSyncService.preview(previewRequest);

        assertTrue(
                Boolean.TRUE.equals(preview.getCanSync()),
                "SQL 工作流预检应通过, errors="
                        + safeJson(preview.getErrors())
                        + ", warnings="
                        + safeJson(preview.getWarnings()));
        assertTrue(
                preview.getErrors().isEmpty(), "预检不应有错误, errors=" + safeJson(preview.getErrors()));

        // 4) 执行反向同步
        RuntimeSyncExecuteRequest syncRequest = new RuntimeSyncExecuteRequest();
        syncRequest.setProjectCode(effectiveProjectCode);
        syncRequest.setWorkflowCode(runtimeWorkflowCode);
        syncRequest.setOperator("it-runtime-sync");
        if (Boolean.TRUE.equals(preview.getRelationDecisionRequired())) {
            syncRequest.setRelationDecision("INFERRED");
        }
        RuntimeSyncExecuteResponse syncResponse = workflowRuntimeSyncService.sync(syncRequest);

        assertTrue(
                Boolean.TRUE.equals(syncResponse.getSuccess()),
                "同步应成功, errors="
                        + safeJson(syncResponse.getErrors())
                        + ", warnings="
                        + safeJson(syncResponse.getWarnings()));
        assertTrue(
                syncResponse.getErrors().isEmpty(),
                "同步不应返回错误, errors=" + safeJson(syncResponse.getErrors()));
        assertNotNull(syncResponse.getWorkflowId());
        assertNotNull(syncResponse.getVersionNo());
        assertNotNull(syncResponse.getSyncRecordId());

        Long syncedWorkflowId = syncResponse.getWorkflowId();
        DataWorkflow syncedWorkflow = dataWorkflowMapper.selectById(syncedWorkflowId);
        assertNotNull(syncedWorkflow);
        assertEquals("runtime", syncedWorkflow.getSyncSource());
        assertEquals("success", syncedWorkflow.getRuntimeSyncStatus());
        assertNotNull(syncedWorkflow.getRuntimeSyncAt());
        assertTrue(StringUtils.hasText(syncedWorkflow.getRuntimeSyncHash()));

        WorkflowRuntimeSyncRecord syncRecord =
                assertSyncRecord(
                        syncResponse.getSyncRecordId(),
                        syncedWorkflowId,
                        runtimeWorkflowCode,
                        "it-runtime-sync");
        assertEquals("success", syncRecord.getStatus(), "同步历史记录状态应为 success");

        WorkflowVersion runtimeVersion =
                assertWorkflowVersion(syncedWorkflowId, syncResponse.getVersionNo());
        assertEquals("runtime_sync", runtimeVersion.getTriggerSource(), "同步产生的版本来源应为 runtime_sync");
        assertEquals(
                runtimeVersion.getId(), syncedWorkflow.getCurrentVersionId(), "工作流当前版本应指向同步版本");

        // 5) 初次 diff 应无变更
        RuntimeWorkflowDiffResponse diffBefore =
                workflowRuntimeSyncService.runtimeDiff(syncResponse.getWorkflowId());
        assertTrue(diffBefore.getErrors().isEmpty(), "首次 diff 不应报错");
        assertFalse(Boolean.TRUE.equals(diffBefore.getDiffSummary().getChanged()), "首次 diff 应无变更");

        // 6) 将同步后的工作流上线并执行，校验真实数据变化
        WorkflowPublishRequest onlineRequest = new WorkflowPublishRequest();
        onlineRequest.setOperation("online");
        onlineRequest.setOperator("it-runtime-sync");
        onlineRequest.setRequireApproval(false);
        WorkflowPublishRecord onlineRecord =
                workflowPublishService.publish(syncedWorkflowId, onlineRequest);
        assertEquals("success", onlineRecord.getStatus(), "同步后工作流上线应成功");

        assertWorkflowExecutionProducesExpectedResult(
                syncedWorkflowId,
                runtimeWorkflowCode,
                "it-runtime-sync",
                dbName,
                dwsUserAmountTable,
                2L,
                new BigDecimal("325.00"),
                4,
                90000L,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable);

        BigDecimal totalPay =
                queryForDecimal(
                        String.format(
                                "SELECT COALESCE(SUM(total_pay), 0) FROM %s.%s",
                                dbName, dwsUserAmountTable));
        assertNotNull(totalPay, "最终聚合金额不能为空");

        // 7) 修改运行态定义后再 diff，应识别变更
        String renamedRuntimeName = deployedWorkflow.getWorkflowName() + "_rt_changed";
        updateRuntimeWorkflowName(effectiveProjectCode, runtimeWorkflowCode, renamedRuntimeName);

        RuntimeWorkflowDiffResponse diffAfter =
                workflowRuntimeSyncService.runtimeDiff(syncResponse.getWorkflowId());
        assertTrue(diffAfter.getErrors().isEmpty(), "变更后 diff 不应报错");
        assertTrue(Boolean.TRUE.equals(diffAfter.getDiffSummary().getChanged()), "变更后 diff 应识别差异");
        assertTrue(
                diffAfter.getDiffSummary().getWorkflowFieldChanges().stream()
                        .anyMatch(item -> item.contains("workflow.workflowName")),
                "应识别 workflow 名称变更");

        // workflow 字段变更同步后应收敛为一致
        RuntimeSyncExecuteResponse resync = previewAndSync(runtimeWorkflowCode, "it-runtime-sync");
        assertNotNull(resync.getWorkflowId());
        RuntimeWorkflowDiffResponse diffAfterResync =
                workflowRuntimeSyncService.runtimeDiff(resync.getWorkflowId());
        assertTrue(diffAfterResync.getErrors().isEmpty(), "workflow 字段回同步后 diff 不应报错");
        assertFalse(
                Boolean.TRUE.equals(diffAfterResync.getDiffSummary().getChanged()),
                "workflow 字段回同步后应与运行态一致");
        DataWorkflow resyncedWorkflow = dataWorkflowMapper.selectById(resync.getWorkflowId());
        assertNotNull(resyncedWorkflow);
        assertEquals(
                renamedRuntimeName, resyncedWorkflow.getWorkflowName(), "workflow 名称应以运行态最新值为准");
    }

    @Test
    @DisplayName("运行态工作流增删改后二次同步应更新任务血缘并保证执行结果")
    void runtimeEvolutionShouldRefreshTasksLineageAndDataResult() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";

        String srcUserTable = IT_PREFIX + "src_user_" + suffix;
        String srcOrderTable = IT_PREFIX + "src_order_" + suffix;
        String srcPayTable = IT_PREFIX + "src_pay_" + suffix;
        String stgUserTable = IT_PREFIX + "stg_user_" + suffix;
        String stgOrderTable = IT_PREFIX + "stg_order_" + suffix;
        String stgPayTable = IT_PREFIX + "stg_pay_" + suffix;
        String dwdOrderUserTable = IT_PREFIX + "dwd_order_user_" + suffix;
        String dwdOrderPayTable = IT_PREFIX + "dwd_order_pay_" + suffix;
        String dwsUserAmountTable = IT_PREFIX + "dws_user_amount_" + suffix;

        String task1Name = IT_PREFIX + "task_1_user_stage_" + suffix;
        String task2Name = IT_PREFIX + "task_2_order_stage_" + suffix;
        String task3Name = IT_PREFIX + "task_3_pay_stage_" + suffix;
        String task4Name = IT_PREFIX + "task_4_join_user_order_" + suffix;
        String task5Name = IT_PREFIX + "task_5_join_order_pay_" + suffix;
        String task6Name = IT_PREFIX + "task_6_user_agg_" + suffix;
        String task7Name = IT_PREFIX + "task_7_user_agg_v2_" + suffix;

        preparePhysicalTablesAndSeedData(
                dbName,
                srcUserTable,
                srcOrderTable,
                srcPayTable,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable);

        Long srcUserTableId = createMetadataTable(dbName, srcUserTable, "ODS");
        Long srcOrderTableId = createMetadataTable(dbName, srcOrderTable, "ODS");
        Long srcPayTableId = createMetadataTable(dbName, srcPayTable, "ODS");
        Long stgUserTableId = createMetadataTable(dbName, stgUserTable, "DWD");
        Long stgOrderTableId = createMetadataTable(dbName, stgOrderTable, "DWD");
        Long stgPayTableId = createMetadataTable(dbName, stgPayTable, "DWD");
        Long dwdOrderUserTableId = createMetadataTable(dbName, dwdOrderUserTable, "DWD");
        Long dwdOrderPayTableId = createMetadataTable(dbName, dwdOrderPayTable, "DWD");
        Long dwsUserAmountTableId = createMetadataTable(dbName, dwsUserAmountTable, "DWS");

        DataTask task1 =
                createSqlTask(
                        task1Name,
                        task1Name,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, stgUserTable, dbName, srcUserTable),
                        Collections.singletonList(srcUserTableId),
                        Collections.singletonList(stgUserTableId));

        DataTask task2 =
                createSqlTask(
                        task2Name,
                        task2Name,
                        String.format(
                                "INSERT INTO %s.%s SELECT order_id, user_id, order_amount FROM"
                                        + " %s.%s",
                                dbName, stgOrderTable, dbName, srcOrderTable),
                        Collections.singletonList(srcOrderTableId),
                        Collections.singletonList(stgOrderTableId));

        DataTask task3 =
                createSqlTask(
                        task3Name,
                        task3Name,
                        String.format(
                                "INSERT INTO %s.%s SELECT pay_id, order_id, pay_amount FROM %s.%s",
                                dbName, stgPayTable, dbName, srcPayTable),
                        Collections.singletonList(srcPayTableId),
                        Collections.singletonList(stgPayTableId));

        DataTask task4 =
                createSqlTask(
                        task4Name,
                        task4Name,
                        String.format(
                                "INSERT INTO %s.%s SELECT o.order_id, o.user_id, u.user_name,"
                                        + " o.order_amount FROM %s.%s o JOIN %s.%s u ON o.user_id ="
                                        + " u.user_id",
                                dbName,
                                dwdOrderUserTable,
                                dbName,
                                stgOrderTable,
                                dbName,
                                stgUserTable),
                        Arrays.asList(stgOrderTableId, stgUserTableId),
                        Collections.singletonList(dwdOrderUserTableId));

        DataTask task5 =
                createSqlTask(
                        task5Name,
                        task5Name,
                        String.format(
                                "INSERT INTO %s.%s SELECT ou.order_id, ou.user_id, ou.user_name,"
                                    + " ou.order_amount, p.pay_id, p.pay_amount FROM %s.%s ou JOIN"
                                    + " %s.%s p ON ou.order_id = p.order_id",
                                dbName,
                                dwdOrderPayTable,
                                dbName,
                                dwdOrderUserTable,
                                dbName,
                                stgPayTable),
                        Arrays.asList(dwdOrderUserTableId, stgPayTableId),
                        Collections.singletonList(dwdOrderPayTableId));

        DataTask task6 =
                createSqlTask(
                        task6Name,
                        task6Name,
                        String.format(
                                "INSERT INTO %s.%s "
                                        + "SELECT user_id, SUM(pay_amount) AS total_pay "
                                        + "FROM %s.%s GROUP BY user_id",
                                dbName, dwsUserAmountTable, dbName, dwdOrderPayTable),
                        Collections.singletonList(dwdOrderPayTableId),
                        Collections.singletonList(dwsUserAmountTableId));

        DataWorkflow workflow =
                createWorkflow(
                        IT_PREFIX + "wf_evolution_" + suffix,
                        Arrays.asList(
                                task1.getId(),
                                task2.getId(),
                                task3.getId(),
                                task4.getId(),
                                task5.getId(),
                                task6.getId()),
                        effectiveProjectCode);
        createdWorkflowIds.add(workflow.getId());

        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setRequireApproval(false);
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(workflow.getId(), deployRequest);
        assertEquals("success", deployRecord.getStatus(), "首次发布应成功");

        DataWorkflow deployedWorkflow = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(deployedWorkflow);
        Long runtimeWorkflowCode = deployedWorkflow.getWorkflowCode();
        assertNotNull(runtimeWorkflowCode);
        createdRuntimeWorkflowCodes.add(runtimeWorkflowCode);
        assertExportDefinitionAvailable(runtimeWorkflowCode, "演进前");

        RuntimeSyncExecuteResponse firstSync =
                previewAndSync(runtimeWorkflowCode, "it-runtime-sync");
        Long syncedWorkflowId = firstSync.getWorkflowId();
        assertNotNull(syncedWorkflowId);
        WorkflowRuntimeSyncRecord firstRecord =
                assertSyncRecord(
                        firstSync.getSyncRecordId(),
                        syncedWorkflowId,
                        runtimeWorkflowCode,
                        "it-runtime-sync");
        WorkflowVersion firstRuntimeVersion =
                assertWorkflowVersion(syncedWorkflowId, firstSync.getVersionNo());
        assertEquals(
                "runtime_sync",
                firstRuntimeVersion.getTriggerSource(),
                "首次反向同步版本来源应为 runtime_sync");

        truncateTables(
                dbName,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable);
        executeAndAssertResult(
                syncedWorkflowId,
                runtimeWorkflowCode,
                dbName,
                dwsUserAmountTable,
                2L,
                new BigDecimal("325.00"),
                "it-runtime-sync");

        RuntimeWorkflowDefinition beforeEvolutionDefinition =
                dolphinRuntimeDefinitionService.loadRuntimeDefinitionFromExport(
                        effectiveProjectCode, runtimeWorkflowCode);
        Map<String, RuntimeTaskDefinition> beforeEvolutionTaskByName =
                beforeEvolutionDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .filter(task -> StringUtils.hasText(task.getTaskName()))
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskName,
                                        item -> item,
                                        (a, b) -> a));
        RuntimeTaskDefinition beforeTask2 =
                requireRuntimeTask(beforeEvolutionTaskByName, task2Name);
        Integer baselineTaskGroupId = beforeTask2.getTaskGroupId();
        Integer evolvedTaskGroupId = resolveEvolvedTaskGroupId(baselineTaskGroupId);
        boolean expectTaskGroupChanged = !Objects.equals(baselineTaskGroupId, evolvedTaskGroupId);

        String expectedWorkerGroup = dolphinSchedulerService.getDefaultWorkerGroup();
        String expectedTenantCode = dolphinSchedulerService.getDefaultTenantCode();
        String evolvedScheduleCron = "0 15 2 * * ? *";
        String evolvedScheduleTimezone = "Asia/Shanghai";
        LocalDateTime evolvedScheduleStart =
                LocalDateTime.now().plusMinutes(10L).withSecond(0).withNano(0);
        LocalDateTime evolvedScheduleEnd = evolvedScheduleStart.plusMonths(6L);

        evolveRuntimeWorkflowForEvolutionScenario(
                runtimeWorkflowCode,
                task1Name,
                task2Name,
                task3Name,
                task4Name,
                task5Name,
                task6Name,
                task7Name,
                dbName,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable,
                evolvedScheduleCron,
                evolvedScheduleTimezone,
                evolvedScheduleStart,
                evolvedScheduleEnd,
                evolvedTaskGroupId);
        assertExportDefinitionAvailable(runtimeWorkflowCode, "演进后");

        RuntimeSyncExecuteResponse secondSync =
                previewAndSync(runtimeWorkflowCode, "it-runtime-sync");
        assertEquals(syncedWorkflowId, secondSync.getWorkflowId(), "二次同步应更新同一工作流");
        assertTrue(secondSync.getVersionNo() > firstSync.getVersionNo(), "二次同步版本号应递增");
        WorkflowRuntimeSyncRecord secondRecord =
                assertSyncRecord(
                        secondSync.getSyncRecordId(),
                        syncedWorkflowId,
                        runtimeWorkflowCode,
                        "it-runtime-sync");
        assertTrue(secondRecord.getId() > firstRecord.getId(), "二次同步历史记录应晚于首次记录");
        assertNotEquals(
                firstRecord.getSnapshotHash(), secondRecord.getSnapshotHash(), "工作流变更后快照 hash 应变化");
        assertEquals("success", secondRecord.getStatus(), "二次同步历史记录状态应为 success");

        RuntimeDiffSummary secondRecordDiff = parseRuntimeDiffSummary(secondRecord.getDiffJson());
        assertNotNull(secondRecordDiff, "二次同步 diff 摘要不能为空");
        assertTrue(
                Boolean.TRUE.equals(secondRecordDiff.getChanged()), "工作流演进后二次同步 diff 应标记为 changed");
        assertTrue(
                secondRecordDiff.getTaskAdded().stream().anyMatch(item -> item.contains(task7Name)),
                "同步记录应包含新增任务");
        assertTrue(
                secondRecordDiff.getTaskRemoved().stream()
                        .anyMatch(item -> item.contains(task6Name)),
                "同步记录应包含删除任务");
        assertTrue(
                secondRecordDiff.getTaskModified().stream()
                        .anyMatch(item -> item.contains(task4Name)),
                "同步记录应包含任务4 SQL/依赖变更");
        assertTrue(
                secondRecordDiff.getTaskModified().stream()
                        .anyMatch(item -> item.contains(task5Name)),
                "同步记录应包含任务5 SQL/依赖变更");
        if (expectTaskGroupChanged) {
            assertTrue(
                    secondRecordDiff.getTaskModified().stream()
                            .anyMatch(item -> item.contains(task2Name)),
                    "同步记录应包含任务2任务组配置变更");
        }
        long scheduleCoreChangeCount =
                secondRecordDiff.getScheduleChanges().stream()
                        .filter(
                                item ->
                                        item.contains("schedule.crontab")
                                                || item.contains("schedule.timezoneId")
                                                || item.contains("schedule.startTime")
                                                || item.contains("schedule.endTime")
                                                || item.contains("schedule.workerGroup")
                                                || item.contains("schedule.tenantCode")
                                                || item.contains("schedule.scheduleId")
                                                || item.contains("schedule.releaseState"))
                        .count();
        assertTrue(scheduleCoreChangeCount >= 4, "同步记录应包含调度关键字段变化");
        assertFalse(secondRecordDiff.getEdgeAdded().isEmpty(), "同步记录应包含新增边");
        assertFalse(secondRecordDiff.getEdgeRemoved().isEmpty(), "同步记录应包含删除边");

        JsonNode firstSnapshotNode = parseJsonOrNull(firstRecord.getSnapshotJson());
        JsonNode secondSnapshotNode = parseJsonOrNull(secondRecord.getSnapshotJson());
        assertNotNull(firstSnapshotNode, "首次同步快照应可解析");
        assertNotNull(secondSnapshotNode, "二次同步快照应可解析");
        assertEquals(
                evolvedScheduleCron,
                readText(secondSnapshotNode.path("schedule"), "crontab"),
                "运行态快照应记录最新调度 cron");
        assertEquals(
                evolvedScheduleTimezone,
                readText(secondSnapshotNode.path("schedule"), "timezoneId"),
                "运行态快照应记录最新调度时区");
        assertEquals(
                expectedWorkerGroup,
                readText(secondSnapshotNode.path("schedule"), "workerGroup"),
                "运行态快照应记录 workerGroup");
        assertEquals(
                expectedTenantCode,
                readText(secondSnapshotNode.path("schedule"), "tenantCode"),
                "运行态快照应记录 tenantCode");
        assertTrue(
                StringUtils.hasText(readText(secondSnapshotNode.path("schedule"), "startTime")),
                "运行态快照应记录调度开始时间");
        assertTrue(
                StringUtils.hasText(readText(secondSnapshotNode.path("schedule"), "endTime")),
                "运行态快照应记录调度结束时间");

        JsonNode firstTask2Snapshot = findRuntimeTaskSnapshot(firstSnapshotNode, task2Name);
        JsonNode secondTask2Snapshot = findRuntimeTaskSnapshot(secondSnapshotNode, task2Name);
        assertNotNull(secondTask2Snapshot, "二次同步快照应包含任务2");
        Integer firstTask2GroupId = readInteger(firstTask2Snapshot, "taskGroupId");
        Integer secondTask2GroupId = readInteger(secondTask2Snapshot, "taskGroupId");
        if (expectTaskGroupChanged) {
            assertEquals(evolvedTaskGroupId, secondTask2GroupId, "任务组配置变化应写入同步快照");
            assertNotEquals(firstTask2GroupId, secondTask2GroupId, "任务组配置应发生变化");
        } else {
            assertEquals(firstTask2GroupId, secondTask2GroupId, "无可用任务组时应保持原任务组配置");
        }

        List<WorkflowTaskRelation> relationRows =
                workflowTaskRelationMapper.selectList(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getWorkflowId, syncedWorkflowId));
        assertEquals(6, relationRows.size(), "演进后工作流任务绑定数量应保持6（新增1、删除1）");

        Set<Long> taskIds =
                relationRows.stream()
                        .map(WorkflowTaskRelation::getTaskId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        List<DataTask> workflowTasks = dataTaskMapper.selectBatchIds(taskIds);
        Map<String, DataTask> taskByName =
                workflowTasks.stream()
                        .filter(Objects::nonNull)
                        .collect(
                                Collectors.toMap(DataTask::getTaskName, item -> item, (a, b) -> a));

        assertFalse(taskByName.containsKey(task6Name), "删除的任务不应继续绑定到工作流");
        assertTrue(taskByName.containsKey(task7Name), "新增任务应成功同步并绑定");

        DataTask syncedTask1 = taskByName.get(task1Name);
        DataTask syncedTask4 = taskByName.get(task4Name);
        DataTask syncedTask5 = taskByName.get(task5Name);
        DataTask syncedTask7 = taskByName.get(task7Name);
        assertNotNull(syncedTask1);
        assertNotNull(syncedTask4);
        assertNotNull(syncedTask5);
        assertNotNull(syncedTask7);

        DataTask removedTask6 = dataTaskMapper.selectById(task6.getId());
        assertNotNull(removedTask6, "删除绑定的任务6实体仍应可追溯");
        assertNotNull(syncedTask1.getDolphinTaskCode(), "任务1应有 Dolphin taskCode");
        assertNotNull(syncedTask4.getDolphinTaskCode(), "任务4应有 Dolphin taskCode");
        assertNotNull(syncedTask5.getDolphinTaskCode(), "任务5应有 Dolphin taskCode");
        assertNotNull(syncedTask7.getDolphinTaskCode(), "任务7应有 Dolphin taskCode");
        assertNotNull(removedTask6.getDolphinTaskCode(), "任务6应保留历史 Dolphin taskCode");
        String edgeTask1ToTask4 =
                syncedTask1.getDolphinTaskCode() + "->" + syncedTask4.getDolphinTaskCode();
        String edgeTask1ToTask5 =
                syncedTask1.getDolphinTaskCode() + "->" + syncedTask5.getDolphinTaskCode();
        String edgeTask5ToTask6 =
                syncedTask5.getDolphinTaskCode() + "->" + removedTask6.getDolphinTaskCode();
        String edgeTask5ToTask7 =
                syncedTask5.getDolphinTaskCode() + "->" + syncedTask7.getDolphinTaskCode();
        assertTrue(
                containsRawEdge(secondRecordDiff.getEdgeRemoved(), edgeTask1ToTask4),
                "同步记录应包含任务4减少依赖导致的边删除，edgeRemoved=" + secondRecordDiff.getEdgeRemoved());
        assertTrue(
                containsRawEdge(secondRecordDiff.getEdgeAdded(), edgeTask1ToTask5),
                "同步记录应包含任务5新增依赖导致的边新增，edgeAdded=" + secondRecordDiff.getEdgeAdded());
        assertTrue(
                containsRawEdge(secondRecordDiff.getEdgeRemoved(), edgeTask5ToTask6),
                "同步记录应包含任务6删除导致的边删除，edgeRemoved=" + secondRecordDiff.getEdgeRemoved());
        assertTrue(
                containsRawEdge(secondRecordDiff.getEdgeAdded(), edgeTask5ToTask7),
                "同步记录应包含任务7新增导致的边新增，edgeAdded=" + secondRecordDiff.getEdgeAdded());

        String task4Sql =
                syncedTask4.getTaskSql() != null ? syncedTask4.getTaskSql().toLowerCase() : "";
        String task5Sql =
                syncedTask5.getTaskSql() != null ? syncedTask5.getTaskSql().toLowerCase() : "";
        assertTrue(task4Sql.contains("'unknown'"), "任务4 SQL 应已更新为减少依赖表版本");
        assertFalse(
                task4Sql.contains(
                        "join " + dbName.toLowerCase() + "." + stgUserTable.toLowerCase()),
                "任务4 不应再依赖 stg_user");
        assertTrue(task5Sql.contains("pay_amount > 90"), "任务5 SQL 应已更新为新过滤逻辑");
        assertTrue(
                task5Sql.contains(
                        "join " + dbName.toLowerCase() + "." + stgUserTable.toLowerCase()),
                "任务5 应新增 stg_user 依赖");

        Set<Long> task4ReadTables = readTableIds(syncedTask4.getId());
        Set<Long> task5ReadTables = readTableIds(syncedTask5.getId());
        Set<Long> task7WriteTables = writeTableIds(syncedTask7.getId());
        assertEquals(
                Collections.singleton(stgOrderTableId), task4ReadTables, "任务4 输入依赖应减少为 stg_order");
        assertEquals(
                new LinkedHashSet<>(
                        Arrays.asList(dwdOrderUserTableId, stgPayTableId, stgUserTableId)),
                task5ReadTables,
                "任务5 输入依赖应增加 stg_user");
        assertEquals(
                Collections.singleton(dwsUserAmountTableId),
                task7WriteTables,
                "新增任务7输出表应为 dws_user_amount");
        assertEquals(task4ReadTables, lineageInputTableIds(syncedTask4.getId()), "任务4 血缘输入应与读依赖一致");
        assertEquals(task5ReadTables, lineageInputTableIds(syncedTask5.getId()), "任务5 血缘输入应与读依赖一致");

        Map<Long, WorkflowTaskRelation> relationByTaskId =
                relationRows.stream()
                        .collect(
                                Collectors.toMap(
                                        WorkflowTaskRelation::getTaskId,
                                        item -> item,
                                        (a, b) -> a));
        assertEquals(
                Integer.valueOf(1),
                relationByTaskId.get(syncedTask4.getId()).getUpstreamTaskCount(),
                "任务4 上游任务数应减少为1");
        assertEquals(
                Integer.valueOf(3),
                relationByTaskId.get(syncedTask5.getId()).getUpstreamTaskCount(),
                "任务5 上游任务数应增加为3");

        RuntimeWorkflowDiffResponse diffAfterResync =
                workflowRuntimeSyncService.runtimeDiff(syncedWorkflowId);
        assertTrue(diffAfterResync.getErrors().isEmpty(), "二次同步后 diff 不应报错");
        assertFalse(
                Boolean.TRUE.equals(diffAfterResync.getDiffSummary().getChanged()),
                "二次同步完成后应与运行态一致");

        List<WorkflowRuntimeSyncRecord> syncRecords =
                workflowRuntimeSyncRecordMapper.selectList(
                        Wrappers.<WorkflowRuntimeSyncRecord>lambdaQuery()
                                .eq(WorkflowRuntimeSyncRecord::getWorkflowId, syncedWorkflowId)
                                .orderByAsc(WorkflowRuntimeSyncRecord::getId));
        List<WorkflowRuntimeSyncRecord> successRecords =
                syncRecords.stream()
                        .filter(item -> "success".equals(item.getStatus()))
                        .collect(Collectors.toList());
        assertEquals(2, successRecords.size(), "该场景应产生两次 success 同步记录");
        WorkflowRuntimeSyncRecord latestSuccess = successRecords.get(successRecords.size() - 1);
        assertEquals(secondSync.getSyncRecordId(), latestSuccess.getId(), "最新同步历史应对应二次同步");
        assertEquals(firstRuntimeVersion.getId(), firstRecord.getVersionId(), "首次同步记录应关联首次同步版本");

        List<WorkflowVersion> versions =
                workflowVersionMapper.selectList(
                        Wrappers.<WorkflowVersion>lambdaQuery()
                                .eq(WorkflowVersion::getWorkflowId, syncedWorkflowId)
                                .orderByAsc(WorkflowVersion::getVersionNo));
        List<WorkflowVersion> runtimeSyncVersions =
                versions.stream()
                        .filter(item -> "runtime_sync".equals(item.getTriggerSource()))
                        .collect(Collectors.toList());
        assertEquals(2, runtimeSyncVersions.size(), "版本历史中应包含两次 runtime_sync 版本");
        WorkflowVersion secondRuntimeVersion =
                assertWorkflowVersion(syncedWorkflowId, secondSync.getVersionNo());
        assertEquals(
                "runtime_sync", secondRuntimeVersion.getTriggerSource(), "二次同步版本来源应为 runtime_sync");
        assertTrue(StringUtils.hasText(secondRuntimeVersion.getStructureSnapshot()), "同步版本应保存结构快照");
        assertEquals(secondRuntimeVersion.getId(), secondRecord.getVersionId(), "同步记录应关联二次同步版本ID");
        Set<Long> versionTaskIds =
                extractTaskIdsFromStructureSnapshot(secondRuntimeVersion.getStructureSnapshot());
        assertTrue(versionTaskIds.contains(syncedTask7.getId()), "最新版本快照应包含新增任务");
        assertFalse(versionTaskIds.contains(task6.getId()), "最新版本快照不应包含已删除任务");

        JsonNode firstVersionSnapshot = parseJsonOrNull(firstRuntimeVersion.getStructureSnapshot());
        JsonNode secondVersionSnapshot =
                parseJsonOrNull(secondRuntimeVersion.getStructureSnapshot());
        assertNotNull(firstVersionSnapshot, "首次版本快照应可解析");
        assertNotNull(secondVersionSnapshot, "二次版本快照应可解析");
        String secondVersionCron = readText(secondVersionSnapshot.path("schedule"), "scheduleCron");
        if (StringUtils.hasText(secondVersionCron)) {
            assertEquals(evolvedScheduleCron, secondVersionCron, "版本快照若包含调度字段，应记录最新调度 cron");
        }

        DataWorkflow latestWorkflow = dataWorkflowMapper.selectById(syncedWorkflowId);
        assertNotNull(latestWorkflow);
        assertEquals(
                secondRuntimeVersion.getId(),
                latestWorkflow.getCurrentVersionId(),
                "二次同步后 currentVersionId 应指向最新同步版本");
        assertEquals(
                secondRecord.getSnapshotHash(),
                latestWorkflow.getRuntimeSyncHash(),
                "工作流 runtimeSyncHash 应与最新同步记录一致");
        assertNotNull(latestWorkflow.getDolphinScheduleId(), "二次同步后应写入 scheduleId");
        assertEquals(evolvedScheduleCron, latestWorkflow.getScheduleCron(), "二次同步后应写入最新调度 cron");
        assertEquals(
                evolvedScheduleTimezone, latestWorkflow.getScheduleTimezone(), "二次同步后应写入最新调度时区");
        assertEquals(
                expectedWorkerGroup,
                latestWorkflow.getScheduleWorkerGroup(),
                "二次同步后应写入 workerGroup");
        assertEquals(
                expectedTenantCode, latestWorkflow.getScheduleTenantCode(), "二次同步后应写入 tenantCode");
        assertNotNull(latestWorkflow.getScheduleStartTime(), "二次同步后应写入调度开始时间");
        assertNotNull(latestWorkflow.getScheduleEndTime(), "二次同步后应写入调度结束时间");

        truncateTables(
                dbName,
                stgUserTable,
                stgOrderTable,
                stgPayTable,
                dwdOrderUserTable,
                dwdOrderPayTable,
                dwsUserAmountTable);
        executeAndAssertResult(
                syncedWorkflowId,
                runtimeWorkflowCode,
                dbName,
                dwsUserAmountTable,
                2L,
                new BigDecimal("275.00"),
                "it-runtime-sync");
    }

    @Test
    @DisplayName("首节点 UPDATE 无输入仅输出时，严格模式优先校验 Dolphin 显式边")
    void reversePreviewShouldPassForUpdateHeadWithoutInput() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";
        String odsUpdateTable = IT_PREFIX + "ods_update_head_" + suffix;
        String dwsResultTable = IT_PREFIX + "dws_update_result_" + suffix;

        String odsFullName = dbName + "." + odsUpdateTable;
        String dwsFullName = dbName + "." + dwsResultTable;
        executeJdbc("DROP TABLE IF EXISTS " + odsFullName);
        executeJdbc("DROP TABLE IF EXISTS " + dwsFullName);
        if (!createdPhysicalTables.contains(odsFullName)) {
            createdPhysicalTables.add(odsFullName);
        }
        if (!createdPhysicalTables.contains(dwsFullName)) {
            createdPhysicalTables.add(dwsFullName);
        }

        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, tag INT)", dbName, odsUpdateTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, tag INT)", dbName, dwsResultTable));
        executeJdbc(
                String.format(
                        "INSERT INTO %s.%s (user_id, tag) VALUES (1,0),(2,0)",
                        dbName, odsUpdateTable));

        Long odsUpdateTableId = createMetadataTable(dbName, odsUpdateTable, "ODS");
        Long dwsResultTableId = createMetadataTable(dbName, dwsResultTable, "DWS");

        String taskUpdateName = IT_PREFIX + "task_update_head_" + suffix;
        String taskInsertName = IT_PREFIX + "task_insert_after_update_" + suffix;

        DataTask updateTask =
                createSqlTask(
                        taskUpdateName,
                        taskUpdateName,
                        String.format(
                                "UPDATE %s.%s SET tag = 1 WHERE user_id > 0",
                                dbName, odsUpdateTable),
                        Collections.emptyList(),
                        Collections.singletonList(odsUpdateTableId));

        DataTask insertTask =
                createSqlTask(
                        taskInsertName,
                        taskInsertName,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, tag FROM %s.%s",
                                dbName, dwsResultTable, dbName, odsUpdateTable),
                        Collections.singletonList(odsUpdateTableId),
                        Collections.singletonList(dwsResultTableId));

        assertTrue(readTableIds(updateTask.getId()).isEmpty(), "首节点 UPDATE 的平台读依赖应为空");
        assertEquals(
                Collections.singleton(odsUpdateTableId),
                writeTableIds(updateTask.getId()),
                "首节点 UPDATE 的平台写依赖应为目标表");
        assertEquals(
                Collections.singleton(odsUpdateTableId),
                readTableIds(insertTask.getId()),
                "下游节点平台读依赖应为首节点输出表");

        DataWorkflow workflow =
                createWorkflow(
                        IT_PREFIX + "wf_update_head_" + suffix,
                        Arrays.asList(updateTask.getId(), insertTask.getId()),
                        effectiveProjectCode);
        createdWorkflowIds.add(workflow.getId());

        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setRequireApproval(false);
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(workflow.getId(), deployRequest);
        assertEquals("success", deployRecord.getStatus(), "发布应成功");

        DataWorkflow deployedWorkflow = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(deployedWorkflow);
        Long runtimeWorkflowCode = deployedWorkflow.getWorkflowCode();
        assertNotNull(runtimeWorkflowCode, "发布后应生成 runtime workflowCode");
        createdRuntimeWorkflowCodes.add(runtimeWorkflowCode);

        RuntimeWorkflowDefinition runtimeDefinition =
                dolphinRuntimeDefinitionService.loadRuntimeDefinitionFromExport(
                        effectiveProjectCode, runtimeWorkflowCode);
        assertNotNull(runtimeDefinition, "运行态定义必须可读取");
        Map<String, RuntimeTaskDefinition> runtimeTasksByName =
                runtimeDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .filter(task -> StringUtils.hasText(task.getTaskName()))
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskName,
                                        item -> item,
                                        (a, b) -> a));
        RuntimeTaskDefinition runtimeUpdateTask = runtimeTasksByName.get(taskUpdateName);
        RuntimeTaskDefinition runtimeInsertTask = runtimeTasksByName.get(taskInsertName);
        assertNotNull(runtimeUpdateTask, "运行态应包含 UPDATE 首节点");
        assertNotNull(runtimeInsertTask, "运行态应包含下游节点");

        RuntimeSyncPreviewRequest previewRequest = new RuntimeSyncPreviewRequest();
        previewRequest.setProjectCode(effectiveProjectCode);
        previewRequest.setWorkflowCode(runtimeWorkflowCode);
        previewRequest.setOperator("it-runtime-sync");
        RuntimeSyncPreviewResponse preview = workflowRuntimeSyncService.preview(previewRequest);

        assertTrue(
                Boolean.TRUE.equals(preview.getCanSync()),
                "UPDATE 首节点场景预检应通过, errors="
                        + safeJson(preview.getErrors())
                        + ", warnings="
                        + safeJson(preview.getWarnings()));
        assertTrue(
                preview.getErrors().isEmpty(), "预检不应返回错误, errors=" + safeJson(preview.getErrors()));
        assertTrue(
                preview.getWarnings().stream()
                        .noneMatch(
                                issue ->
                                        RuntimeSyncErrorCodes.RELATION_MISMATCH.equals(
                                                issue.getCode())),
                "平台存储血缘边与 Dolphin SQL 推断边应一致, warnings=" + safeJson(preview.getWarnings()));
        assertFalse(Boolean.TRUE.equals(preview.getRelationDecisionRequired()), "边一致时不应要求关系选轨");
    }

    @Test
    @DisplayName("运行态包含非SQL节点时，反向预检应严格失败")
    void reversePreviewShouldFailForUnsupportedNodeType() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Long shellWorkflowCode =
                createShellWorkflow(effectiveProjectCode, IT_PREFIX + "shell_" + suffix);
        createdRuntimeWorkflowCodes.add(shellWorkflowCode);

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(effectiveProjectCode);
        request.setWorkflowCode(shellWorkflowCode);
        request.setOperator("it-runtime-sync");

        RuntimeSyncPreviewResponse preview = workflowRuntimeSyncService.preview(request);
        assertFalse(Boolean.TRUE.equals(preview.getCanSync()));
        assertTrue(
                preview.getErrors().stream()
                        .anyMatch(issue -> "UNSUPPORTED_NODE_TYPE".equals(issue.getCode())),
                "应返回 UNSUPPORTED_NODE_TYPE, actualErrors=" + safeJson(preview.getErrors()));
    }

    @Test
    @DisplayName("声明关系与SQL推断冲突时需人工选轨，选轨后同步应收敛")
    void relationMismatchShouldRequireDecisionAndConvergeAfterManualSelection() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";
        String srcTable = IT_PREFIX + "src_relation_" + suffix;
        String dwdTable = IT_PREFIX + "dwd_relation_" + suffix;
        String dwsTable = IT_PREFIX + "dws_relation_" + suffix;

        String srcFullName = dbName + "." + srcTable;
        String dwdFullName = dbName + "." + dwdTable;
        String dwsFullName = dbName + "." + dwsTable;
        executeJdbc("DROP TABLE IF EXISTS " + srcFullName);
        executeJdbc("DROP TABLE IF EXISTS " + dwdFullName);
        executeJdbc("DROP TABLE IF EXISTS " + dwsFullName);
        if (!createdPhysicalTables.contains(srcFullName)) {
            createdPhysicalTables.add(srcFullName);
        }
        if (!createdPhysicalTables.contains(dwdFullName)) {
            createdPhysicalTables.add(dwdFullName);
        }
        if (!createdPhysicalTables.contains(dwsFullName)) {
            createdPhysicalTables.add(dwsFullName);
        }

        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, user_name VARCHAR(128))",
                        dbName, srcTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, user_name VARCHAR(128))",
                        dbName, dwdTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, user_name VARCHAR(128))",
                        dbName, dwsTable));
        executeJdbc(
                String.format(
                        "INSERT INTO %s.%s (user_id, user_name) VALUES (1,'u1'),(2,'u2')",
                        dbName, srcTable));

        Long srcTableId = createMetadataTable(dbName, srcTable, "ODS");
        Long dwdTableId = createMetadataTable(dbName, dwdTable, "DWD");
        Long dwsTableId = createMetadataTable(dbName, dwsTable, "DWS");

        String taskExtractName = IT_PREFIX + "task_extract_" + suffix;
        String taskLoadName = IT_PREFIX + "task_load_" + suffix;

        DataTask taskExtract =
                createSqlTask(
                        taskExtractName,
                        taskExtractName,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, dwdTable, dbName, srcTable),
                        Collections.singletonList(srcTableId),
                        Collections.singletonList(dwdTableId));

        DataTask taskLoad =
                createSqlTask(
                        taskLoadName,
                        taskLoadName,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, dwsTable, dbName, dwdTable),
                        Collections.singletonList(dwdTableId),
                        Collections.singletonList(dwsTableId));

        DataWorkflow workflow =
                createWorkflow(
                        IT_PREFIX + "wf_relation_mismatch_" + suffix,
                        Arrays.asList(taskExtract.getId(), taskLoad.getId()),
                        effectiveProjectCode);
        createdWorkflowIds.add(workflow.getId());

        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setRequireApproval(false);
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(workflow.getId(), deployRequest);
        assertEquals("success", deployRecord.getStatus(), "发布应成功");

        DataWorkflow deployedWorkflow = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(deployedWorkflow);
        Long runtimeWorkflowCode = deployedWorkflow.getWorkflowCode();
        assertNotNull(runtimeWorkflowCode, "发布后应生成 runtime workflowCode");
        createdRuntimeWorkflowCodes.add(runtimeWorkflowCode);
        assertExportDefinitionAvailable(runtimeWorkflowCode, "关系冲突演进前");

        RuntimeWorkflowDefinition runtimeDefinition =
                dolphinRuntimeDefinitionService.loadRuntimeDefinitionFromExport(
                        effectiveProjectCode, runtimeWorkflowCode);
        assertNotNull(runtimeDefinition, "运行态定义不能为空");
        Map<String, RuntimeTaskDefinition> runtimeTaskByName =
                runtimeDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .filter(task -> StringUtils.hasText(task.getTaskName()))
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskName,
                                        item -> item,
                                        (a, b) -> a));
        RuntimeTaskDefinition runtimeExtractTask =
                requireRuntimeTask(runtimeTaskByName, taskExtractName);
        RuntimeTaskDefinition runtimeLoadTask = requireRuntimeTask(runtimeTaskByName, taskLoadName);

        Map<Long, Integer> taskVersionByCode =
                runtimeDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .filter(task -> task.getTaskCode() != null)
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskCode,
                                        task ->
                                                task.getTaskVersion() != null
                                                        ? task.getTaskVersion()
                                                        : 1,
                                        (a, b) -> a,
                                        LinkedHashMap::new));

        List<Map<String, Object>> taskDefinitions =
                runtimeDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .map(
                                task ->
                                        buildRuntimeSqlTaskDefinition(
                                                task,
                                                task.getSql(),
                                                StringUtils.hasText(task.getDatasourceType())
                                                        ? task.getDatasourceType()
                                                        : "MYSQL"))
                        .collect(Collectors.toList());

        List<DolphinSchedulerService.TaskRelationPayload> mismatchedRelations = new ArrayList<>();
        mismatchedRelations.add(
                buildRelation(0L, 0, runtimeExtractTask.getTaskCode(), taskVersionByCode));
        mismatchedRelations.add(
                buildRelation(0L, 0, runtimeLoadTask.getTaskCode(), taskVersionByCode));

        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();
        List<Long> orderedCodes =
                runtimeDefinition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .map(RuntimeTaskDefinition::getTaskCode)
                        .filter(Objects::nonNull)
                        .sorted()
                        .collect(Collectors.toList());
        for (int i = 0; i < orderedCodes.size(); i++) {
            locations.add(dolphinSchedulerService.buildLocation(orderedCodes.get(i), i, 0));
        }

        String globalParams =
                StringUtils.hasText(runtimeDefinition.getGlobalParams())
                        ? runtimeDefinition.getGlobalParams()
                        : "[]";
        try {
            dolphinSchedulerService.setWorkflowReleaseState(runtimeWorkflowCode, "OFFLINE");
            sleepQuietly(1000L);
        } catch (Exception ignored) {
        }
        long updatedCode =
                dolphinSchedulerService.syncWorkflow(
                        runtimeWorkflowCode,
                        runtimeDefinition.getWorkflowName(),
                        runtimeDefinition.getDescription(),
                        taskDefinitions,
                        mismatchedRelations,
                        locations,
                        globalParams);
        assertEquals(runtimeWorkflowCode.longValue(), updatedCode, "关系冲突演进应命中同一 workflowCode");
        assertExportDefinitionAvailable(runtimeWorkflowCode, "关系冲突演进后");

        RuntimeSyncPreviewRequest previewRequest = new RuntimeSyncPreviewRequest();
        previewRequest.setProjectCode(effectiveProjectCode);
        previewRequest.setWorkflowCode(runtimeWorkflowCode);
        previewRequest.setOperator("it-runtime-sync");
        RuntimeSyncPreviewResponse preview = workflowRuntimeSyncService.preview(previewRequest);

        assertTrue(
                Boolean.TRUE.equals(preview.getCanSync()),
                "关系冲突场景预检应可继续, errors="
                        + safeJson(preview.getErrors())
                        + ", warnings="
                        + safeJson(preview.getWarnings()));
        assertTrue(preview.getErrors().isEmpty(), "关系冲突场景预检不应返回错误");
        assertTrue(Boolean.TRUE.equals(preview.getRelationDecisionRequired()), "关系冲突时应要求用户选轨");
        assertTrue(
                preview.getWarnings().stream()
                        .anyMatch(
                                issue ->
                                        RuntimeSyncErrorCodes.RELATION_MISMATCH.equals(
                                                issue.getCode())),
                "关系冲突时应返回 RELATION_MISMATCH 告警");
        assertNotNull(preview.getRelationCompareDetail(), "关系冲突时应返回关系比对详情");
        assertTrue(
                preview.getRelationCompareDetail().getOnlyInDeclared().stream()
                        .anyMatch(
                                item ->
                                        item.getPreTaskCode() != null
                                                && item.getPreTaskCode() == 0L
                                                && Objects.equals(
                                                        item.getPostTaskCode(),
                                                        runtimeLoadTask.getTaskCode())),
                "应识别仅在声明关系中的入口边 0->load");
        assertTrue(
                preview.getRelationCompareDetail().getOnlyInInferred().stream()
                        .anyMatch(
                                item ->
                                        Objects.equals(
                                                        item.getPreTaskCode(),
                                                        runtimeExtractTask.getTaskCode())
                                                && Objects.equals(
                                                        item.getPostTaskCode(),
                                                        runtimeLoadTask.getTaskCode())),
                "应识别仅在 SQL 推断关系中的 extract->load");

        RuntimeSyncExecuteRequest syncWithoutDecision = new RuntimeSyncExecuteRequest();
        syncWithoutDecision.setProjectCode(effectiveProjectCode);
        syncWithoutDecision.setWorkflowCode(runtimeWorkflowCode);
        syncWithoutDecision.setOperator("it-runtime-sync");
        RuntimeSyncExecuteResponse rejected = workflowRuntimeSyncService.sync(syncWithoutDecision);
        assertFalse(Boolean.TRUE.equals(rejected.getSuccess()), "未选轨执行应失败");
        assertTrue(Boolean.TRUE.equals(rejected.getRelationDecisionRequired()), "未选轨失败时应提示需要选轨");
        assertTrue(
                rejected.getErrors().stream()
                        .anyMatch(
                                issue ->
                                        RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED.equals(
                                                issue.getCode())),
                "未选轨失败应返回 RELATION_DECISION_REQUIRED");
        assertNotNull(rejected.getSyncRecordId(), "未选轨失败应落失败记录");

        RuntimeSyncExecuteRequest syncWithDecision = new RuntimeSyncExecuteRequest();
        syncWithDecision.setProjectCode(effectiveProjectCode);
        syncWithDecision.setWorkflowCode(runtimeWorkflowCode);
        syncWithDecision.setOperator("it-runtime-sync");
        syncWithDecision.setRelationDecision("INFERRED");
        RuntimeSyncExecuteResponse accepted = workflowRuntimeSyncService.sync(syncWithDecision);
        assertTrue(
                Boolean.TRUE.equals(accepted.getSuccess()),
                "人工选轨后同步应成功, errors="
                        + safeJson(accepted.getErrors())
                        + ", warnings="
                        + safeJson(accepted.getWarnings()));
        assertTrue(accepted.getErrors().isEmpty(), "人工选轨成功后不应返回错误");
        assertNotNull(accepted.getWorkflowId(), "人工选轨成功后应返回 workflowId");
        assertNotNull(accepted.getSyncRecordId(), "人工选轨成功后应返回同步记录");

        RuntimeWorkflowDiffResponse diffAfterSync =
                workflowRuntimeSyncService.runtimeDiff(accepted.getWorkflowId());
        assertTrue(diffAfterSync.getErrors().isEmpty(), "人工选轨同步后 diff 不应报错");
        assertFalse(
                Boolean.TRUE.equals(diffAfterSync.getDiffSummary().getChanged()), "人工选轨同步后应与运行态一致");

        List<WorkflowRuntimeSyncRecord> syncRecords =
                workflowRuntimeSyncRecordMapper.selectList(
                        Wrappers.<WorkflowRuntimeSyncRecord>lambdaQuery()
                                .eq(
                                        WorkflowRuntimeSyncRecord::getWorkflowId,
                                        accepted.getWorkflowId())
                                .orderByAsc(WorkflowRuntimeSyncRecord::getId));
        assertTrue(
                syncRecords.stream()
                        .anyMatch(
                                item ->
                                        "failed".equals(item.getStatus())
                                                && RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED
                                                        .equals(item.getErrorCode())));
        assertTrue(syncRecords.stream().anyMatch(item -> "success".equals(item.getStatus())));
    }

    @Test
    @DisplayName("首次发布后仅变更单个任务时，未变更任务不应出现发布差异")
    void publishPreviewShouldOnlyShowChangedTasksForExistingRuntimeWorkflow() {
        bootstrapOrSkip();
        cleanupHistoricalITData();

        DolphinTaskGroupOption taskGroup = resolveAvailableTaskGroup();
        assertNotNull(taskGroup, "集成环境缺少可用任务组，无法验证 taskGroupName 差异收敛");
        assertTrue(StringUtils.hasText(taskGroup.getName()), "任务组名称不能为空");

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String dbName = "opendataworks";
        String srcTable = IT_PREFIX + "src_preview_" + suffix;
        String dwdTable = IT_PREFIX + "dwd_preview_" + suffix;
        String dwsTable = IT_PREFIX + "dws_preview_" + suffix;

        Long srcTableId = createMetadataTable(dbName, srcTable, "ODS");
        Long dwdTableId = createMetadataTable(dbName, dwdTable, "DWD");
        Long dwsTableId = createMetadataTable(dbName, dwsTable, "DWS");

        DataTask taskExtract =
                createSqlTask(
                        IT_PREFIX + "task_extract_preview_" + suffix,
                        IT_PREFIX + "task_extract_preview_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, user_name FROM %s.%s",
                                dbName, dwdTable, dbName, srcTable),
                        Collections.singletonList(srcTableId),
                        Collections.singletonList(dwdTableId));
        taskExtract.setTaskGroupName(taskGroup.getName());
        dataTaskMapper.updateById(taskExtract);

        DataTask taskLoad =
                createSqlTask(
                        IT_PREFIX + "task_load_preview_" + suffix,
                        IT_PREFIX + "task_load_preview_" + suffix,
                        String.format(
                                "INSERT INTO %s.%s SELECT user_id, COUNT(1) AS total FROM %s.%s"
                                        + " GROUP BY user_id",
                                dbName, dwsTable, dbName, dwdTable),
                        Collections.singletonList(dwdTableId),
                        Collections.singletonList(dwsTableId));
        taskLoad.setTaskGroupName(taskGroup.getName());
        dataTaskMapper.updateById(taskLoad);

        DataWorkflow workflow =
                createWorkflow(
                        IT_PREFIX + "wf_publish_preview_" + suffix,
                        Arrays.asList(taskExtract.getId(), taskLoad.getId()),
                        effectiveProjectCode);
        createdWorkflowIds.add(workflow.getId());

        WorkflowPublishPreviewResponse firstPreview =
                workflowPublishService.previewPublish(workflow.getId());
        assertTrue(
                firstPreview.getErrors().isEmpty(),
                "首次发布前预检不应报错, errors=" + safeJson(firstPreview.getErrors()));
        assertTrue(
                firstPreview.getRepairIssues().stream()
                        .noneMatch(
                                item ->
                                        "task.datasourceId".equals(item.getField())
                                                || "task.taskGroupId".equals(item.getField())),
                "保存后首次预检不应再提示 datasourceId/taskGroupId 修复: "
                        + safeJson(firstPreview.getRepairIssues()));

        WorkflowPublishRequest deployRequest = new WorkflowPublishRequest();
        deployRequest.setOperation("deploy");
        deployRequest.setOperator("it-runtime-sync");
        deployRequest.setRequireApproval(false);
        deployRequest.setConfirmDiff(true);
        WorkflowPublishRecord deployRecord =
                workflowPublishService.publish(workflow.getId(), deployRequest);
        assertEquals("success", deployRecord.getStatus(), "基线发布应成功");

        DataWorkflow deployedWorkflow = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(deployedWorkflow);
        assertNotNull(deployedWorkflow.getWorkflowCode(), "发布后应存在 workflowCode");
        createdRuntimeWorkflowCodes.add(deployedWorkflow.getWorkflowCode());

        DataTask latestExtractTask = dataTaskMapper.selectById(taskExtract.getId());
        DataTask latestLoadTask = dataTaskMapper.selectById(taskLoad.getId());
        assertNotNull(latestExtractTask);
        assertNotNull(latestLoadTask);
        assertNotNull(latestExtractTask.getDolphinTaskCode(), "任务1应具备 Dolphin taskCode");
        assertNotNull(latestLoadTask.getDolphinTaskCode(), "任务2应具备 Dolphin taskCode");

        latestLoadTask.setTaskSql(
                String.format(
                        "INSERT INTO %s.%s SELECT user_id, COUNT(1) AS total FROM %s.%s WHERE"
                                + " user_id > 0 GROUP BY user_id",
                        dbName, dwsTable, dbName, dwdTable));
        dataTaskMapper.updateById(latestLoadTask);
        workflowService.normalizeAndPersistMetadata(workflow.getId(), "it-runtime-sync");

        WorkflowPublishPreviewResponse deltaPreview =
                workflowPublishService.previewPublish(workflow.getId());
        assertTrue(
                deltaPreview.getErrors().isEmpty(),
                "增量发布预检不应报错, errors=" + safeJson(deltaPreview.getErrors()));
        assertTrue(Boolean.TRUE.equals(deltaPreview.getCanPublish()), "增量发布预检应允许发布");
        assertNotNull(deltaPreview.getDiffSummary(), "增量发布差异摘要不能为空");
        assertTrue(
                deltaPreview.getRepairIssues().stream()
                        .noneMatch(
                                item ->
                                        "task.datasourceId".equals(item.getField())
                                                || "task.taskGroupId".equals(item.getField())),
                "增量发布预检不应再提示 datasourceId/taskGroupId 修复: "
                        + safeJson(deltaPreview.getRepairIssues()));

        assertTrue(
                deltaPreview.getDiffSummary().getTaskModified().stream()
                        .anyMatch(
                                item ->
                                        Objects.equals(
                                                item.getTaskCode(),
                                                latestLoadTask.getDolphinTaskCode())),
                "应识别被修改的 task_load 差异");
        assertTrue(
                deltaPreview.getDiffSummary().getTaskModified().stream()
                        .noneMatch(
                                item ->
                                        Objects.equals(
                                                item.getTaskCode(),
                                                latestExtractTask.getDolphinTaskCode())),
                "未变更的 task_extract 不应出现在差异列表, taskModified="
                        + safeJson(deltaPreview.getDiffSummary().getTaskModified()));
    }

    private RuntimeSyncExecuteResponse previewAndSync(Long workflowCode, String operator) {
        RuntimeSyncPreviewRequest previewRequest = new RuntimeSyncPreviewRequest();
        previewRequest.setProjectCode(effectiveProjectCode);
        previewRequest.setWorkflowCode(workflowCode);
        previewRequest.setOperator(operator);
        RuntimeSyncPreviewResponse preview = workflowRuntimeSyncService.preview(previewRequest);

        assertTrue(
                Boolean.TRUE.equals(preview.getCanSync()),
                "运行态预检应通过, errors="
                        + safeJson(preview.getErrors())
                        + ", warnings="
                        + safeJson(preview.getWarnings()));
        assertTrue(
                preview.getErrors().isEmpty(), "预检不应返回错误, errors=" + safeJson(preview.getErrors()));

        RuntimeSyncExecuteRequest syncRequest = new RuntimeSyncExecuteRequest();
        syncRequest.setProjectCode(effectiveProjectCode);
        syncRequest.setWorkflowCode(workflowCode);
        syncRequest.setOperator(operator);
        if (Boolean.TRUE.equals(preview.getRelationDecisionRequired())) {
            syncRequest.setRelationDecision("INFERRED");
        }

        RuntimeSyncExecuteResponse sync = workflowRuntimeSyncService.sync(syncRequest);
        assertTrue(
                Boolean.TRUE.equals(sync.getSuccess()),
                "运行态同步应成功, errors="
                        + safeJson(sync.getErrors())
                        + ", warnings="
                        + safeJson(sync.getWarnings()));
        assertTrue(sync.getErrors().isEmpty(), "同步不应返回错误, errors=" + safeJson(sync.getErrors()));
        assertNotNull(sync.getWorkflowId(), "同步应返回 workflowId");
        assertNotNull(sync.getVersionNo(), "同步应返回 versionNo");
        assertNotNull(sync.getSyncRecordId(), "同步应返回 syncRecordId");
        return sync;
    }

    private void truncateTables(String dbName, String... tableNames) {
        if (tableNames == null) {
            return;
        }
        for (String tableName : tableNames) {
            if (!StringUtils.hasText(tableName)) {
                continue;
            }
            executeJdbc(String.format("TRUNCATE TABLE %s.%s", dbName, tableName));
        }
    }

    private void executeAndAssertResult(
            Long workflowId,
            Long runtimeWorkflowCode,
            String dbName,
            String resultTableName,
            Long expectedCount,
            BigDecimal expectedSum,
            String operator) {
        WorkflowPublishRequest onlineRequest = new WorkflowPublishRequest();
        onlineRequest.setOperation("online");
        onlineRequest.setOperator(operator);
        onlineRequest.setRequireApproval(false);
        WorkflowPublishRecord onlineRecord =
                workflowPublishService.publish(workflowId, onlineRequest);
        assertEquals("success", onlineRecord.getStatus(), "上线操作应成功");

        assertWorkflowExecutionProducesExpectedResult(
                workflowId,
                runtimeWorkflowCode,
                operator,
                dbName,
                resultTableName,
                expectedCount,
                expectedSum,
                4,
                90000L);
    }

    private String triggerWorkflowExecution(Long workflowId, Long runtimeWorkflowCode) {
        String executionId = workflowService.executeWorkflow(workflowId);
        assertTrue(StringUtils.hasText(executionId), "执行后应返回 executionId");
        return executionId;
    }

    private void assertWorkflowExecutionProducesExpectedResult(
            Long workflowId,
            Long runtimeWorkflowCode,
            String operator,
            String dbName,
            String resultTableName,
            Long expectedCount,
            BigDecimal expectedSum,
            int maxAttempts,
            long perAttemptTimeoutMillis,
            String... cleanupTables) {
        int attempts = Math.max(1, maxAttempts);
        StringBuilder diagnostics = new StringBuilder();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (cleanupTables != null && cleanupTables.length > 0) {
                truncateTables(dbName, cleanupTables);
            }

            WorkflowPublishRequest onlineRequest = new WorkflowPublishRequest();
            onlineRequest.setOperation("online");
            onlineRequest.setOperator(operator);
            onlineRequest.setRequireApproval(false);
            WorkflowPublishRecord onlineRecord =
                    workflowPublishService.publish(workflowId, onlineRequest);
            assertEquals("success", onlineRecord.getStatus(), "第 " + attempt + " 次执行前上线应成功");

            String executionId = triggerWorkflowExecution(workflowId, runtimeWorkflowCode);
            if (awaitResultTable(
                    dbName, resultTableName, expectedCount, expectedSum, perAttemptTimeoutMillis)) {
                return;
            }

            if (diagnostics.length() > 0) {
                diagnostics.append(" | ");
            }
            diagnostics
                    .append("attempt=")
                    .append(attempt)
                    .append(", executionId=")
                    .append(executionId)
                    .append(", instances=")
                    .append(describeLatestWorkflowInstances(runtimeWorkflowCode, 5));
            sleepQuietly(1500L);
        }

        throw new AssertionError(
                "工作流执行未达到预期结果, expectedCount="
                        + expectedCount
                        + ", expectedSum="
                        + expectedSum
                        + ", diagnostics="
                        + diagnostics);
    }

    private boolean awaitResultTable(
            String dbName,
            String resultTableName,
            Long expectedCount,
            BigDecimal expectedSum,
            long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 30000L);
        while (System.currentTimeMillis() < deadline) {
            Long rowCount =
                    queryForLong(
                            String.format("SELECT COUNT(1) FROM %s.%s", dbName, resultTableName));
            BigDecimal totalSum =
                    queryForDecimal(
                            String.format(
                                    "SELECT COALESCE(SUM(total_pay), 0) FROM %s.%s",
                                    dbName, resultTableName));
            if (rowCount != null
                    && expectedCount != null
                    && expectedCount.equals(rowCount)
                    && totalSum != null
                    && expectedSum != null
                    && totalSum.compareTo(expectedSum) == 0) {
                return true;
            }
            sleepQuietly(2000L);
        }
        return false;
    }

    private String describeLatestWorkflowInstances(Long workflowCode, int limit) {
        List<WorkflowInstanceSummary> instances =
                dolphinSchedulerService.listWorkflowInstances(workflowCode, Math.max(limit, 1));
        if (instances == null || instances.isEmpty()) {
            return "[]";
        }
        return instances.stream()
                .filter(Objects::nonNull)
                .sorted(
                        (a, b) ->
                                Long.compare(
                                        b.getInstanceId() != null ? b.getInstanceId() : 0L,
                                        a.getInstanceId() != null ? a.getInstanceId() : 0L))
                .limit(Math.max(limit, 1))
                .map(
                        item ->
                                String.format(
                                        "{id=%s,state=%s,start=%s,end=%s,cmd=%s}",
                                        item.getInstanceId(),
                                        normalizeState(item.getState()),
                                        item.getStartTime(),
                                        item.getEndTime(),
                                        item.getCommandType()))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private void evolveRuntimeWorkflowForEvolutionScenario(
            Long runtimeWorkflowCode,
            String task1Name,
            String task2Name,
            String task3Name,
            String task4Name,
            String task5Name,
            String task6Name,
            String task7Name,
            String dbName,
            String stgUserTable,
            String stgOrderTable,
            String stgPayTable,
            String dwdOrderUserTable,
            String dwdOrderPayTable,
            String dwsUserAmountTable,
            String evolvedScheduleCron,
            String evolvedScheduleTimezone,
            LocalDateTime evolvedScheduleStart,
            LocalDateTime evolvedScheduleEnd,
            Integer evolvedTaskGroupId) {
        RuntimeWorkflowDefinition definition =
                dolphinRuntimeDefinitionService.loadRuntimeDefinitionFromExport(
                        effectiveProjectCode, runtimeWorkflowCode);
        assertNotNull(definition, "运行态工作流定义不能为空");
        assertTrue(
                definition.getTasks() != null && !definition.getTasks().isEmpty(), "运行态任务定义不能为空");

        Map<String, RuntimeTaskDefinition> runtimeTaskByName =
                definition.getTasks().stream()
                        .filter(Objects::nonNull)
                        .filter(task -> StringUtils.hasText(task.getTaskName()))
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskName,
                                        item -> item,
                                        (a, b) -> a));

        RuntimeTaskDefinition task1 = requireRuntimeTask(runtimeTaskByName, task1Name);
        RuntimeTaskDefinition task2 = requireRuntimeTask(runtimeTaskByName, task2Name);
        RuntimeTaskDefinition task3 = requireRuntimeTask(runtimeTaskByName, task3Name);
        RuntimeTaskDefinition task4 = requireRuntimeTask(runtimeTaskByName, task4Name);
        RuntimeTaskDefinition task5 = requireRuntimeTask(runtimeTaskByName, task5Name);
        RuntimeTaskDefinition task6 = requireRuntimeTask(runtimeTaskByName, task6Name);

        Long datasourceId = task1.getDatasourceId();
        assertNotNull(datasourceId, "SQL 节点 datasourceId 不能为空");
        String datasourceType =
                StringUtils.hasText(task1.getDatasourceType())
                        ? task1.getDatasourceType()
                        : "MYSQL";

        String evolvedTask4Sql =
                String.format(
                        "INSERT INTO %s.%s SELECT o.order_id, o.user_id, 'unknown' AS user_name,"
                                + " o.order_amount FROM %s.%s o",
                        dbName, dwdOrderUserTable, dbName, stgOrderTable);

        String evolvedTask5Sql =
                String.format(
                        "INSERT INTO %s.%s SELECT ou.order_id, ou.user_id, su.user_name,"
                            + " ou.order_amount, p.pay_id, p.pay_amount FROM %s.%s ou JOIN %s.%s p"
                            + " ON ou.order_id = p.order_id JOIN %s.%s su ON ou.user_id ="
                            + " su.user_id WHERE p.pay_amount > 90",
                        dbName,
                        dwdOrderPayTable,
                        dbName,
                        dwdOrderUserTable,
                        dbName,
                        stgPayTable,
                        dbName,
                        stgUserTable);

        String task7Sql =
                String.format(
                        "INSERT INTO %s.%s "
                                + "SELECT user_id, SUM(pay_amount) AS total_pay "
                                + "FROM %s.%s GROUP BY user_id",
                        dbName, dwsUserAmountTable, dbName, dwdOrderPayTable);

        long task7Code = dolphinSchedulerService.nextTaskCode();
        int task7Version = 1;
        RuntimeTaskDefinition task7 = new RuntimeTaskDefinition();
        task7.setTaskCode(task7Code);
        task7.setTaskVersion(task7Version);
        task7.setTaskName(task7Name);
        task7.setDescription("runtime-evolved-task");
        task7.setDatasourceId(datasourceId);
        task7.setDatasourceType(datasourceType);
        task7.setTaskPriority(
                StringUtils.hasText(task6.getTaskPriority()) ? task6.getTaskPriority() : "MEDIUM");
        task7.setRetryTimes(task6.getRetryTimes() != null ? task6.getRetryTimes() : 1);
        task7.setRetryInterval(task6.getRetryInterval() != null ? task6.getRetryInterval() : 1);
        task7.setTimeoutSeconds(
                task6.getTimeoutSeconds() != null ? task6.getTimeoutSeconds() : 300);
        Integer targetTaskGroupId =
                evolvedTaskGroupId != null ? evolvedTaskGroupId : task6.getTaskGroupId();
        task7.setTaskGroupId(targetTaskGroupId);

        List<RuntimeTaskDefinition> evolvedTasks =
                Arrays.asList(task1, task2, task3, task4, task5, task7);
        Map<Long, Integer> taskVersionByCode =
                evolvedTasks.stream()
                        .collect(
                                Collectors.toMap(
                                        RuntimeTaskDefinition::getTaskCode,
                                        task ->
                                                task.getTaskVersion() != null
                                                        ? task.getTaskVersion()
                                                        : 1,
                                        (a, b) -> a,
                                        LinkedHashMap::new));

        List<Map<String, Object>> taskDefinitions = new ArrayList<>();
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(
                        task1, task1.getSql(), datasourceType, targetTaskGroupId));
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(
                        task2, task2.getSql(), datasourceType, targetTaskGroupId));
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(
                        task3, task3.getSql(), datasourceType, targetTaskGroupId));
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(
                        task4, evolvedTask4Sql, datasourceType, targetTaskGroupId));
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(
                        task5, evolvedTask5Sql, datasourceType, targetTaskGroupId));
        taskDefinitions.add(
                buildRuntimeSqlTaskDefinition(task7, task7Sql, datasourceType, targetTaskGroupId));

        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        relations.add(buildRelation(0L, 0, task1.getTaskCode(), taskVersionByCode));
        relations.add(buildRelation(0L, 0, task2.getTaskCode(), taskVersionByCode));
        relations.add(buildRelation(0L, 0, task3.getTaskCode(), taskVersionByCode));
        relations.add(
                buildRelation(
                        task2.getTaskCode(),
                        taskVersionByCode.get(task2.getTaskCode()),
                        task4.getTaskCode(),
                        taskVersionByCode));
        relations.add(
                buildRelation(
                        task4.getTaskCode(),
                        taskVersionByCode.get(task4.getTaskCode()),
                        task5.getTaskCode(),
                        taskVersionByCode));
        relations.add(
                buildRelation(
                        task3.getTaskCode(),
                        taskVersionByCode.get(task3.getTaskCode()),
                        task5.getTaskCode(),
                        taskVersionByCode));
        relations.add(
                buildRelation(
                        task1.getTaskCode(),
                        taskVersionByCode.get(task1.getTaskCode()),
                        task5.getTaskCode(),
                        taskVersionByCode));
        relations.add(
                buildRelation(
                        task5.getTaskCode(),
                        taskVersionByCode.get(task5.getTaskCode()),
                        task7.getTaskCode(),
                        taskVersionByCode));

        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();
        List<Long> orderedTaskCodes =
                Arrays.asList(
                        task1.getTaskCode(),
                        task2.getTaskCode(),
                        task3.getTaskCode(),
                        task4.getTaskCode(),
                        task5.getTaskCode(),
                        task7.getTaskCode());
        for (int i = 0; i < orderedTaskCodes.size(); i++) {
            int lane;
            if (i <= 2) {
                lane = i;
            } else if (i == 3) {
                lane = 1;
            } else if (i == 4) {
                lane = 1;
            } else {
                lane = 1;
            }
            locations.add(dolphinSchedulerService.buildLocation(orderedTaskCodes.get(i), i, lane));
        }

        String globalParams =
                StringUtils.hasText(definition.getGlobalParams())
                        ? definition.getGlobalParams()
                        : "[]";
        try {
            dolphinSchedulerService.setWorkflowReleaseState(runtimeWorkflowCode, "OFFLINE");
            sleepQuietly(1000L);
        } catch (Exception ignored) {
        }
        long updatedCode =
                dolphinSchedulerService.syncWorkflow(
                        runtimeWorkflowCode,
                        definition.getWorkflowName(),
                        definition.getDescription(),
                        taskDefinitions,
                        relations,
                        locations,
                        globalParams);
        assertEquals(runtimeWorkflowCode.longValue(), updatedCode, "运行态演进更新应命中同一 workflowCode");

        upsertRuntimeSchedule(
                runtimeWorkflowCode,
                evolvedScheduleCron,
                evolvedScheduleTimezone,
                evolvedScheduleStart,
                evolvedScheduleEnd);
    }

    private RuntimeTaskDefinition requireRuntimeTask(
            Map<String, RuntimeTaskDefinition> taskByName, String taskName) {
        RuntimeTaskDefinition task = taskByName.get(taskName);
        assertNotNull(task, "运行态缺少任务: " + taskName);
        assertNotNull(task.getTaskCode(), "运行态任务缺少 taskCode: " + taskName);
        return task;
    }

    private Map<String, Object> buildRuntimeSqlTaskDefinition(
            RuntimeTaskDefinition task, String sql, String datasourceTypeFallback) {
        return buildRuntimeSqlTaskDefinition(task, sql, datasourceTypeFallback, null);
    }

    private Map<String, Object> buildRuntimeSqlTaskDefinition(
            RuntimeTaskDefinition task,
            String sql,
            String datasourceTypeFallback,
            Integer overrideTaskGroupId) {
        String priority =
                StringUtils.hasText(task.getTaskPriority()) ? task.getTaskPriority() : "MEDIUM";
        int retryTimes = task.getRetryTimes() != null ? task.getRetryTimes() : 1;
        int retryInterval = task.getRetryInterval() != null ? task.getRetryInterval() : 1;
        int timeoutSeconds = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 300;
        String description =
                StringUtils.hasText(task.getDescription())
                        ? task.getDescription()
                        : "runtime-evolved-task";
        String datasourceType =
                StringUtils.hasText(task.getDatasourceType())
                        ? task.getDatasourceType()
                        : datasourceTypeFallback;
        Integer resolvedTaskGroupId =
                overrideTaskGroupId != null ? overrideTaskGroupId : task.getTaskGroupId();
        return dolphinSchedulerService.buildTaskDefinition(
                task.getTaskCode(),
                task.getTaskVersion() != null ? task.getTaskVersion() : 1,
                task.getTaskName(),
                description,
                sql,
                priority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                "SQL",
                task.getDatasourceId(),
                datasourceType,
                resolvedTaskGroupId,
                0);
    }

    private DolphinSchedulerService.TaskRelationPayload buildRelation(
            Long upstreamCode,
            Integer upstreamVersion,
            Long downstreamCode,
            Map<Long, Integer> taskVersionByCode) {
        int resolvedUpstreamVersion = upstreamVersion != null ? upstreamVersion : 0;
        int resolvedDownstreamVersion = taskVersionByCode.getOrDefault(downstreamCode, 1);
        return dolphinSchedulerService.buildRelation(
                upstreamCode != null ? upstreamCode : 0L,
                resolvedUpstreamVersion,
                downstreamCode,
                resolvedDownstreamVersion);
    }

    private Integer resolveEvolvedTaskGroupId(Integer baselineTaskGroupId) {
        Integer baseline = baselineTaskGroupId != null ? baselineTaskGroupId : 0;
        try {
            DolphinPageData<DolphinTaskGroup> page =
                    dolphinOpenApiClient.listTaskGroups(
                            dolphinSchedulerService.getConfig(null), 1, 200, null, null);
            if (page != null && page.getTotalList() != null) {
                for (DolphinTaskGroup group : page.getTotalList()) {
                    if (group == null || group.getId() == null || group.getId() <= 0) {
                        continue;
                    }
                    if (!Objects.equals(group.getId(), baseline)) {
                        return group.getId();
                    }
                }
                if (baseline <= 0) {
                    for (DolphinTaskGroup group : page.getTotalList()) {
                        if (group == null || group.getId() == null || group.getId() <= 0) {
                            continue;
                        }
                        return group.getId();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return baseline;
    }

    private void upsertRuntimeSchedule(
            Long workflowCode,
            String cron,
            String timezone,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        LocalDateTime resolvedStart =
                startTime != null
                        ? startTime
                        : LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0);
        LocalDateTime resolvedEnd =
                endTime != null && endTime.isAfter(resolvedStart)
                        ? endTime
                        : resolvedStart.plusMonths(6);
        String resolvedCron = StringUtils.hasText(cron) ? cron.trim() : "0 0 2 * * ? *";
        String resolvedTimezone = StringUtils.hasText(timezone) ? timezone.trim() : "Asia/Shanghai";
        String workerGroup = dolphinSchedulerService.getDefaultWorkerGroup();
        String tenantCode = dolphinSchedulerService.getDefaultTenantCode();
        String scheduleJson =
                buildScheduleJson(resolvedStart, resolvedEnd, resolvedTimezone, resolvedCron);

        try {
            dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "ONLINE");
            sleepQuietly(500L);
        } catch (Exception ignored) {
        }

        DolphinSchedule existing = dolphinSchedulerService.getWorkflowSchedule(workflowCode);
        if (existing == null || existing.getId() == null || existing.getId() <= 0) {
            Long scheduleId =
                    dolphinSchedulerService.createWorkflowSchedule(
                            workflowCode,
                            scheduleJson,
                            "NONE",
                            "CONTINUE",
                            0L,
                            "MEDIUM",
                            workerGroup,
                            tenantCode,
                            -1L);
            if (scheduleId != null && scheduleId > 0) {
                try {
                    dolphinSchedulerService.onlineWorkflowSchedule(scheduleId);
                } catch (Exception ignored) {
                }
            }
            DolphinSchedule visibleSchedule = waitForRuntimeScheduleVisible(workflowCode, 15000L);
            if (visibleSchedule == null
                    || visibleSchedule.getId() == null
                    || visibleSchedule.getId() <= 0) {
                throw new IllegalStateException("创建运行态调度后未能查询到 schedule");
            }
            return;
        }

        try {
            dolphinSchedulerService.offlineWorkflowSchedule(existing.getId());
            sleepQuietly(500L);
        } catch (Exception ignored) {
        }

        dolphinSchedulerService.updateWorkflowSchedule(
                existing.getId(),
                workflowCode,
                scheduleJson,
                StringUtils.hasText(existing.getWarningType()) ? existing.getWarningType() : "NONE",
                StringUtils.hasText(existing.getFailureStrategy())
                        ? existing.getFailureStrategy()
                        : "CONTINUE",
                existing.getWarningGroupId() != null ? existing.getWarningGroupId() : 0L,
                StringUtils.hasText(existing.getProcessInstancePriority())
                        ? existing.getProcessInstancePriority()
                        : "MEDIUM",
                StringUtils.hasText(existing.getWorkerGroup())
                        ? existing.getWorkerGroup()
                        : workerGroup,
                StringUtils.hasText(existing.getTenantCode())
                        ? existing.getTenantCode()
                        : tenantCode,
                existing.getEnvironmentCode() != null ? existing.getEnvironmentCode() : -1L);

        try {
            dolphinSchedulerService.onlineWorkflowSchedule(existing.getId());
        } catch (Exception ignored) {
        }

        DolphinSchedule visibleSchedule = waitForRuntimeScheduleVisible(workflowCode, 15000L);
        if (visibleSchedule == null
                || visibleSchedule.getId() == null
                || visibleSchedule.getId() <= 0) {
            throw new IllegalStateException("更新运行态调度后未能查询到 schedule");
        }
    }

    private String buildScheduleJson(
            LocalDateTime startTime, LocalDateTime endTime, String timezoneId, String crontab) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put(
                "startTime", startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        schedule.put("endTime", endTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        schedule.put("timezoneId", timezoneId);
        schedule.put("crontab", crontab);
        try {
            return objectMapper.writeValueAsString(schedule);
        } catch (Exception ex) {
            throw new IllegalStateException("构建调度 JSON 失败", ex);
        }
    }

    private DolphinSchedule waitForRuntimeScheduleVisible(Long workflowCode, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 5000L);
        while (System.currentTimeMillis() < deadline) {
            DolphinSchedule schedule = dolphinSchedulerService.getWorkflowSchedule(workflowCode);
            if (schedule != null && schedule.getId() != null && schedule.getId() > 0) {
                return schedule;
            }
            sleepQuietly(500L);
        }
        return null;
    }

    private RuntimeDiffSummary parseRuntimeDiffSummary(String diffJson) {
        if (!StringUtils.hasText(diffJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(diffJson, RuntimeDiffSummary.class);
        } catch (Exception ex) {
            throw new IllegalStateException("解析 diffJson 失败: " + diffJson, ex);
        }
    }

    private boolean containsRawEdge(
            List<com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange> edgeDisplayItems,
            String rawEdge) {
        if (!StringUtils.hasText(rawEdge)
                || edgeDisplayItems == null
                || edgeDisplayItems.isEmpty()) {
            return false;
        }
        return edgeDisplayItems.stream()
                .filter(Objects::nonNull)
                .anyMatch(
                        item ->
                                rawEdge.equals(
                                        item.getPreTaskCode() + "->" + item.getPostTaskCode()));
    }

    private void assertExportDefinitionAvailable(Long workflowCode, String stage) {
        RuntimeWorkflowDefinition exportDefinition =
                dolphinRuntimeDefinitionService.loadRuntimeDefinitionFromExport(
                        effectiveProjectCode, workflowCode);
        assertNotNull(exportDefinition, stage + "导出路径定义不能为空");
        assertEquals(
                workflowCode, exportDefinition.getWorkflowCode(), stage + "导出定义 workflowCode 不一致");
        assertTrue(
                exportDefinition.getTasks() != null && !exportDefinition.getTasks().isEmpty(),
                stage + "导出定义任务不能为空");
    }

    private JsonNode parseJsonOrNull(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private JsonNode findRuntimeTaskSnapshot(JsonNode snapshotNode, String taskName) {
        if (snapshotNode == null || snapshotNode.isNull() || !StringUtils.hasText(taskName)) {
            return null;
        }
        JsonNode tasksNode = snapshotNode.path("tasks");
        if (!tasksNode.isArray()) {
            return null;
        }
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || taskNode.isNull()) {
                continue;
            }
            if (taskName.equals(readText(taskNode, "taskName"))) {
                return taskNode;
            }
        }
        return null;
    }

    private Integer readInteger(JsonNode node, String fieldName) {
        if (node == null || node.isNull() || !StringUtils.hasText(fieldName)) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        if (field.isIntegralNumber()) {
            return field.asInt();
        }
        String value = field.asText(null);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Set<Long> readTableIds(Long taskId) {
        return tableTaskRelationMapper
                .selectList(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .eq(TableTaskRelation::getTaskId, taskId)
                                .eq(TableTaskRelation::getRelationType, "read"))
                .stream()
                .map(TableTaskRelation::getTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> writeTableIds(Long taskId) {
        return tableTaskRelationMapper
                .selectList(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .eq(TableTaskRelation::getTaskId, taskId)
                                .eq(TableTaskRelation::getRelationType, "write"))
                .stream()
                .map(TableTaskRelation::getTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Long> lineageInputTableIds(Long taskId) {
        return dataLineageMapper
                .selectList(
                        Wrappers.<DataLineage>lambdaQuery()
                                .eq(DataLineage::getTaskId, taskId)
                                .eq(DataLineage::getLineageType, "input"))
                .stream()
                .map(DataLineage::getUpstreamTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private WorkflowRuntimeSyncRecord assertSyncRecord(
            Long syncRecordId, Long workflowId, Long workflowCode, String operator) {
        WorkflowRuntimeSyncRecord record = workflowRuntimeSyncRecordMapper.selectById(syncRecordId);
        assertNotNull(record, "同步历史记录必须存在");
        assertEquals(workflowId, record.getWorkflowId(), "同步历史 workflowId 不一致");
        assertEquals(effectiveProjectCode, record.getProjectCode(), "同步历史 projectCode 不一致");
        assertEquals(workflowCode, record.getWorkflowCode(), "同步历史 workflowCode 不一致");
        assertEquals(operator, record.getOperator(), "同步历史 operator 不一致");
        assertTrue(StringUtils.hasText(record.getSnapshotHash()), "同步历史 snapshotHash 不能为空");
        assertTrue(StringUtils.hasText(record.getSnapshotJson()), "同步历史 snapshotJson 不能为空");
        assertTrue(StringUtils.hasText(record.getDiffJson()), "同步历史 diffJson 不能为空");
        assertNotNull(record.getVersionId(), "同步成功记录必须关联版本ID");
        assertNotNull(record.getCreatedAt(), "同步历史 createdAt 不能为空");
        return record;
    }

    private WorkflowVersion assertWorkflowVersion(Long workflowId, Integer versionNo) {
        WorkflowVersion version =
                workflowVersionMapper.selectOne(
                        Wrappers.<WorkflowVersion>lambdaQuery()
                                .eq(WorkflowVersion::getWorkflowId, workflowId)
                                .eq(WorkflowVersion::getVersionNo, versionNo)
                                .last("LIMIT 1"));
        assertNotNull(version, "应存在对应的版本记录, versionNo=" + versionNo);
        assertTrue(StringUtils.hasText(version.getStructureSnapshot()), "版本结构快照不能为空");
        assertEquals(
                Integer.valueOf(3), version.getSnapshotSchemaVersion(), "新版本快照应为 V3 definition 结构");
        assertNotNull(version.getCreatedAt(), "版本创建时间不能为空");
        return version;
    }

    private Set<Long> extractTaskIdsFromStructureSnapshot(String structureSnapshot) {
        if (!StringUtils.hasText(structureSnapshot)) {
            return Collections.emptySet();
        }
        try {
            JsonNode root = objectMapper.readTree(structureSnapshot);
            JsonNode tasksNode = root.path("taskDefinitionList");
            if (!tasksNode.isArray()) {
                return Collections.emptySet();
            }
            Set<Long> taskIds = new LinkedHashSet<>();
            for (JsonNode taskNode : tasksNode) {
                long taskId = taskNode.path("xPlatformTaskMeta").path("taskId").asLong(0L);
                if (taskId <= 0L) {
                    taskId = taskNode.path("taskId").asLong(0L);
                }
                if (taskId > 0) {
                    taskIds.add(taskId);
                }
            }
            return taskIds;
        } catch (Exception ex) {
            throw new IllegalStateException("解析版本结构快照失败: " + structureSnapshot, ex);
        }
    }

    private void bootstrapOrSkip() {
        DolphinConfig config = dolphinConfigService.getActiveConfig();
        String configuredBaseUrl = config != null ? config.getUrl() : null;
        effectiveBaseUrl =
                StringUtils.hasText(System.getenv("DS_BASE_URL"))
                        ? System.getenv("DS_BASE_URL")
                        : (StringUtils.hasText(configuredBaseUrl)
                                ? configuredBaseUrl
                                : DEFAULT_DS_URL);

        assertTrue(
                waitForDolphinReachable(effectiveBaseUrl, 30, 2000L),
                "Dolphin 不可达，请先手动启动/重启后再执行集成测试: " + effectiveBaseUrl);

        effectiveToken = resolveValidToken(config);
        assertTrue(StringUtils.hasText(effectiveToken), "无法获取可用 Dolphin token");

        String projectName =
                StringUtils.hasText(System.getenv("DS_PROJECT_NAME"))
                        ? System.getenv("DS_PROJECT_NAME")
                        : (config != null && StringUtils.hasText(config.getProjectName())
                                ? config.getProjectName()
                                : IT_PROJECT_NAME);

        DolphinConfig updated = new DolphinConfig();
        updated.setUrl(effectiveBaseUrl);
        updated.setToken(effectiveToken);
        updated.setProjectName(projectName);
        updated.setTenantCode(
                config != null && StringUtils.hasText(config.getTenantCode())
                        ? config.getTenantCode()
                        : "default");
        updated.setWorkerGroup(
                config != null && StringUtils.hasText(config.getWorkerGroup())
                        ? config.getWorkerGroup()
                        : "default");
        updated.setExecutionType(
                config != null && StringUtils.hasText(config.getExecutionType())
                        ? config.getExecutionType()
                        : "PARALLEL");
        updated.setIsActive(true);
        if (config == null) dolphinConfigService.create(updated);
        else {
            updated.setId(config.getId());
            dolphinConfigService.update(config.getId(), updated);
        }
        dolphinSchedulerService.clearProjectCodeCache();

        effectiveProjectCode = ensureProject(projectName);
        effectiveDatasourceName = ensureDatasource();
        effectiveClusterId = ensureClusterId();

        assertTrue(effectiveProjectCode != null && effectiveProjectCode > 0, "无法准备 Dolphin 项目");
        assertTrue(StringUtils.hasText(effectiveDatasourceName), "无法准备 Dolphin 数据源");
        assertTrue(effectiveClusterId != null && effectiveClusterId > 0, "无法准备平台元数据集群");
    }

    private String resolveValidToken(DolphinConfig config) {
        List<String> candidates = new ArrayList<>();
        String envToken = System.getenv("DS_TOKEN");
        if (StringUtils.hasText(envToken)) {
            candidates.add(envToken.trim());
        }
        if (config != null && StringUtils.hasText(config.getToken())) {
            candidates.add(config.getToken().trim());
        }
        for (String candidate : candidates) {
            if (isTokenValid(candidate)) {
                return candidate;
            }
        }
        return loginAndGenerateToken();
    }

    private boolean isTokenValid(String token) {
        if (!StringUtils.hasText(token)) {
            return false;
        }
        try {
            JsonNode root =
                    webClient()
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/projects")
                                                    .queryParam("pageNo", 1)
                                                    .queryParam("pageSize", 1)
                                                    .build())
                            .header("token", token)
                            .retrieve()
                            .body(JsonNode.class);
            return root != null && root.path("code").asInt(-1) == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String loginAndGenerateToken() {
        String username =
                StringUtils.hasText(System.getenv("DS_USERNAME"))
                        ? System.getenv("DS_USERNAME")
                        : DEFAULT_DS_USERNAME;
        String password =
                StringUtils.hasText(System.getenv("DS_PASSWORD"))
                        ? System.getenv("DS_PASSWORD")
                        : DEFAULT_DS_PASSWORD;

        try {
            ResponseEntity<JsonNode> loginResponse =
                    webClient()
                            .post()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/login")
                                                    .queryParam("userName", username)
                                                    .queryParam("userPassword", password)
                                                    .build())
                            .retrieve()
                            .toEntity(JsonNode.class);
            if (loginResponse == null) {
                return null;
            }
            String sessionId =
                    extractSessionId(loginResponse.getHeaders(), loginResponse.getBody());
            if (!StringUtils.hasText(sessionId)) {
                return null;
            }
            Integer userId = resolveCurrentUserIdBySession(sessionId);
            if (userId == null || userId <= 0) {
                return null;
            }
            String expireTime =
                    LocalDateTime.now()
                            .plusDays(365)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            JsonNode createTokenResp =
                    webClient()
                            .post()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/access-tokens")
                                                    .queryParam("userId", userId)
                                                    .queryParam("expireTime", expireTime)
                                                    .build())
                            .header(HttpHeaders.COOKIE, "sessionId=" + sessionId)
                            .retrieve()
                            .body(JsonNode.class);

            String token = null;
            if (createTokenResp != null && createTokenResp.path("code").asInt(-1) == 0) {
                token = readText(createTokenResp.path("data"), "token");
            }
            if (!StringUtils.hasText(token)) {
                JsonNode generated =
                        webClient()
                                .post()
                                .uri(
                                        uriBuilder ->
                                                uriBuilder
                                                        .path("/access-tokens/generate")
                                                        .queryParam("userId", userId)
                                                        .queryParam("expireTime", expireTime)
                                                        .build())
                                .header(HttpHeaders.COOKIE, "sessionId=" + sessionId)
                                .retrieve()
                                .body(JsonNode.class);
                if (generated != null && generated.path("code").asInt(-1) == 0) {
                    token = generated.path("data").asText(null);
                }
            }
            if (isTokenValid(token)) {
                return token;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Integer resolveCurrentUserIdBySession(String sessionId) {
        try {
            JsonNode userInfoResp =
                    webClient()
                            .get()
                            .uri("/users/get-user-info")
                            .header(HttpHeaders.COOKIE, "sessionId=" + sessionId)
                            .retrieve()
                            .body(JsonNode.class);
            if (userInfoResp == null || userInfoResp.path("code").asInt(-1) != 0) {
                return null;
            }
            JsonNode data = userInfoResp.path("data");
            Integer found = findIntField(data, "id");
            if (found != null && found > 0) {
                return found;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Integer findIntField(JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode direct = node.get(key);
        if (direct != null && direct.isIntegralNumber()) {
            return direct.asInt();
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                Integer child = findIntField(entry.getValue(), key);
                if (child != null) {
                    return child;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                Integer found = findIntField(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String extractSessionId(HttpHeaders headers, JsonNode body) {
        List<String> setCookies =
                headers.getOrDefault(HttpHeaders.SET_COOKIE, Collections.emptyList());
        for (String cookie : setCookies) {
            if (!StringUtils.hasText(cookie)) {
                continue;
            }
            for (String item : cookie.split(";")) {
                String trimmed = item.trim();
                if (trimmed.startsWith("sessionId=")) {
                    return trimmed.substring("sessionId=".length());
                }
            }
        }
        if (body != null) {
            JsonNode sessionId = body.path("data").path("sessionId");
            if (sessionId.isTextual() && StringUtils.hasText(sessionId.asText())) {
                return sessionId.asText();
            }
        }
        return null;
    }

    private Long ensureProject(String projectName) {
        try {
            DolphinProject project =
                    dolphinOpenApiClient.getProject(
                            dolphinSchedulerService.getConfig(null), projectName);
            if (project != null && project.getCode() != null && project.getCode() > 0) {
                return project.getCode();
            }
            Long createdCode =
                    dolphinOpenApiClient.createProject(
                            dolphinSchedulerService.getConfig(null),
                            projectName,
                            "Runtime sync integration test project");
            if (createdCode != null && createdCode > 0) {
                return createdCode;
            }
            DolphinProject created =
                    dolphinOpenApiClient.getProject(
                            dolphinSchedulerService.getConfig(null), projectName);
            return created != null ? created.getCode() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String ensureDatasource() {
        List<DolphinDatasourceOption> options =
                dolphinSchedulerService.listDatasources(null, IT_DATASOURCE_NAME);
        if (options.stream().anyMatch(opt -> IT_DATASOURCE_NAME.equals(opt.getName()))) {
            return IT_DATASOURCE_NAME;
        }

        List<String> hostCandidates = new ArrayList<>();
        hostCandidates.add(System.getenv("IT_MYSQL_HOST"));
        hostCandidates.add(System.getenv("MYSQL_HOST"));
        hostCandidates.add("host.docker.internal");
        hostCandidates.add("mysql");
        hostCandidates.add("127.0.0.1");
        hostCandidates =
                hostCandidates.stream()
                        .filter(StringUtils::hasText)
                        .distinct()
                        .collect(Collectors.toList());

        int mysqlPort = parseIntOrDefault(System.getenv("IT_MYSQL_PORT"), 3306);
        String mysqlDb =
                StringUtils.hasText(System.getenv("IT_MYSQL_DATABASE"))
                        ? System.getenv("IT_MYSQL_DATABASE")
                        : "opendataworks";
        String mysqlUser =
                StringUtils.hasText(System.getenv("IT_MYSQL_USERNAME"))
                        ? System.getenv("IT_MYSQL_USERNAME")
                        : "opendataworks";
        String mysqlPwd =
                StringUtils.hasText(System.getenv("IT_MYSQL_PASSWORD"))
                        ? System.getenv("IT_MYSQL_PASSWORD")
                        : "opendataworks123";

        for (String host : hostCandidates) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", "MYSQL");
                payload.put("name", IT_DATASOURCE_NAME);
                payload.put("note", "integration-test datasource");
                payload.put("host", host);
                payload.put("port", mysqlPort);
                payload.put("userName", mysqlUser);
                payload.put("password", mysqlPwd);
                payload.put("database", mysqlDb);
                payload.put("connectType", "");
                payload.put("other", Collections.singletonMap("serverTimezone", "Asia/Shanghai"));

                JsonNode createResp =
                        webClient()
                                .post()
                                .uri("/datasources")
                                .header("token", effectiveToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(objectMapper.writeValueAsString(payload))
                                .retrieve()
                                .body(JsonNode.class);

                boolean success = createResp != null && createResp.path("code").asInt(-1) == 0;
                if (success) {
                    break;
                }
            } catch (Exception ignored) {
            }
        }

        List<DolphinDatasourceOption> reloaded =
                dolphinSchedulerService.listDatasources(null, IT_DATASOURCE_NAME);
        if (reloaded.stream().anyMatch(opt -> IT_DATASOURCE_NAME.equals(opt.getName()))) {
            return IT_DATASOURCE_NAME;
        }

        // 兜底：使用任意已存在 MySQL 数据源
        List<DolphinDatasourceOption> mysqlOptions =
                dolphinSchedulerService.listDatasources("MYSQL", null);
        if (!mysqlOptions.isEmpty()) {
            return mysqlOptions.get(0).getName();
        }
        return null;
    }

    private DolphinTaskGroupOption resolveAvailableTaskGroup() {
        List<DolphinTaskGroupOption> options = dolphinSchedulerService.listTaskGroups(null);
        DolphinTaskGroupOption selected = selectAvailableTaskGroup(options);
        if (selected != null) {
            return selected;
        }

        ensureTaskGroup();
        return selectAvailableTaskGroup(dolphinSchedulerService.listTaskGroups(null));
    }

    private DolphinTaskGroupOption selectAvailableTaskGroup(List<DolphinTaskGroupOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        for (DolphinTaskGroupOption option : options) {
            if (option == null
                    || option.getId() == null
                    || option.getId() <= 0
                    || !StringUtils.hasText(option.getName())) {
                continue;
            }
            if (effectiveProjectCode != null
                    && option.getProjectCode() != null
                    && Objects.equals(effectiveProjectCode, option.getProjectCode())) {
                return option;
            }
        }
        for (DolphinTaskGroupOption option : options) {
            if (option != null
                    && option.getId() != null
                    && option.getId() > 0
                    && StringUtils.hasText(option.getName())) {
                return option;
            }
        }
        return null;
    }

    private void ensureTaskGroup() {
        if (!StringUtils.hasText(effectiveToken)) {
            return;
        }
        String groupName = IT_PREFIX + "tg_default";
        String groupDesc = "integration-test task group";
        try {
            JsonNode resp =
                    webClient()
                            .post()
                            .uri(
                                    uriBuilder -> {
                                        if (effectiveProjectCode != null
                                                && effectiveProjectCode > 0) {
                                            return uriBuilder
                                                    .path("/task-group/create")
                                                    .queryParam("name", groupName)
                                                    .queryParam("description", groupDesc)
                                                    .queryParam("groupSize", 1)
                                                    .queryParam("projectCode", effectiveProjectCode)
                                                    .build();
                                        }
                                        return uriBuilder
                                                .path("/task-group/create")
                                                .queryParam("name", groupName)
                                                .queryParam("description", groupDesc)
                                                .queryParam("groupSize", 1)
                                                .build();
                                    })
                            .header("token", effectiveToken)
                            .retrieve()
                            .body(JsonNode.class);
            if (resp == null || resp.path("code").asInt(-1) != 0) {
                // Ignore create failure here and rely on follow-up list query.
            }
        } catch (Exception ignored) {
        }
    }

    private Long ensureClusterId() {
        DorisCluster existing =
                dorisClusterMapper.selectOne(
                        Wrappers.<DorisCluster>lambdaQuery()
                                .eq(DorisCluster::getDeleted, 0)
                                .orderByDesc(DorisCluster::getIsDefault)
                                .orderByDesc(DorisCluster::getId)
                                .last("LIMIT 1"));
        if (existing != null && existing.getId() != null) {
            return existing.getId();
        }

        DorisCluster cluster = new DorisCluster();
        cluster.setClusterName(IT_PREFIX + "cluster");
        cluster.setSourceType("MYSQL");
        cluster.setFeHost("127.0.0.1");
        cluster.setFePort(3306);
        cluster.setUsername("opendataworks");
        cluster.setPassword("opendataworks123");
        cluster.setIsDefault(1);
        cluster.setStatus("active");
        cluster.setAutoSync(0);
        dorisClusterMapper.insert(cluster);
        return cluster.getId();
    }

    private void preparePhysicalTablesAndSeedData(
            String dbName,
            String srcUserTable,
            String srcOrderTable,
            String srcPayTable,
            String stgUserTable,
            String stgOrderTable,
            String stgPayTable,
            String dwdOrderUserTable,
            String dwdOrderPayTable,
            String dwsUserAmountTable) {
        List<String> allTables =
                Arrays.asList(
                        srcUserTable,
                        srcOrderTable,
                        srcPayTable,
                        stgUserTable,
                        stgOrderTable,
                        stgPayTable,
                        dwdOrderUserTable,
                        dwdOrderPayTable,
                        dwsUserAmountTable);

        for (String table : allTables) {
            String fullName = dbName + "." + table;
            executeJdbc("DROP TABLE IF EXISTS " + fullName);
            if (!createdPhysicalTables.contains(fullName)) {
                createdPhysicalTables.add(fullName);
            }
        }

        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, user_name VARCHAR(128))",
                        dbName, srcUserTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (order_id BIGINT, user_id BIGINT, order_amount"
                                + " DECIMAL(18,2))",
                        dbName, srcOrderTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (pay_id BIGINT, order_id BIGINT, pay_amount"
                                + " DECIMAL(18,2))",
                        dbName, srcPayTable));

        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, user_name VARCHAR(128))",
                        dbName, stgUserTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (order_id BIGINT, user_id BIGINT, order_amount"
                                + " DECIMAL(18,2))",
                        dbName, stgOrderTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (pay_id BIGINT, order_id BIGINT, pay_amount"
                                + " DECIMAL(18,2))",
                        dbName, stgPayTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (order_id BIGINT, user_id BIGINT, user_name"
                                + " VARCHAR(128), order_amount DECIMAL(18,2))",
                        dbName, dwdOrderUserTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (order_id BIGINT, user_id BIGINT, user_name"
                            + " VARCHAR(128), order_amount DECIMAL(18,2), pay_id BIGINT, pay_amount"
                            + " DECIMAL(18,2))",
                        dbName, dwdOrderPayTable));
        executeJdbc(
                String.format(
                        "CREATE TABLE %s.%s (user_id BIGINT, total_pay DECIMAL(18,2))",
                        dbName, dwsUserAmountTable));

        executeJdbc(
                String.format(
                        "INSERT INTO %s.%s (user_id, user_name) VALUES (1,'u1'),(2,'u2'),(3,'u3')",
                        dbName, srcUserTable));
        executeJdbc(
                String.format(
                        "INSERT INTO %s.%s (order_id, user_id, order_amount) VALUES"
                                + " (101,1,100.00),(102,1,50.00),(103,2,200.00),(104,3,10.00)",
                        dbName, srcOrderTable));
        executeJdbc(
                String.format(
                        "INSERT INTO %s.%s (pay_id, order_id, pay_amount) VALUES"
                                + " (1001,101,95.00),(1002,102,50.00),(1003,103,180.00)",
                        dbName, srcPayTable));
    }

    private Long createMetadataTable(String dbName, String tableName, String layer) {
        DataTable table = new DataTable();
        table.setClusterId(effectiveClusterId);
        table.setDbName(dbName);
        table.setTableName(tableName);
        table.setLayer(layer);
        table.setTableComment("integration test table: " + tableName);
        table.setOwner("it-runtime-sync");
        table.setStatus("active");
        dataTableMapper.insert(table);
        createdTableIds.add(table.getId());
        return table.getId();
    }

    private DataTask createSqlTask(
            String taskName,
            String taskCode,
            String sql,
            List<Long> inputTableIds,
            List<Long> outputTableIds) {
        DataTask task = new DataTask();
        task.setTaskName(taskName);
        task.setTaskCode(taskCode);
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDatasourceName(effectiveDatasourceName);
        task.setDatasourceType("MYSQL");
        task.setTaskSql(sql);
        task.setTaskDesc("integration test task");
        task.setPriority(5);
        task.setTimeoutSeconds(300);
        task.setRetryTimes(1);
        task.setRetryInterval(1);
        task.setOwner("it-runtime-sync");
        DataTask created = dataTaskService.create(task, inputTableIds, outputTableIds);
        createdTaskIds.add(created.getId());
        return created;
    }

    private DataWorkflow createWorkflow(String workflowName, List<Long> taskIds, Long projectCode) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflowName);
        request.setDescription("integration test workflow");
        request.setOperator("it-runtime-sync");
        request.setProjectCode(projectCode);
        request.setTasks(
                taskIds.stream()
                        .map(
                                taskId -> {
                                    WorkflowTaskBinding binding = new WorkflowTaskBinding();
                                    binding.setTaskId(taskId);
                                    return binding;
                                })
                        .collect(Collectors.toList()));
        return workflowService.createWorkflow(request);
    }

    private Set<Long> listWorkflowInstanceIds(Long workflowCode, int limit) {
        return dolphinSchedulerService.listWorkflowInstances(workflowCode, limit).stream()
                .map(WorkflowInstanceSummary::getInstanceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private WorkflowInstanceSummary waitForNewWorkflowInstanceTerminal(
            Long workflowCode, Set<Long> beforeIds, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMillis, 30000L);
        WorkflowInstanceSummary latestSeen = null;
        while (System.currentTimeMillis() < deadline) {
            List<WorkflowInstanceSummary> instances =
                    dolphinSchedulerService.listWorkflowInstances(workflowCode, 30);
            WorkflowInstanceSummary candidate =
                    instances.stream()
                            .filter(Objects::nonNull)
                            .filter(item -> item.getInstanceId() != null)
                            .filter(
                                    item ->
                                            beforeIds == null
                                                    || !beforeIds.contains(item.getInstanceId()))
                            .max(Comparator.comparing(WorkflowInstanceSummary::getInstanceId))
                            .orElse(null);
            if (candidate != null) {
                latestSeen = candidate;
                if (isTerminalDolphinState(candidate.getState())) {
                    return candidate;
                }
            }
            sleepQuietly(3000L);
        }
        return latestSeen;
    }

    private boolean isTerminalDolphinState(String state) {
        String normalized = normalizeState(state);
        return "SUCCESS".equals(normalized)
                || "FAILURE".equals(normalized)
                || "FAILED".equals(normalized)
                || "KILL".equals(normalized)
                || "STOP".equals(normalized);
    }

    private String normalizeState(String state) {
        return StringUtils.hasText(state) ? state.trim().toUpperCase() : "";
    }

    private void updateRuntimeWorkflowName(Long projectCode, Long workflowCode, String newName) {
        // Dolphin requires workflow to be OFFLINE before editing definition metadata.
        try {
            dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
        } catch (Exception ignored) {
        }

        DolphinConfig target = dolphinConfigService.getActiveConfig();
        JsonNode exported =
                dolphinOpenApiClient.exportDefinitionByCode(target, projectCode, workflowCode);
        JsonNode definition = exported.path("processDefinition");
        if (definition.isMissingNode()) definition = exported.path("workflowDefinition");
        dolphinOpenApiClient.createOrUpdateProcessDefinition(
                target,
                projectCode,
                newName,
                definition.path("description").asText(""),
                target.getTenantCode(),
                target.getExecutionType(),
                asJsonArrayText(exported.get("processTaskRelationList")),
                asJsonArrayText(exported.get("taskDefinitionList")),
                asJsonArrayText(definition.get("locations")),
                asJsonArrayText(definition.get("globalParams")),
                workflowCode);
    }

    private Long createShellWorkflow(Long projectCode, String workflowName) {
        long taskCode = dolphinSchedulerService.nextTaskCode();
        List<Map<String, Object>> definitions = new ArrayList<>();
        definitions.add(
                dolphinSchedulerService.buildTaskDefinition(
                        taskCode,
                        1,
                        workflowName + "_shell_task",
                        "integration shell task",
                        "echo hello-runtime-sync",
                        "MEDIUM",
                        1,
                        1,
                        60,
                        "SHELL",
                        null,
                        null,
                        null,
                        null));

        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        relations.add(dolphinSchedulerService.buildRelation(0L, 0, taskCode, 1));
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();
        locations.add(dolphinSchedulerService.buildLocation(taskCode, 0, 0));

        return dolphinSchedulerService.syncWorkflow(
                0L,
                workflowName,
                "integration shell workflow",
                definitions,
                relations,
                locations,
                "[]");
    }

    private void cleanupHistoricalITData() {
        // 清理本地历史测试工作流
        List<DataWorkflow> workflows =
                dataWorkflowMapper.selectList(
                        Wrappers.<DataWorkflow>lambdaQuery()
                                .like(DataWorkflow::getWorkflowName, IT_PREFIX)
                                .orderByDesc(DataWorkflow::getId));
        for (DataWorkflow workflow : workflows) {
            try {
                workflowService.deleteWorkflow(workflow.getId());
            } catch (Exception ignored) {
            }
        }

        // 清理本地历史测试任务
        List<DataTask> tasks =
                dataTaskMapper.selectList(
                        Wrappers.<DataTask>lambdaQuery().like(DataTask::getTaskCode, IT_PREFIX));
        for (DataTask task : tasks) {
            try {
                dataLineageMapper.delete(
                        Wrappers.<DataLineage>lambdaQuery()
                                .eq(DataLineage::getTaskId, task.getId()));
                tableTaskRelationMapper.hardDeleteByTaskId(task.getId());
                workflowTaskRelationMapper.delete(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getTaskId, task.getId()));
                dataTaskMapper.deleteById(task.getId());
            } catch (Exception ignored) {
            }
        }

        // 清理本地历史测试表
        List<DataTable> tables =
                dataTableMapper.selectList(
                        Wrappers.<DataTable>lambdaQuery().like(DataTable::getTableName, IT_PREFIX));
        for (DataTable table : tables) {
            try {
                dataTableMapper.deleteById(table.getId());
            } catch (Exception ignored) {
            }
        }

        cleanupHistoricalPhysicalTables("opendataworks");

        // 清理 Dolphin 中历史测试工作流
        try {
            JsonNode page =
                    dolphinOpenApiClient.listProcessDefinitions(
                            dolphinSchedulerService.getConfig(null),
                            effectiveProjectCode,
                            1,
                            200,
                            IT_PREFIX);
            if (page != null && page.path("totalList").isArray()) {
                List<Long> workflowCodes = new ArrayList<>();
                for (JsonNode item : page.path("totalList")) {
                    if (item == null || item.isNull()) {
                        continue;
                    }
                    String name = readText(item, "name", "workflowName");
                    if (!StringUtils.hasText(name) || !name.startsWith(IT_PREFIX)) {
                        continue;
                    }
                    long code = item.path("code").asLong(0L);
                    if (code > 0) {
                        workflowCodes.add(code);
                    }
                }
                workflowCodes.stream()
                        .sorted(Comparator.reverseOrder())
                        .forEach(
                                code -> {
                                    try {
                                        dolphinSchedulerService.deleteWorkflow(code);
                                    } catch (Exception ignored) {
                                    }
                                });
            }
        } catch (Exception ignored) {
        }
    }

    private void cleanupHistoricalPhysicalTables(String dbName) {
        List<String> tableNames = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT table_name FROM information_schema.tables "
                                        + "WHERE table_schema = ? AND table_name LIKE ?")) {
            statement.setString(1, dbName);
            statement.setString(2, IT_PREFIX + "%");
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString(1);
                    if (StringUtils.hasText(tableName)) {
                        tableNames.add(tableName.trim());
                    }
                }
            }
        } catch (Exception ignored) {
        }

        for (String tableName : tableNames) {
            try {
                executeJdbc(String.format("DROP TABLE IF EXISTS %s.%s", dbName, tableName));
            } catch (Exception ignored) {
            }
        }
    }

    private String asJsonArrayText(JsonNode... candidates) {
        if (candidates == null) {
            return "[]";
        }
        for (JsonNode candidate : candidates) {
            if (candidate == null || candidate.isMissingNode() || candidate.isNull()) {
                continue;
            }
            if (candidate.isTextual()) {
                String text = candidate.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            } else {
                try {
                    return objectMapper.writeValueAsString(candidate);
                } catch (Exception ignored) {
                }
            }
        }
        return "[]";
    }

    private String readText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                String text = value.asText(null);
                if (StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private int parseIntOrDefault(String raw, int defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private void executeJdbc(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (Exception ex) {
            throw new IllegalStateException("执行 SQL 失败: " + sql, ex);
        }
    }

    private long queryForLong(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getLong(1);
            }
            return 0L;
        } catch (Exception ex) {
            throw new IllegalStateException("查询 SQL 失败: " + sql, ex);
        }
    }

    private BigDecimal queryForDecimal(String sql) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            if (rs.next()) {
                BigDecimal value = rs.getBigDecimal(1);
                return value != null ? value : BigDecimal.ZERO;
            }
            return BigDecimal.ZERO;
        } catch (Exception ex) {
            throw new IllegalStateException("查询 SQL 失败: " + sql, ex);
        }
    }

    private boolean canReachDolphin(String baseUrl) {
        HttpURLConnection connection = null;
        try {
            String probeUrl = normalizeBaseUrl(baseUrl) + "/login";
            connection = (HttpURLConnection) new URL(probeUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            int statusCode = connection.getResponseCode();
            return statusCode > 0;
        } catch (Exception ex) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean waitForDolphinReachable(String baseUrl, int retries, long sleepMillis) {
        for (int i = 0; i < retries; i++) {
            if (canReachDolphin(baseUrl)) {
                return true;
            }
            sleepQuietly(sleepMillis);
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String safeJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private RestClient webClient() {
        return webClient(effectiveBaseUrl);
    }

    private RestClient webClient(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
