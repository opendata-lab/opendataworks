package com.onedata.portal.service.freshness;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import java.util.Optional;
import org.springframework.stereotype.Component;



/**
 * 新鲜度契约解析。仅表级来源：每张表在 {@code table_freshness_config} 中显式声明取值方式与阈值
 * （对齐 dbt「每个 source 各自声明 freshness」）。{@code enabled = 0} 表示显式关闭该表检查。
 *
 * <p>无契约、或无任何阈值 → 返回空，该表不检查、不产出结论。
 * 不从 {@code statistics_cycle} / {@code schedule_cron} / 列名 / 规则默认推导。
 */
@Component
public class FreshnessContractResolver {

    /**
     * @param table       数据表
     * @param tableConfig 表级契约，可为 null
     * @return 可检查的契约；无契约、显式关闭或无阈值时返回空
     */
    public Optional<FreshnessContract> resolve(DataTable table, TableFreshnessConfig tableConfig) {
        if (tableConfig == null || Boolean.FALSE.equals(tableConfig.getEnabled())) {
            return Optional.empty();
        }

        FreshnessContract contract = FreshnessContract.builder()
            .mode(FreshnessMode.parse(tableConfig.getMode()).orElse(null), FreshnessSource.TABLE)
            .loadedAtField(trimToNull(tableConfig.getLoadedAtField()), FreshnessSource.TABLE)
            .loadedAtQuery(trimToNull(tableConfig.getLoadedAtQuery()), FreshnessSource.TABLE)
            .partitionFormat(trimToNull(tableConfig.getPartitionFormat()), FreshnessSource.TABLE)
            .filterExpr(trimToNull(tableConfig.getFilterExpr()), FreshnessSource.TABLE)
            .warnAfter(threshold(tableConfig.getWarnAfterCount(), tableConfig.getWarnAfterPeriod()), FreshnessSource.TABLE)
            .errorAfter(threshold(tableConfig.getErrorAfterCount(), tableConfig.getErrorAfterPeriod()), FreshnessSource.TABLE)
            .build();

        return contract.isCheckable() ? Optional.of(contract) : Optional.empty();
    }

    private FreshnessThreshold threshold(Integer count, String period) {
        if (count == null || count <= 0) {
            return null;
        }
        return FreshnessPeriod.parse(period)
            .map(p -> new FreshnessThreshold(count, p))
            .orElse(null);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
