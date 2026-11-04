package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowTaskRelationServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
    }

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Mock
    private DataTaskMapper dataTaskMapper;
    @Mock
    private WorkflowTopologyService workflowTopologyService;

    private WorkflowTaskRelationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowTaskRelationService(
                workflowTaskRelationMapper,
                tableTaskRelationMapper,
                dataTaskMapper,
                workflowTopologyService,
                new ObjectMapper());
    }

    @Test
    void buildTaskBindingsShouldPreserveAttrsAndSkipInvalidJson() {
        WorkflowTaskRelation first = relation(1L, 10L, true, false, 101L, "{\"x\":220,\"y\":120}");
        WorkflowTaskRelation second = relation(1L, 20L, false, true, 101L, "{not-json");

        List<WorkflowTaskBinding> bindings = service.buildTaskBindingsFromRelations(Arrays.asList(first, second));

        assertEquals(2, bindings.size());
        assertEquals(10L, bindings.get(0).getTaskId());
        assertEquals(true, bindings.get(0).getEntry());
        assertEquals(220, bindings.get(0).getNodeAttrs().get("x"));
        assertEquals(20L, bindings.get(1).getTaskId());
        assertEquals(true, bindings.get(1).getExit());
        assertNull(bindings.get(1).getNodeAttrs());
    }

    @Test
    void collectTaskIdsShouldKeepFirstSeenOrderAndDeduplicate() {
        WorkflowTaskBinding first = binding(10L);
        WorkflowTaskBinding duplicate = binding(10L);
        WorkflowTaskBinding second = binding(20L);

        List<Long> taskIds = service.collectTaskIds(Arrays.asList(first, duplicate, second, null));

        assertEquals(Arrays.asList(10L, 20L), taskIds);
    }

    @Test
    void refreshTaskRelationsShouldHardDeleteBeforeReinsertingBindings() {
        WorkflowTaskRelation existing = relation(1L, 10L, true, true, 101L, "{\"x\":220}");
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(existing));

        WorkflowTopologyResult topology = WorkflowTopologyResult.builder()
                .entryTaskIds(Collections.singleton(10L))
                .exitTaskIds(Collections.singleton(10L))
                .build();
        when(workflowTopologyService.buildTopology(anyList())).thenReturn(topology);
        when(dataTaskMapper.selectById(10L)).thenReturn(new DataTask());
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(existing);
        when(tableTaskRelationMapper.countUpstreamTasks(10L)).thenReturn(2);
        when(tableTaskRelationMapper.countDownstreamTasks(10L)).thenReturn(3);

        service.refreshTaskRelations(1L);

        InOrder inOrder = inOrder(workflowTaskRelationMapper);
        inOrder.verify(workflowTaskRelationMapper).selectList(any());
        inOrder.verify(workflowTaskRelationMapper).hardDeleteByWorkflowId(1L);
        ArgumentCaptor<WorkflowTaskRelation> captor = ArgumentCaptor.forClass(WorkflowTaskRelation.class);
        inOrder.verify(workflowTaskRelationMapper).insert(captor.capture());

        WorkflowTaskRelation inserted = captor.getValue();
        assertEquals(1L, inserted.getWorkflowId());
        assertEquals(10L, inserted.getTaskId());
        assertTrue(inserted.getIsEntry());
        assertTrue(inserted.getIsExit());
        assertEquals(101L, inserted.getVersionId());
        assertEquals(Integer.valueOf(2), inserted.getUpstreamTaskCount());
        assertEquals(Integer.valueOf(3), inserted.getDownstreamTaskCount());
        verify(workflowTopologyService).buildTopology(Collections.singletonList(10L));
    }

    private WorkflowTaskRelation relation(Long workflowId,
            Long taskId,
            boolean entry,
            boolean exit,
            Long versionId,
            String nodeAttrs) {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(workflowId);
        relation.setTaskId(taskId);
        relation.setIsEntry(entry);
        relation.setIsExit(exit);
        relation.setVersionId(versionId);
        relation.setNodeAttrs(nodeAttrs);
        return relation;
    }

    private WorkflowTaskBinding binding(Long taskId) {
        WorkflowTaskBinding binding = new WorkflowTaskBinding();
        binding.setTaskId(taskId);
        return binding;
    }
}
