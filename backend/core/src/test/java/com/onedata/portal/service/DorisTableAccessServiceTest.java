package com.onedata.portal.service;

import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.dto.DashboardTableAccessAggregate;
import com.onedata.portal.dto.DashboardTableAccessSummary;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisAuditAccessCheckpointMapper;
import com.onedata.portal.mapper.TableAccessDailyMapper;
import com.onedata.portal.mapper.TableAccessUserDailyMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DorisTableAccessServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;
    @Mock
    private DorisAuditAccessCheckpointMapper checkpointMapper;
    @Mock
    private TableAccessDailyMapper dailyMapper;
    @Mock
    private TableAccessUserDailyMapper userDailyMapper;

    private DorisAuditAccessSyncProperties properties;
    private DorisTableAccessService service;
    private DataTable table;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTable.class);
        TableInfoHelper.initTableInfo(assistant, DorisAuditAccessCheckpoint.class);
        properties = new DorisAuditAccessSyncProperties();
        service = new DorisTableAccessService(
                properties,
                dataTableMapper,
                checkpointMapper,
                dailyMapper,
                userDailyMapper,
                new TableAccessSummaryCache());
        table = new DataTable();
        table.setId(10L);
        table.setClusterId(1L);
        table.setDbName("dw");
        table.setTableName("orders");
        table.setDorisCreateTime(LocalDateTime.now().minusDays(180));
        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
    }

    @Test
    void disabledSyncServesExistingHotSummaryButSuppressesColdTables() {
        properties.setEnabled(false);
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now().minusDays(120), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.singletonList(aggregate(12L, LocalDateTime.now().minusDays(100))));

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("DISABLED", summary.getTableAccessSyncStatus());
        assertEquals(1, summary.getHotTables().size());
        assertTrue(summary.getLongUnusedTables().isEmpty());
        assertTrue(summary.getNote().contains("同步已关闭"));
    }

    @Test
    void incompleteCoverageNeverProducesColdTables() {
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now().minusDays(30), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("READY", summary.getTableAccessSyncStatus());
        assertFalse(summary.getTableAccessCoverageComplete());
        assertTrue(summary.getLongUnusedTables().isEmpty());
    }

    @Test
    void freshlyInitializedClusterReportsIncompleteCoverageInsteadOfColdTables() {
        // 汇总只做增量累积，不回填历史：上线当天覆盖起点就是当前时间，冷表必须保持沉默直到窗口攒够。
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now(), LocalDateTime.now())));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("READY", summary.getTableAccessSyncStatus());
        assertFalse(summary.getTableAccessCoverageComplete());
        assertTrue(summary.getLongUnusedTables().isEmpty());
        assertTrue(summary.getNote().contains("覆盖不足"));
    }

    @Test
    void completeReadyCoverageMarksOldNeverAccessedTableAsCold() {
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now().minusDays(120), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertTrue(summary.getTableAccessCoverageComplete());
        assertEquals(1, summary.getLongUnusedTables().size());
        assertEquals(10L, summary.getLongUnusedTables().get(0).getTableId());
    }

    @Test
    void backfillingServesHotSummaryButSuppressesColdTables() {
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "BACKFILLING", LocalDateTime.now().minusDays(30), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.singletonList(aggregate(4L, LocalDateTime.now().minusDays(2))));

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("BACKFILLING", summary.getTableAccessSyncStatus());
        assertEquals(1, summary.getHotTables().size());
        assertTrue(summary.getLongUnusedTables().isEmpty());
    }

    @Test
    void degradedServesLastSuccessfulSummaryButSuppressesColdTables() {
        DorisAuditAccessCheckpoint checkpoint = checkpoint(
                "DEGRADED", LocalDateTime.now().minusDays(120), LocalDateTime.now().minusMinutes(20));
        checkpoint.setLastError("Doris connection refused");
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.singletonList(aggregate(6L, LocalDateTime.now().minusDays(100))));

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("DEGRADED", summary.getTableAccessSyncStatus());
        assertEquals(1, summary.getHotTables().size());
        assertTrue(summary.getLongUnusedTables().isEmpty());
    }

    @Test
    void unavailableWithoutSuccessfulSummaryReturnsEmptyStatistics() {
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "UNAVAILABLE", LocalDateTime.now(), null)));

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertEquals("UNAVAILABLE", summary.getTableAccessSyncStatus());
        assertTrue(summary.getHotTables().isEmpty());
        assertTrue(summary.getLongUnusedTables().isEmpty());
    }

    @Test
    void recentlyCreatedNeverAccessedTableIsNotCold() {
        table.setDorisCreateTime(null);
        table.setCreatedAt(LocalDateTime.now().minusDays(10));
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now().minusDays(120), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        DashboardTableAccessSummary summary = service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        assertTrue(summary.getTableAccessCoverageComplete());
        assertTrue(summary.getLongUnusedTables().isEmpty());
    }

    private DorisAuditAccessCheckpoint checkpoint(String status,
            LocalDateTime coverageStart,
            LocalDateTime lastSyncedAt) {
        DorisAuditAccessCheckpoint checkpoint = new DorisAuditAccessCheckpoint();
        checkpoint.setClusterId(1L);
        checkpoint.setAuditSource("`doris_audit_db__`.`doris_audit_tbl__`");
        checkpoint.setSyncStatus(status);
        checkpoint.setCoverageStart(coverageStart);
        checkpoint.setLastSyncedAt(lastSyncedAt);
        return checkpoint;
    }

    @Test
    void coldWindowAggregatesFromTheThresholdCalendarDay() {
        // 冷表阈值是精确时刻，聚合起点必须回看到它所在的自然日，
        // 否则阈值当天晚些时候的访问会落在窗口外，把活跃表误判成冷表。
        when(checkpointMapper.selectList(any())).thenReturn(Collections.singletonList(checkpoint(
                "READY", LocalDateTime.now().minusDays(120), LocalDateTime.now().minusMinutes(5))));
        when(dailyMapper.selectDashboardAggregates(anyList(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.getDashboardAccessSummary(null, 30, 10, 90, 10);

        ArgumentCaptor<LocalDate> historyStart = ArgumentCaptor.forClass(LocalDate.class);
        verify(dailyMapper).selectDashboardAggregates(anyList(), any(), historyStart.capture());
        assertEquals(LocalDateTime.now().minusDays(90).toLocalDate(), historyStart.getValue());
    }

    private DashboardTableAccessAggregate aggregate(Long count, LocalDateTime lastAccess) {
        DashboardTableAccessAggregate aggregate = new DashboardTableAccessAggregate();
        aggregate.setClusterId(1L);
        aggregate.setDbName("dw");
        aggregate.setTableName("orders");
        aggregate.setAccessCount(count);
        aggregate.setLastAccessTime(lastAccess);
        return aggregate;
    }
}
