package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流级联删除任务时的唯一键归档回归测试。
 *
 * <p>级联软删除与单任务删除是两条独立路径，必须同样在软删除前归档
 * {@code task_name} / {@code task_code}，否则同名任务再次级联删除会触发 duplicate key。</p>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowCommandServiceCascadeDeleteTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataLineage.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
    }

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataTaskIdentityArchiver dataTaskIdentityArchiver;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private WorkflowVersionService workflowVersionService;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private WorkflowTopologyService workflowTopologyService;

    @Mock
    private DolphinConfigService dolphinConfigService;

    @Mock
    private WorkflowTaskRelationService workflowTaskRelationService;

    @Mock
    private WorkflowDefinitionAssembler workflowDefinitionAssembler;

    @InjectMocks
    private WorkflowCommandService workflowCommandService;

    @Test
    void cascadeDeleteShouldArchiveTaskIdentityBeforeSoftDelete() {
        givenWorkflowWithTasks(901L, 601L, 602L);

        workflowCommandService.deleteWorkflow(901L, true);

        List<Long> taskIds = Arrays.asList(601L, 602L);
        InOrder inOrder = inOrder(dataTaskIdentityArchiver, dataTaskMapper);
        inOrder.verify(dataTaskIdentityArchiver).archiveByIds(taskIds);
        inOrder.verify(dataTaskMapper).deleteBatchIds(taskIds);
    }

    @Test
    void nonCascadeDeleteShouldNotTouchTaskIdentity() {
        givenWorkflowWithTasks(902L, 603L);

        workflowCommandService.deleteWorkflow(902L, false);

        verify(dataTaskIdentityArchiver, never()).archiveByIds(anyCollection());
        verify(dataTaskMapper, never()).deleteBatchIds(any());
    }

    private void givenWorkflowWithTasks(Long workflowId, Long... taskIds) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(workflowId);
        // workflowCode 为空时跳过 Dolphin 同步删除，聚焦本地级联删除行为
        workflow.setWorkflowCode(null);
        when(dataWorkflowMapper.selectById(workflowId)).thenReturn(workflow);

        List<WorkflowTaskRelation> relations = Arrays.stream(taskIds)
                .map(taskId -> {
                    WorkflowTaskRelation relation = new WorkflowTaskRelation();
                    relation.setWorkflowId(workflowId);
                    relation.setTaskId(taskId);
                    return relation;
                })
                .collect(Collectors.toList());
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(relations);
    }
}
