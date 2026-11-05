package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowQueryServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowPublishRecord.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
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
    private WorkflowInstanceCacheService workflowInstanceCacheService;
    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    private WorkflowQueryService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowQueryService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                workflowPublishRecordMapper,
                workflowVersionService,
                workflowVersionMapper,
                workflowInstanceCacheService,
                dolphinSchedulerService);
    }

    @Test
    void listShouldAttachLatestCacheAndCurrentVersion() {
        WorkflowQueryRequest request = new WorkflowQueryRequest();
        request.setKeyword("daily");
        request.setStatus("online");

        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("daily_wf");
        workflow.setCurrentVersionId(7L);

        Page<DataWorkflow> page = new Page<>(1, 20);
        page.setRecords(Collections.singletonList(workflow));
        when(dataWorkflowMapper.selectPage(any(Page.class), any())).thenReturn(page);

        WorkflowInstanceCache latest = new WorkflowInstanceCache();
        latest.setInstanceId(77L);
        latest.setState("SUCCESS");
        latest.setStartTime(new Date(1710000000000L));
        latest.setEndTime(new Date(1710003600000L));
        when(workflowInstanceCacheService.findLatest(1L)).thenReturn(latest);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(7L);
        version.setVersionNo(4);
        when(workflowVersionMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(version));

        Page<DataWorkflow> actual = service.list(request);

        assertSame(page, actual);
        DataWorkflow row = actual.getRecords().get(0);
        assertEquals(77L, row.getLatestInstanceId());
        assertEquals("SUCCESS", row.getLatestInstanceState());
        assertNotNull(row.getLatestInstanceStartTime());
        assertNotNull(row.getLatestInstanceEndTime());
        assertEquals(4, row.getCurrentVersionNo());
        verify(workflowInstanceCacheService).findLatest(1L);
        verify(dolphinSchedulerService, never()).listWorkflowInstances(any(), any(), anyInt());
    }

    @Test
    void getDetailShouldLoadRelationsVersionsPublishRecordsAndRealtimeInstances() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(2L);
        workflow.setWorkflowName("published_wf");
        workflow.setDolphinConfigId(3L);
        workflow.setWorkflowCode(1001L);
        workflow.setCurrentVersionId(9L);
        when(dataWorkflowMapper.selectById(2L)).thenReturn(workflow);

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(2L);
        relation.setTaskId(11L);
        List<WorkflowTaskRelation> relations = Collections.singletonList(relation);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(relations);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(9L);
        version.setVersionNo(5);
        List<WorkflowVersion> versions = Collections.singletonList(version);
        when(workflowVersionService.listByWorkflow(2L)).thenReturn(versions);

        WorkflowPublishRecord publishRecord = new WorkflowPublishRecord();
        publishRecord.setWorkflowId(2L);
        List<WorkflowPublishRecord> publishRecords = Collections.singletonList(publishRecord);
        when(workflowPublishRecordMapper.selectList(any())).thenReturn(publishRecords);

        WorkflowInstanceSummary summary = WorkflowInstanceSummary.builder()
                .instanceId(88L)
                .state("RUNNING")
                .commandType("START_PROCESS")
                .durationMs(1200L)
                .startTime("2026-06-17 10:00:00")
                .endTime("2026-06-17 10:02:00")
                .rawJson("{\"id\":88}")
                .build();
        List<WorkflowInstanceSummary> summaries = Collections.singletonList(summary);
        when(dolphinSchedulerService.listWorkflowInstances(3L, 1001L, 10)).thenReturn(summaries);

        WorkflowDetailResponse detail = service.getDetail(2L);

        assertSame(workflow, detail.getWorkflow());
        assertSame(relations, detail.getTaskRelations());
        assertSame(versions, detail.getVersions());
        assertSame(publishRecords, detail.getPublishRecords());
        assertEquals(5, workflow.getCurrentVersionNo());
        assertEquals(1, detail.getRecentInstances().size());
        assertEquals(88L, detail.getRecentInstances().get(0).getInstanceId());
        assertEquals("RUNNING", detail.getRecentInstances().get(0).getState());
        verify(workflowInstanceCacheService).replaceCache(eq(workflow), eq(summaries));
        verify(workflowInstanceCacheService, never()).listRecent(eq(2L), anyInt());
    }
}
