package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceMetadataPersistenceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
    }

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private WorkflowVersionService workflowVersionService;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private WorkflowTopologyService workflowTopologyService;

    @Mock
    private DolphinConfigService dolphinConfigService;

    @Mock
    private WorkflowQueryService workflowQueryService;

    @Mock
    private WorkflowExecutionService workflowExecutionService;

    private WorkflowCommandService workflowCommandService;

    private WorkflowTaskRelationService workflowTaskRelationService;
    private WorkflowDefinitionAssembler workflowDefinitionAssembler;

    private ObjectMapper objectMapper;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        workflowTaskRelationService = new WorkflowTaskRelationService(
                workflowTaskRelationMapper,
                tableTaskRelationMapper,
                dataTaskMapper,
                workflowTopologyService,
                objectMapper);
        workflowDefinitionAssembler = new WorkflowDefinitionAssembler(
                objectMapper,
                dolphinSchedulerService,
                dataTaskMapper,
                tableTaskRelationMapper,
                workflowTaskRelationService);
        workflowCommandService = new WorkflowCommandService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                dolphinSchedulerService,
                dataTaskMapper,
                new DataTaskIdentityArchiver(dataTaskMapper),
                dataLineageMapper,
                tableTaskRelationMapper,
                workflowVersionService,
                workflowVersionMapper,
                objectMapper,
                workflowTopologyService,
                dolphinConfigService,
                workflowTaskRelationService,
                workflowDefinitionAssembler);
        service = new WorkflowService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                workflowTopologyService,
                workflowQueryService,
                workflowTaskRelationService,
                workflowExecutionService,
                workflowCommandService,
                workflowDefinitionAssembler);
    }

    @Test
    void normalizeAndPersistMetadataShouldResolveMissingIdsFromCatalog() throws Exception {
        DataWorkflow workflow = baseWorkflow("{}");
        DataTask task = baseTask();
        task.setTaskGroupName(null);

        mockNormalizeContext(workflow, task);
        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(999L);
        datasourceOption.setName("ds_main");
        datasourceOption.setType("MYSQL");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));

        DolphinTaskGroupOption taskGroupOption = new DolphinTaskGroupOption();
        taskGroupOption.setId(88);
        taskGroupOption.setName("tg_default");
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(taskGroupOption));

        service.normalizeAndPersistMetadata(1L, "tester");

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        DataWorkflow updated = workflowCaptor.getValue();
        assertNotNull(updated);

        JsonNode root = objectMapper.readTree(updated.getDefinitionJson());
        assertFalse(root.path("processDefinition").has("releaseState"));
        JsonNode firstTask = firstTaskNode(updated.getDefinitionJson());
        assertEquals("YES", firstTask.path("flag").asText());
        assertEquals("tg_default", firstTask.path("taskGroupName").asText());
        assertEquals(88, firstTask.path("taskGroupId").asInt());
        JsonNode params = firstTask.path("taskParams");
        assertEquals(999L, params.path("datasourceId").asLong());
        assertEquals(999L, params.path("datasource").asLong());
    }

    @Test
    void normalizeAndPersistMetadataShouldOverwriteExistingDatasourceTypeFromCatalog() throws Exception {
        String seedDefinition = "{\"taskDefinitionList\":[{\"taskCode\":1001,\"taskGroupId\":88,"
                + "\"taskParams\":{\"datasourceId\":999,\"datasource\":999,\"datasourceName\":\"ds_main\","
                + "\"datasourceType\":\"DORIS\",\"type\":\"DORIS\"}}]}";
        DataWorkflow workflow = baseWorkflow(seedDefinition);
        DataTask task = baseTask();
        task.setDatasourceType("DORIS");

        mockNormalizeContext(workflow, task);

        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(999L);
        datasourceOption.setName("ds_main");
        datasourceOption.setType("OCEANBASE");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));

        service.normalizeAndPersistMetadata(1L, "tester");

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        DataWorkflow updated = workflowCaptor.getValue();
        JsonNode firstTask = firstTaskNode(updated.getDefinitionJson());
        assertEquals(88, firstTask.path("taskGroupId").asInt());
        JsonNode params = firstTask.path("taskParams");
        assertEquals(999L, params.path("datasourceId").asLong());
        assertEquals(999L, params.path("datasource").asLong());
        assertEquals("OCEANBASE", params.path("datasourceType").asText());
        assertEquals("OCEANBASE", params.path("type").asText());

        ArgumentCaptor<DataTask> taskCaptor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskMapper).updateById(taskCaptor.capture());
        assertEquals("OCEANBASE", taskCaptor.getValue().getDatasourceType());
        verify(dolphinSchedulerService, never()).listTaskGroups(any());
    }

    @Test
    void normalizeAndPersistMetadataShouldKeepMissingIdsWhenCatalogHasNoMatch() throws Exception {
        DataWorkflow workflow = baseWorkflow("{}");
        DataTask task = baseTask();

        mockNormalizeContext(workflow, task);
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.emptyList());

        service.normalizeAndPersistMetadata(1L, "tester");

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        DataWorkflow updated = workflowCaptor.getValue();
        JsonNode firstTask = firstTaskNode(updated.getDefinitionJson());
        JsonNode params = firstTask.path("taskParams");
        assertFalse(firstTask.has("taskGroupId"));
        assertFalse(params.has("datasourceId"));
        assertFalse(params.has("datasource"));
    }

    @Test
    void createWorkflowShouldKeepProjectCodeNullWhenRequestProjectCodeMissing() {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName("wf_no_project_code");
        request.setDescription("desc");
        request.setOperator("tester");
        request.setTasks(Collections.emptyList());
        request.setProjectCode(null);

        WorkflowTopologyResult emptyTopology = WorkflowTopologyResult.builder()
                .entryTaskIds(Collections.emptySet())
                .exitTaskIds(Collections.emptySet())
                .build();
        when(workflowTopologyService.buildTopology(anyList())).thenReturn(emptyTopology);

        when(dataWorkflowMapper.insert(any(DataWorkflow.class))).thenAnswer(invocation -> {
            DataWorkflow workflow = invocation.getArgument(0);
            workflow.setId(11L);
            return 1;
        });
        when(dataWorkflowMapper.updateById(any(DataWorkflow.class))).thenReturn(1);

        WorkflowVersion createdVersion = new WorkflowVersion();
        createdVersion.setId(901L);
        when(workflowVersionService.createVersion(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(createdVersion);

        DataWorkflow created = service.createWorkflow(request);

        assertNotNull(created);
        assertEquals(11L, created.getId());
        assertEquals(null, created.getProjectCode());
        verify(dolphinSchedulerService, never()).getProjectCode();
    }

    @Test
    void createWorkflowShouldPersistResolvedIdsIntoVersionSnapshot() throws Exception {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName("wf_snapshot_meta");
        request.setDescription("desc");
        request.setOperator("tester");
        request.setProjectCode(77L);
        request.setTaskGroupName("tg_default");

        WorkflowTaskBinding binding = new WorkflowTaskBinding();
        binding.setTaskId(10L);
        request.setTasks(Collections.singletonList(binding));

        WorkflowTopologyResult topology = WorkflowTopologyResult.builder()
                .entryTaskIds(Collections.singleton(10L))
                .exitTaskIds(Collections.singleton(10L))
                .build();
        when(workflowTopologyService.buildTopology(anyList())).thenReturn(topology);

        when(dataWorkflowMapper.insert(any(DataWorkflow.class))).thenAnswer(invocation -> {
            DataWorkflow workflow = invocation.getArgument(0);
            workflow.setId(11L);
            return 1;
        });
        when(dataWorkflowMapper.updateById(any(DataWorkflow.class))).thenReturn(1);

        DataTask task = baseTask();
        task.setTaskGroupName(null);
        when(dataTaskMapper.selectById(10L)).thenReturn(task);
        when(dataTaskMapper.selectBatchIds(anyList())).thenReturn(Collections.singletonList(task));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());

        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(999L);
        datasourceOption.setName("ds_main");
        datasourceOption.setType("MYSQL");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));

        DolphinTaskGroupOption taskGroupOption = new DolphinTaskGroupOption();
        taskGroupOption.setId(88);
        taskGroupOption.setName("tg_default");
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(taskGroupOption));

        WorkflowVersion createdVersion = new WorkflowVersion();
        createdVersion.setId(901L);
        when(workflowVersionService.createVersion(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(createdVersion);

        service.createWorkflow(request);

        ArgumentCaptor<String> snapshotCaptor = ArgumentCaptor.forClass(String.class);
        verify(workflowVersionService).createVersion(any(), snapshotCaptor.capture(), any(), any(), any(), any(), any());
        JsonNode root = objectMapper.readTree(snapshotCaptor.getValue());
        assertFalse(root.path("processDefinition").has("releaseState"));
        JsonNode firstTask = firstTaskNode(snapshotCaptor.getValue());
        assertEquals("YES", firstTask.path("flag").asText());
        assertEquals(88, firstTask.path("taskGroupId").asInt());
        JsonNode params = firstTask.path("taskParams");
        assertEquals(999L, params.path("datasourceId").asLong());
        assertEquals(999L, params.path("datasource").asLong());
    }

    @Test
    void buildDefinitionJsonForExportShouldStripWorkflowReleaseStateButKeepScheduleState() throws Exception {
        String persistedDefinitionJson = "{"
                + "\"processDefinition\":{\"workflowCode\":1001,\"name\":\"wf_meta\",\"releaseState\":\"ONLINE\"},"
                + "\"schedule\":{\"releaseState\":\"OFFLINE\"},"
                + "\"taskDefinitionList\":[]"
                + "}";
        DataWorkflow workflow = baseWorkflow(persistedDefinitionJson);
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        String exported = service.buildDefinitionJsonForExport(1L);

        JsonNode root = objectMapper.readTree(exported);
        assertFalse(root.path("processDefinition").has("releaseState"));
        assertEquals("OFFLINE", root.path("schedule").path("releaseState").asText());
    }

    @Test
    void refreshTaskRelationsShouldHardDeleteBeforeReinsertingBindings() {
        WorkflowTaskRelation existing = new WorkflowTaskRelation();
        existing.setWorkflowId(1L);
        existing.setTaskId(10L);
        existing.setIsEntry(true);
        existing.setIsExit(true);
        existing.setNodeAttrs("{\"x\":220,\"y\":120}");
        existing.setVersionId(101L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(existing));

        DataTask task = baseTask();
        when(dataTaskMapper.selectById(10L)).thenReturn(task);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(existing);
        when(tableTaskRelationMapper.countUpstreamTasks(10L)).thenReturn(0);
        when(tableTaskRelationMapper.countDownstreamTasks(10L)).thenReturn(0);

        WorkflowTopologyResult topology = WorkflowTopologyResult.builder()
                .entryTaskIds(Collections.singleton(10L))
                .exitTaskIds(Collections.singleton(10L))
                .build();
        when(workflowTopologyService.buildTopology(anyList())).thenReturn(topology);

        service.refreshTaskRelations(1L);

        verify(workflowTaskRelationMapper).hardDeleteByWorkflowId(1L);
        verify(workflowTaskRelationMapper).insert(any(WorkflowTaskRelation.class));
        verify(workflowTaskRelationMapper, never()).delete(any());
    }

    @Test
    void deleteWorkflowShouldSoftDeleteWorkflowOnlyWhenCascadeDisabled() {
        DataWorkflow workflow = baseWorkflow("{}");
        workflow.setWorkflowCode(12345L);
        workflow.setDolphinScheduleId(6789L);
        workflow.setDolphinConfigId(9L);
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relation1 = new WorkflowTaskRelation();
        relation1.setWorkflowId(1L);
        relation1.setTaskId(10L);
        WorkflowTaskRelation relation2 = new WorkflowTaskRelation();
        relation2.setWorkflowId(1L);
        relation2.setTaskId(20L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relation1, relation2));
        when(dolphinSchedulerService.checkWorkflowExists(9L, 12345L)).thenReturn(true);

        service.deleteWorkflow(1L, false);

        verify(dolphinSchedulerService).offlineWorkflowSchedule(9L, 6789L);
        verify(dolphinSchedulerService).setWorkflowReleaseState(9L, 12345L, "OFFLINE");
        verify(dolphinSchedulerService).deleteWorkflow(9L, 12345L);
        verify(workflowTaskRelationMapper).hardDeleteByWorkflowId(1L);
        verify(dataWorkflowMapper).deleteById(1L);
        verify(dataLineageMapper, never()).delete(any());
        verify(dataTaskMapper, never()).deleteBatchIds(anyList());
        verify(tableTaskRelationMapper, never()).delete(any());
        verify(tableTaskRelationMapper, never()).hardDeleteByTaskId(any());
    }

    @Test
    void deleteWorkflowShouldCascadeSoftDeleteTasksWhenEnabled() {
        DataWorkflow workflow = baseWorkflow("{}");
        workflow.setWorkflowCode(null);
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relation1 = new WorkflowTaskRelation();
        relation1.setWorkflowId(1L);
        relation1.setTaskId(10L);
        WorkflowTaskRelation relation2 = new WorkflowTaskRelation();
        relation2.setWorkflowId(1L);
        relation2.setTaskId(20L);
        WorkflowTaskRelation relation3 = new WorkflowTaskRelation();
        relation3.setWorkflowId(1L);
        relation3.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relation1, relation2, relation3));

        service.deleteWorkflow(1L, true);

        verify(dataLineageMapper).delete(any());
        verify(tableTaskRelationMapper).delete(any());
        verify(tableTaskRelationMapper, never()).hardDeleteByTaskId(any());
        verify(dataTaskMapper).deleteBatchIds(argThat(ids -> {
            if (!(ids instanceof List)) {
                return false;
            }
            List<?> values = (List<?>) ids;
            return values.size() == 2 && values.contains(10L) && values.contains(20L);
        }));
        verify(workflowTaskRelationMapper).hardDeleteByWorkflowId(1L);
        verify(dataWorkflowMapper).deleteById(1L);
    }

    private void mockNormalizeContext(DataWorkflow workflow, DataTask task) {
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(1L);
        relation.setTaskId(10L);
        relation.setIsEntry(true);
        relation.setIsExit(true);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        WorkflowTopologyResult topology = WorkflowTopologyResult.builder()
                .entryTaskIds(Collections.singleton(10L))
                .exitTaskIds(Collections.singleton(10L))
                .build();
        when(workflowTopologyService.buildTopology(anyList())).thenReturn(topology);

        when(dataTaskMapper.selectBatchIds(anyList())).thenReturn(Collections.singletonList(task));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private DataWorkflow baseWorkflow(String definitionJson) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("wf_meta");
        workflow.setTaskGroupName("tg_default");
        workflow.setDefinitionJson(definitionJson);
        return workflow;
    }

    private DataTask baseTask() {
        DataTask task = new DataTask();
        task.setId(10L);
        task.setTaskName("task_sql");
        task.setTaskCode("task_sql");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDolphinTaskCode(1001L);
        task.setDolphinTaskVersion(1);
        task.setDatasourceName("ds_main");
        task.setDatasourceType("MYSQL");
        task.setTaskSql("select 1");
        task.setPriority(5);
        task.setRetryTimes(1);
        task.setRetryInterval(1);
        task.setTimeoutSeconds(60);
        task.setDolphinFlag("YES");
        return task;
    }

    private JsonNode firstTaskNode(String definitionJson) throws Exception {
        JsonNode root = objectMapper.readTree(definitionJson);
        JsonNode taskList = root.path("taskDefinitionList");
        return taskList.get(0);
    }
}
