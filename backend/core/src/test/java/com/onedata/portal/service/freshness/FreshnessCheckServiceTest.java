package com.onedata.portal.service.freshness;

import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.mapper.TableFreshnessResultMapper;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.DorisConnectionService.FreshnessProbe;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检查执行：四种状态、严格大于边界、never_loaded、四种模式取数、分区解析失败、批量跳过与隔离。
 */
class FreshnessCheckServiceTest {

    private final DorisConnectionService doris = mock(DorisConnectionService.class);
    private final TableFreshnessConfigMapper configMapper = mock(TableFreshnessConfigMapper.class);
    private final TableFreshnessResultMapper resultMapper = mock(TableFreshnessResultMapper.class);
    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);
    private final FreshnessContractResolver resolver = new FreshnessContractResolver();
    private final FreshnessCheckProperties properties = new FreshnessCheckProperties();

    private final FreshnessCheckService service = new FreshnessCheckService(
        doris, configMapper, resultMapper, dataTableMapper, resolver, properties);

    private DataTable table() {
        DataTable t = new DataTable();
        t.setId(1L);
        t.setClusterId(10L);
        t.setDbName("dwd");
        t.setTableName("dwd_order_di");
        return t;
    }

    private FreshnessThreshold hours(int n) {
        return new FreshnessThreshold(n, FreshnessPeriod.HOUR);
    }

    private FreshnessContract columnContract(FreshnessThreshold warn, FreshnessThreshold error) {
        return FreshnessContract.builder()
            .mode(FreshnessMode.COLUMN, FreshnessSource.TABLE)
            .loadedAtField("etl_time", FreshnessSource.TABLE)
            .warnAfter(warn, FreshnessSource.TABLE)
            .errorAfter(error, FreshnessSource.TABLE)
            .build();
    }

    private void stubColumnProbe(LocalDateTime maxLoaded, LocalDateTime snapshot) {
        when(doris.probeMaxLoadedAt(any(), any(), any(), any(), any(), anyInt()))
            .thenReturn(new FreshnessProbe(maxLoaded, snapshot));
    }

    @Test
    void pass_ageWellUnderWarn() {
        LocalDateTime snap = LocalDateTime.now();
        stubColumnProbe(snap.minusHours(1), snap);
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
        assertEquals(3600L, r.getAgeSeconds());
    }

    @Test
    void warn_betweenThresholds() {
        LocalDateTime snap = LocalDateTime.now();
        stubColumnProbe(snap.minusHours(3), snap);
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_WARN, r.getStatus());
    }

    @Test
    void error_overErrorThreshold() {
        LocalDateTime snap = LocalDateTime.now();
        stubColumnProbe(snap.minusHours(5), snap);
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_ERROR, r.getStatus());
    }

    @Test
    void boundary_ageEqualsWarn_isPass() {
        // age 恰好等于 warn 阈值 → pass（严格大于）
        LocalDateTime maxLoaded = LocalDateTime.of(2026, 8, 6, 0, 0, 0);
        LocalDateTime snap = maxLoaded.plusHours(2); // age = 7200 == warn
        stubColumnProbe(maxLoaded, snap);
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
        assertEquals(7200L, r.getAgeSeconds());
    }

    @Test
    void boundary_oneSecondOverWarn_isWarn() {
        LocalDateTime maxLoaded = LocalDateTime.of(2026, 8, 6, 0, 0, 0);
        LocalDateTime snap = maxLoaded.plusHours(2).plusSeconds(1); // age = 7201 > warn
        stubColumnProbe(maxLoaded, snap);
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_WARN, r.getStatus());
    }

    @Test
    void neverLoaded_maxNull_isError() {
        stubColumnProbe(null, LocalDateTime.now());
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_ERROR, r.getStatus());
        assertEquals(FreshnessCheckResult.REASON_NEVER_LOADED, r.getReason());
        assertNull(r.getAgeSeconds());
    }

    @Test
    void runtimeError_probeThrows() {
        when(doris.probeMaxLoadedAt(any(), any(), any(), any(), any(), anyInt()))
            .thenThrow(new RuntimeException("列 etl_time 不存在"));
        FreshnessCheckResult r = service.evaluate(table(), columnContract(hours(2), hours(4)));
        assertEquals(FreshnessCheckResult.STATUS_RUNTIME_ERROR, r.getStatus());
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getErrorMessage().contains("etl_time"));
    }

    @Test
    void onlyWarnThreshold_neverErrors() {
        LocalDateTime snap = LocalDateTime.now();
        stubColumnProbe(snap.minusHours(100), snap);
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.COLUMN, FreshnessSource.TABLE)
            .loadedAtField("etl_time", FreshnessSource.TABLE)
            .warnAfter(hours(2), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_WARN, r.getStatus());
    }

    @Test
    void onlyErrorThreshold_underThreshold_isPass() {
        LocalDateTime snap = LocalDateTime.now();
        stubColumnProbe(snap.minusHours(1), snap);
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.COLUMN, FreshnessSource.TABLE)
            .loadedAtField("etl_time", FreshnessSource.TABLE)
            .errorAfter(hours(4), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
    }

    @Test
    void customSql_usesQueryProbe() {
        LocalDateTime snap = LocalDateTime.now();
        when(doris.probeMaxLoadedAtByQuery(eq(10L), eq("dwd"), any(), anyInt()))
            .thenReturn(new FreshnessProbe(snap.minusHours(1), snap));
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.CUSTOM_SQL, FreshnessSource.TABLE)
            .loadedAtQuery("select max(order_time) from dwd.dwd_order_di", FreshnessSource.TABLE)
            .warnAfter(hours(2), FreshnessSource.TABLE)
            .errorAfter(hours(4), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
        verify(doris).probeMaxLoadedAtByQuery(eq(10L), eq("dwd"), any(), anyInt());
    }

    @Test
    void metadata_usesMetadataProbe() {
        LocalDateTime snap = LocalDateTime.now();
        when(doris.probeMetadataUpdateTime(eq(10L), eq("dwd"), eq("dwd_order_di"), anyInt()))
            .thenReturn(new FreshnessProbe(snap.minusHours(1), snap));
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.METADATA, FreshnessSource.TABLE)
            .warnAfter(hours(2), FreshnessSource.TABLE)
            .errorAfter(hours(4), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
    }

    @Test
    void partition_parsesLatestBusinessDate() {
        List<TablePartitionInfo> partitions = Arrays.asList(
            partition("p20260804"), partition("p20260806"), partition("p20260805"));
        when(doris.listPartitions(eq(10L), eq("dwd"), eq("dwd_order_di"))).thenReturn(partitions);
        // 阈值放大以确保 pass 由日期比较驱动而非误判
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.PARTITION, FreshnessSource.TABLE)
            .partitionFormat("yyyyMMdd", FreshnessSource.TABLE)
            .errorAfter(new FreshnessThreshold(3650, FreshnessPeriod.DAY), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_PASS, r.getStatus());
        assertEquals(LocalDateTime.of(2026, 8, 6, 0, 0), r.getMaxLoadedAt());
    }

    @Test
    void partition_unparseable_isRuntimeError() {
        when(doris.listPartitions(any(), any(), any()))
            .thenReturn(Collections.singletonList(partition("default")));
        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.PARTITION, FreshnessSource.TABLE)
            .partitionFormat("yyyyMMdd", FreshnessSource.TABLE)
            .errorAfter(hours(4), FreshnessSource.TABLE)
            .build();
        FreshnessCheckResult r = service.evaluate(table(), contract);
        assertEquals(FreshnessCheckResult.STATUS_RUNTIME_ERROR, r.getStatus());
    }

    @Test
    void parseLatestPartitionDate_static() {
        assertEquals(Optional.of(LocalDateTime.of(2026, 8, 6, 0, 0)),
            FreshnessCheckService.parseLatestPartitionDate(
                Arrays.asList(partition("p20260801"), partition("p20260806")), "yyyyMMdd"));
        assertFalse(FreshnessCheckService.parseLatestPartitionDate(
            Collections.singletonList(partition("nodate")), "yyyyMMdd").isPresent());
    }

    @Test
    void checkBatch_skipsUnconfiguredTables() {
        DataTable configured = table();
        DataTable unconfigured = new DataTable();
        unconfigured.setId(2L);
        unconfigured.setClusterId(10L);
        unconfigured.setDbName("dwd");
        unconfigured.setTableName("dim_user");

        TableFreshnessConfig cfg = new TableFreshnessConfig();
        cfg.setTableId(1L);
        cfg.setMode("metadata");
        cfg.setWarnAfterCount(2);
        cfg.setWarnAfterPeriod("hour");
        cfg.setErrorAfterCount(4);
        cfg.setErrorAfterPeriod("hour");
        cfg.setEnabled(true);
        when(configMapper.selectList(any())).thenReturn(Collections.singletonList(cfg));

        LocalDateTime snap = LocalDateTime.now();
        when(doris.probeMetadataUpdateTime(any(), any(), any(), anyInt()))
            .thenReturn(new FreshnessProbe(snap.minusHours(1), snap));

        List<FreshnessCheckResult> results = service.checkBatch(
            Arrays.asList(configured, unconfigured), "workflow", "system");

        // 只有 configured 被检查；unconfigured 无契约，跳过、不落结果
        assertEquals(1, results.size());
        assertEquals(1L, results.get(0).getTableId());
    }

    private TablePartitionInfo partition(String name) {
        TablePartitionInfo info = new TablePartitionInfo();
        info.setPartitionName(name);
        return info;
    }
}
