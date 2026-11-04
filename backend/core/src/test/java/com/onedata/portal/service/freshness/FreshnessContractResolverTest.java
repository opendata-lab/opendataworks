package com.onedata.portal.service.freshness;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约解析（单层，仅表级）：无契约不检查、显式关闭短路、无阈值不检查、字段来源恒为表级。
 */
class FreshnessContractResolverTest {

    private final FreshnessContractResolver resolver = new FreshnessContractResolver();

    private DataTable table() {
        DataTable t = new DataTable();
        t.setId(1L);
        t.setClusterId(1L);
        t.setDbName("dwd");
        t.setLayer("DWD");
        return t;
    }

    private TableFreshnessConfig config(String mode, String loadedAtField,
                                        Integer warnCount, String warnPeriod,
                                        Integer errCount, String errPeriod) {
        TableFreshnessConfig c = new TableFreshnessConfig();
        c.setMode(mode);
        c.setLoadedAtField(loadedAtField);
        c.setWarnAfterCount(warnCount);
        c.setWarnAfterPeriod(warnPeriod);
        c.setErrorAfterCount(errCount);
        c.setErrorAfterPeriod(errPeriod);
        c.setEnabled(true);
        return c;
    }

    @Test
    void noConfig_returnsEmpty() {
        assertFalse(resolver.resolve(table(), null).isPresent());
    }

    @Test
    void disabledConfig_shortCircuits() {
        TableFreshnessConfig c = config("column", "etl_time", 2, "hour", 4, "hour");
        c.setEnabled(false);
        assertFalse(resolver.resolve(table(), c).isPresent());
    }

    @Test
    void fullConfig_resolved() {
        TableFreshnessConfig c = config("column", "etl_time", 2, "hour", 4, "hour");
        Optional<FreshnessContract> result = resolver.resolve(table(), c);
        assertTrue(result.isPresent());
        FreshnessContract contract = result.get();
        assertEquals(FreshnessMode.COLUMN, contract.getMode());
        assertEquals("etl_time", contract.getLoadedAtField());
        assertEquals(2, contract.getWarnAfter().getCount());
        assertEquals(4, contract.getErrorAfter().getCount());
        assertEquals(FreshnessSource.TABLE, contract.sourceOf(FreshnessContract.F_WARN_AFTER));
    }

    @Test
    void noThreshold_returnsEmpty() {
        // 声明了取数方式但没有任何阈值 → 不可检查
        TableFreshnessConfig c = config("column", "etl_time", null, null, null, null);
        assertFalse(resolver.resolve(table(), c).isPresent());
    }

    @Test
    void onlyWarnThreshold_isCheckable() {
        TableFreshnessConfig c = config("metadata", null, 2, "hour", null, null);
        Optional<FreshnessContract> result = resolver.resolve(table(), c);
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getWarnAfter().getCount());
    }

    @Test
    void onlyErrorThreshold_isCheckable() {
        TableFreshnessConfig c = config("metadata", null, null, null, 4, "hour");
        Optional<FreshnessContract> result = resolver.resolve(table(), c);
        assertTrue(result.isPresent());
        assertEquals(4, result.get().getErrorAfter().getCount());
    }

    @Test
    void invalidMode_notCheckable() {
        // 模式无法解析 + 有阈值 → mode 为空 → 不可检查
        TableFreshnessConfig c = config("bogus", null, 2, "hour", 4, "hour");
        assertFalse(resolver.resolve(table(), c).isPresent());
    }
}
