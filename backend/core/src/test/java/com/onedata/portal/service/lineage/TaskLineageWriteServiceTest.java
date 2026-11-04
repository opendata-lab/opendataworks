package com.onedata.portal.service.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskLineageWriteServiceTest {

    @Mock
    private DataLineageMapper dataLineageMapper;
    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private TaskLineageWriteService service;

    @Test
    void replaceWritesBothLineageAndRelationForEachSide() {
        service.replaceTaskLineage(7L, Arrays.asList(1L, 2L), Collections.singletonList(9L));

        verify(tableTaskRelationMapper).hardDeleteByTaskId(7L);
        verify(dataLineageMapper).delete(any(LambdaQueryWrapper.class));

        ArgumentCaptor<DataLineage> lineages = ArgumentCaptor.forClass(DataLineage.class);
        verify(dataLineageMapper, times(3)).insert(lineages.capture());
        assertEquals(Arrays.asList("input", "input", "output"),
                Arrays.asList(lineages.getAllValues().get(0).getLineageType(),
                        lineages.getAllValues().get(1).getLineageType(),
                        lineages.getAllValues().get(2).getLineageType()));

        ArgumentCaptor<TableTaskRelation> relations = ArgumentCaptor.forClass(TableTaskRelation.class);
        verify(tableTaskRelationMapper, times(3)).insert(relations.capture());
        assertEquals(Arrays.asList("read", "read", "write"),
                Arrays.asList(relations.getAllValues().get(0).getRelationType(),
                        relations.getAllValues().get(1).getRelationType(),
                        relations.getAllValues().get(2).getRelationType()));
    }

    @Test
    void replaceDeduplicatesRepeatedTableIds() {
        service.replaceTaskLineage(7L, Arrays.asList(1L, 1L, null, 2L), Collections.emptyList());

        verify(dataLineageMapper, times(2)).insert(any(DataLineage.class));
        verify(tableTaskRelationMapper, times(2)).insert(any(TableTaskRelation.class));
    }

    @Test
    void refreshDeduplicatesWorkflowIdsSoOneDefinitionIsRewrittenOnce() {
        // 批量删表时同一个工作流可能被多张表牵连，不去重会把同一份 definitionJson 重写多次。
        service.refreshWorkflowDefinitions(Arrays.asList(10L, 10L, 11L, null, 10L), "tester");

        verify(workflowService, times(1)).refreshTaskRelations(10L);
        verify(workflowService, times(1)).refreshTaskRelations(11L);
        verify(workflowService, times(1)).normalizeAndPersistMetadata(10L, "tester");
        verify(workflowService, times(1)).normalizeAndPersistMetadata(11L, "tester");
    }

    @Test
    void refreshDoesNothingForEmptyInput() {
        service.refreshWorkflowDefinitions(Collections.emptyList(), "tester");

        verify(workflowService, never()).refreshTaskRelations(any());
        verify(workflowService, never()).normalizeAndPersistMetadata(any(), any());
    }

    @Test
    void replaceAndRefreshAlsoRebuildsDefinition() {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setTaskId(7L);
        relation.setWorkflowId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        service.replaceTaskLineageAndRefresh(7L, Collections.singletonList(1L),
                Collections.singletonList(9L), "tester");

        verify(tableTaskRelationMapper).hardDeleteByTaskId(7L);
        verify(workflowService).refreshTaskRelations(10L);
        verify(workflowService).normalizeAndPersistMetadata(eq(10L), eq("tester"));
    }

    @Test
    void findWorkflowIdsReturnsDistinctIds() {
        WorkflowTaskRelation first = new WorkflowTaskRelation();
        first.setTaskId(1L);
        first.setWorkflowId(10L);
        WorkflowTaskRelation second = new WorkflowTaskRelation();
        second.setTaskId(2L);
        second.setWorkflowId(10L);
        WorkflowTaskRelation third = new WorkflowTaskRelation();
        third.setTaskId(3L);
        third.setWorkflowId(11L);
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Arrays.asList(first, second, third));

        Set<Long> workflowIds = service.findWorkflowIdsByTaskIds(Arrays.asList(1L, 2L, 3L));

        assertEquals(2, workflowIds.size());
    }

    @Test
    void findTaskIdsByTableIdReturnsDistinctTasks() {
        TableTaskRelation read = new TableTaskRelation();
        read.setTableId(5L);
        read.setTaskId(1L);
        TableTaskRelation write = new TableTaskRelation();
        write.setTableId(5L);
        write.setTaskId(1L);
        List<TableTaskRelation> rows = Arrays.asList(read, write);
        when(tableTaskRelationMapper.selectList(any())).thenReturn(rows);

        Set<Long> taskIds = service.findTaskIdsByTableId(5L);

        assertEquals(Collections.singleton(1L), taskIds);
    }
}
