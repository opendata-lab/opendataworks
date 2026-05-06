package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowInstanceCacheMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowSchedulerEngineSwitchTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
    }

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Mock
    private WorkflowPublishRecordMapper workflowPublishRecordMapper;
    @Mock
    private WorkflowVersionService workflowVersionService;
    @Mock
    private WorkflowVersionMapper workflowVersionMapper;
    @Mock
    private WorkflowInstanceCacheMapper workflowInstanceCacheMapper;
    @Mock
    private DolphinSchedulerService dolphinSchedulerService;
    @Mock
    private DataTaskMapper dataTaskMapper;
    @Mock
    private DataLineageMapper dataLineageMapper;
    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Mock
    private TaskExecutionLogMapper taskExecutionLogMapper;
    @Mock
    private WorkflowTopologyService workflowTopologyService;
    @Mock
    private DolphinConfigService dolphinConfigService;

    private WorkflowService workflowService;

    @BeforeEach
    void setUp() {
        WorkflowInstanceCacheService cacheService = new WorkflowInstanceCacheService(workflowInstanceCacheMapper);
        workflowService = new WorkflowService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                workflowPublishRecordMapper,
                workflowVersionService,
                workflowVersionMapper,
                cacheService,
                new ObjectMapper(),
                dolphinSchedulerService,
                dataTaskMapper,
                dataLineageMapper,
                tableTaskRelationMapper,
                taskExecutionLogMapper,
                workflowTopologyService,
                dolphinConfigService);
    }

    @Test
    void switchSchedulerEngineShouldResetRuntimeBindingAndKeepPlatformDefinition() throws Exception {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setDolphinConfigId(1L);
        workflow.setWorkflowCode(5001L);
        workflow.setProjectCode(11L);
        workflow.setWorkflowName("wf_order");
        workflow.setStatus("online");
        workflow.setPublishStatus("published");
        workflow.setLastPublishedVersionId(88L);
        workflow.setCurrentVersionId(99L);
        workflow.setDefinitionJson("{\"processDefinition\":{\"code\":5001,\"workflowCode\":5001,\"projectCode\":11,"
                + "\"name\":\"wf_order\",\"taskGroupName\":\"tg_default\"},"
                + "\"taskDefinitionList\":[{\"taskCode\":1001,\"taskGroupId\":12,"
                + "\"taskGroupName\":\"tg_default\",\"taskParams\":{}}],"
                + "\"schedule\":{\"id\":900,\"scheduleId\":900,\"dolphinScheduleId\":900,"
                + "\"crontab\":\"0 0 1 * * ? *\",\"scheduleState\":\"ONLINE\"},"
                + "\"xPlatformWorkflowMeta\":{\"workflowCode\":5001,\"projectCode\":11}}");
        workflow.setDolphinScheduleId(900L);
        workflow.setScheduleState("ONLINE");
        workflow.setScheduleCron("0 0 1 * * ? *");

        DolphinConfig target = new DolphinConfig();
        target.setId(2L);
        target.setConfigName("new-dolphin");
        target.setIsActive(true);
        target.setProjectName("new_project");

        WorkflowSchedulerEngineRequest request = new WorkflowSchedulerEngineRequest();
        request.setDolphinConfigId(2L);
        request.setOperator("tester");

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(dolphinConfigService.getEnabledConfig(2L)).thenReturn(target);
        when(dolphinSchedulerService.testConnection(2L)).thenReturn(true);
        when(dolphinSchedulerService.getProjectCode(2L, true)).thenReturn(22L);
        DolphinTaskGroupOption targetGroup = new DolphinTaskGroupOption();
        targetGroup.setId(71);
        targetGroup.setName("tg_default");
        when(dolphinSchedulerService.listTaskGroups(null, 2L))
                .thenReturn(Collections.singletonList(targetGroup));

        DataWorkflow result = workflowService.switchSchedulerEngine(1L, request);

        assertEquals(Long.valueOf(2L), result.getDolphinConfigId());
        assertNull(result.getWorkflowCode());
        assertEquals(Long.valueOf(22L), result.getProjectCode());
        assertNull(result.getDolphinScheduleId());
        assertEquals("OFFLINE", result.getScheduleState());
        assertEquals("offline", result.getStatus());
        assertEquals("never", result.getPublishStatus());
        assertNull(result.getLastPublishedVersionId());
        assertEquals(Long.valueOf(99L), result.getCurrentVersionId());
        assertEquals("0 0 1 * * ? *", result.getScheduleCron());

        ArgumentCaptor<LambdaUpdateWrapper<DataWorkflow>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(dataWorkflowMapper).update(eq(null), wrapperCaptor.capture());
        verify(dataWorkflowMapper, never()).updateById(org.mockito.ArgumentMatchers.any());

        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("workflow_code"), sqlSet);
        assertTrue(sqlSet.contains("dolphin_schedule_id"), sqlSet);
        assertTrue(sqlSet.contains("last_published_version_id"), sqlSet);

        JsonNode root = new ObjectMapper().readTree(result.getDefinitionJson());
        JsonNode processDefinition = root.path("processDefinition");
        assertFalse(processDefinition.has("code"));
        assertFalse(processDefinition.has("workflowCode"));
        assertFalse(processDefinition.has("processDefinitionCode"));
        assertEquals(22L, processDefinition.path("projectCode").asLong());
        assertFalse(root.path("xPlatformWorkflowMeta").has("workflowCode"));
        assertEquals(22L, root.path("xPlatformWorkflowMeta").path("projectCode").asLong());
        assertFalse(root.path("schedule").has("id"));
        assertFalse(root.path("schedule").has("scheduleId"));
        assertFalse(root.path("schedule").has("dolphinScheduleId"));
        assertEquals("OFFLINE", root.path("schedule").path("scheduleState").asText());
        assertEquals(71, root.path("taskDefinitionList").get(0).path("taskGroupId").asInt());
    }
}
