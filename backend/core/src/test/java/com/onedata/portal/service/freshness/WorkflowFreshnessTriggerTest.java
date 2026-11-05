package com.onedata.portal.service.freshness;

import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工作流触发：只对写关系表触发、开关关闭时不触发、异常不外抛。
 */
class WorkflowFreshnessTriggerTest {

    private final FreshnessCheckProperties properties = new FreshnessCheckProperties();
    private final TableTaskRelationMapper relationMapper = mock(TableTaskRelationMapper.class);
    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);
    private final FreshnessCheckService checkService = mock(FreshnessCheckService.class);

    private final WorkflowFreshnessTrigger trigger = new WorkflowFreshnessTrigger(
        properties, relationMapper, dataTableMapper, checkService);

    private DataTable activeTable(long id) {
        DataTable t = new DataTable();
        t.setId(id);
        t.setStatus("active");
        return t;
    }

    @Test
    void triggersCheckForAssociatedTables() {
        when(relationMapper.selectAllTableIdsByWorkflow(eq(7L))).thenReturn(Arrays.asList(1L, 2L));
        when(dataTableMapper.selectBatchIds(any())).thenReturn(Arrays.asList(activeTable(1), activeTable(2)));
        when(checkService.checkBatch(any(), eq("workflow"), eq("system"), any()))
            .thenReturn(Collections.emptyList());

        trigger.onWorkflowSucceeded(7L, 42L);

        verify(checkService).checkBatch(any(), eq("workflow"), eq("system"), eq(42L));
    }

    @Test
    void noAssociatedTables_noCheck() {
        when(relationMapper.selectAllTableIdsByWorkflow(any())).thenReturn(Collections.emptyList());
        trigger.onWorkflowSucceeded(7L, 42L);
        verify(checkService, never()).checkBatch(any(), any(), any(), any());
    }

    @Test
    void disabled_noCheck() {
        properties.setEnabled(false);
        trigger.onWorkflowSucceeded(7L, 42L);
        verify(relationMapper, never()).selectAllTableIdsByWorkflow(any());
        verify(checkService, never()).checkBatch(any(), any(), any(), any());
    }

    @Test
    void exceptionIsSwallowed() {
        when(relationMapper.selectAllTableIdsByWorkflow(any()))
            .thenThrow(new RuntimeException("db down"));
        // 不应抛出
        trigger.onWorkflowSucceeded(7L, 42L);
        verify(checkService, never()).checkBatch(any(), any(), any(), any());
    }
}
