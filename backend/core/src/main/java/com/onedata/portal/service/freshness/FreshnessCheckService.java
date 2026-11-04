package com.onedata.portal.service.freshness;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.entity.TableFreshnessResult;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.mapper.TableFreshnessResultMapper;
import com.onedata.portal.service.DorisConnectionService.FreshnessProbe;
import com.onedata.portal.service.DorisConnectionService;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;



/**
 * 新鲜度检查执行。四种取值模式由契约显式指定，没有自动兜底；判定 pass/warn/error/runtime_error，
 * 每次检查都留痕并回写 {@code data_table} 最新态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FreshnessCheckService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final DorisConnectionService dorisConnectionService;
    private final TableFreshnessConfigMapper freshnessConfigMapper;
    private final TableFreshnessResultMapper freshnessResultMapper;
    private final DataTableMapper dataTableMapper;
    private final FreshnessContractResolver contractResolver;
    private final FreshnessCheckProperties properties;

    /**
     * 检查单表并落一行结果，回写 data_table 最新态。异常内部收敛为 runtime_error。
     */
    public FreshnessCheckResult check(DataTable table, FreshnessContract contract,
                                      String triggerType, String operator) {
        return check(table, contract, triggerType, operator, null);
    }

    /**
     * @param workflowInstanceId 触发本次检查的工作流实例ID；按需/单表检查传 null。
     */
    public FreshnessCheckResult check(DataTable table, FreshnessContract contract,
                                      String triggerType, String operator, Long workflowInstanceId) {
        FreshnessCheckResult result = evaluate(table, contract);
        result.setTableId(table.getId());
        result.setClusterId(table.getClusterId());
        result.setDbName(table.getDbName());
        result.setTableName(table.getTableName());
        persist(result, triggerType, operator, workflowInstanceId);
        return result;
    }

    public List<FreshnessCheckResult> checkBatch(List<DataTable> tables, String triggerType, String operator) {
        return checkBatch(tables, triggerType, operator, null);
    }

    /**
     * 批量检查。按 clusterId 分组，每集群并发上限来自配置；无契约的表跳过、不落结果；单表异常隔离。
     * 同一批次的结果共享 {@code workflowInstanceId}，用于按「每次运行」聚合并反查执行。
     */
    public List<FreshnessCheckResult> checkBatch(List<DataTable> tables, String triggerType, String operator,
                                                 Long workflowInstanceId) {
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, TableFreshnessConfig> configByTable = loadConfigs(tables);

        List<TableWithContract> pending = new ArrayList<>();
        for (DataTable table : tables) {
            contractResolver.resolve(table, configByTable.get(table.getId()))
                .ifPresent(c -> pending.add(new TableWithContract(table, c)));
        }
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }

        int maxConcurrent = Math.max(1, properties.getMaxConcurrentPerCluster());

        // 按 clusterId 分组，逐集群并发
        Map<Long, List<TableWithContract>> byCluster = pending.stream()
            .collect(Collectors.groupingBy(t -> t.table.getClusterId() == null ? -1L : t.table.getClusterId()));

        List<FreshnessCheckResult> results = new ArrayList<>();
        for (List<TableWithContract> group : byCluster.values()) {
            results.addAll(runGroup(group, maxConcurrent, triggerType, operator, workflowInstanceId));
        }
        return results;
    }

    private List<FreshnessCheckResult> runGroup(List<TableWithContract> group, int maxConcurrent,
                                                String triggerType, String operator, Long workflowInstanceId) {
        int poolSize = Math.min(maxConcurrent, group.size());
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<FreshnessCheckResult>> tasks = group.stream()
                .map(t -> (Callable<FreshnessCheckResult>) () -> {
                    try {
                        return check(t.table, t.contract, triggerType, operator, workflowInstanceId);
                    } catch (Exception e) {
                        log.error("Freshness check failed for table id={}", t.table.getId(), e);
                        return null;
                    }
                })
                .collect(Collectors.toList());

            List<FreshnessCheckResult> collected = new ArrayList<>();
            for (Future<FreshnessCheckResult> future : executor.invokeAll(tasks)) {
                try {
                    FreshnessCheckResult r = future.get();
                    if (r != null) {
                        collected.add(r);
                    }
                } catch (Exception e) {
                    log.error("Freshness check task error", e);
                }
            }
            return collected;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 纯判定：取数 + 状态计算，不做持久化。异常收敛为 runtime_error。
     */
    FreshnessCheckResult evaluate(DataTable table, FreshnessContract contract) {
        FreshnessCheckResult result = new FreshnessCheckResult();
        FreshnessMode mode = contract.getMode();
        result.setMode(mode == null ? null : mode.code());

        Long warnSec = contract.getWarnAfter() != null ? contract.getWarnAfter().toSeconds() : null;
        Long errSec = contract.getErrorAfter() != null ? contract.getErrorAfter().toSeconds() : null;
        result.setWarnAfterSeconds(warnSec);
        result.setErrorAfterSeconds(errSec);

        try {
            Probe probe = probe(table, contract, properties.getQueryTimeoutSeconds());
            result.setMaxLoadedAt(probe.maxLoadedAt);
            result.setSnapshottedAt(probe.snapshottedAt);

            if (probe.maxLoadedAt == null) {
                result.setStatus(FreshnessCheckResult.STATUS_ERROR);
                result.setReason(FreshnessCheckResult.REASON_NEVER_LOADED);
                return result;
            }

            long age = Duration.between(probe.maxLoadedAt, probe.snapshottedAt).getSeconds();
            result.setAgeSeconds(age);
            // 严格大于，先判 error 后判 warn（对齐 dbt Time.exceeded / FreshnessThreshold.status）
            if (errSec != null && age > errSec) {
                result.setStatus(FreshnessCheckResult.STATUS_ERROR);
            } else if (warnSec != null && age > warnSec) {
                result.setStatus(FreshnessCheckResult.STATUS_WARN);
            } else {
                result.setStatus(FreshnessCheckResult.STATUS_PASS);
            }
        } catch (Exception e) {
            result.setStatus(FreshnessCheckResult.STATUS_RUNTIME_ERROR);
            result.setErrorMessage(truncate(rootMessage(e)));
        }
        return result;
    }

    private Probe probe(DataTable table, FreshnessContract contract, int timeoutSeconds) {
        String db = table.getDbName();
        String tableName = resolveActualTableName(table.getTableName());
        Long clusterId = table.getClusterId();

        switch (contract.getMode()) {
            case COLUMN: {
                FreshnessProbe p = dorisConnectionService.probeMaxLoadedAt(
                    clusterId, db, tableName, contract.getLoadedAtField(), contract.getFilterExpr(), timeoutSeconds);
                return new Probe(p.getMaxLoadedAt(), p.getSnapshottedAt());
            }
            case CUSTOM_SQL: {
                FreshnessProbe p = dorisConnectionService.probeMaxLoadedAtByQuery(
                    clusterId, db, contract.getLoadedAtQuery(), timeoutSeconds);
                return new Probe(p.getMaxLoadedAt(), p.getSnapshottedAt());
            }
            case METADATA: {
                FreshnessProbe p = dorisConnectionService.probeMetadataUpdateTime(
                    clusterId, db, tableName, timeoutSeconds);
                return new Probe(p.getMaxLoadedAt(), p.getSnapshottedAt());
            }
            case PARTITION: {
                List<TablePartitionInfo> partitions = dorisConnectionService.listPartitions(clusterId, db, tableName);
                LocalDateTime latest = parseLatestPartitionDate(partitions, contract.getPartitionFormat())
                    .orElseThrow(() -> new IllegalStateException(
                        "无法从分区名按格式 " + contract.getPartitionFormat() + " 解析业务日期"));
                return new Probe(latest, LocalDateTime.now());
            }
            default:
                throw new IllegalStateException("未知的新鲜度取值模式: " + contract.getMode());
        }
    }

    /**
     * 从分区列表解析最新业务日期。逐个分区提取名字中的数字串按 {@code partitionFormat} 解析，
     * 取可解析结果的最大值。全部无法解析 → 空。
     */
    static Optional<LocalDateTime> parseLatestPartitionDate(List<TablePartitionInfo> partitions, String format) {
        if (partitions == null || partitions.isEmpty() || !StringUtils.hasText(format)) {
            return Optional.empty();
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format.trim());
        boolean hasTime = format.indexOf('H') >= 0 || format.indexOf('m') >= 0 || format.indexOf('s') >= 0;
        int expectedLen = format.trim().length();

        LocalDateTime max = null;
        for (TablePartitionInfo partition : partitions) {
            LocalDateTime parsed = parsePartitionDate(partition.getPartitionName(), formatter, hasTime, expectedLen);
            if (parsed != null && (max == null || parsed.isAfter(max))) {
                max = parsed;
            }
        }
        return Optional.ofNullable(max);
    }

    private static LocalDateTime parsePartitionDate(String partitionName, DateTimeFormatter formatter,
                                                    boolean hasTime, int expectedLen) {
        if (!StringUtils.hasText(partitionName)) {
            return null;
        }
        String digits = partitionName.replaceAll("\\D", "");
        if (digits.length() < expectedLen) {
            return null;
        }
        if (digits.length() > expectedLen) {
            digits = digits.substring(0, expectedLen);
        }
        try {
            if (hasTime) {
                return LocalDateTime.parse(digits, formatter);
            }
            return LocalDate.parse(digits, formatter).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
    }

    private void persist(FreshnessCheckResult result, String triggerType, String operator, Long workflowInstanceId) {
        TableFreshnessResult entity = new TableFreshnessResult();
        entity.setTableId(result.getTableId());
        entity.setClusterId(result.getClusterId());
        entity.setDbName(result.getDbName());
        entity.setTableName(result.getTableName());
        entity.setMode(result.getMode());
        entity.setStatus(result.getStatus());
        entity.setReason(result.getReason());
        entity.setMaxLoadedAt(result.getMaxLoadedAt());
        entity.setSnapshottedAt(result.getSnapshottedAt());
        entity.setAgeSeconds(result.getAgeSeconds());
        entity.setWarnAfterSeconds(result.getWarnAfterSeconds());
        entity.setErrorAfterSeconds(result.getErrorAfterSeconds());
        entity.setErrorMessage(result.getErrorMessage());
        entity.setTriggerType(triggerType);
        entity.setWorkflowInstanceId(workflowInstanceId);
        entity.setCheckedBy(operator);
        freshnessResultMapper.insert(entity);

        LocalDateTime checkedAt = result.getSnapshottedAt() != null ? result.getSnapshottedAt() : LocalDateTime.now();
        dataTableMapper.update(null, new UpdateWrapper<DataTable>()
            .eq("id", result.getTableId())
            .set("freshness_status", result.getStatus())
            .set("freshness_checked_at", checkedAt));
    }

    private Map<Long, TableFreshnessConfig> loadConfigs(List<DataTable> tables) {
        List<Long> ids = tables.stream().map(DataTable::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TableFreshnessConfig> configs = freshnessConfigMapper.selectList(
            new LambdaQueryWrapper<TableFreshnessConfig>().in(TableFreshnessConfig::getTableId, ids));
        Map<Long, TableFreshnessConfig> map = new HashMap<>();
        for (TableFreshnessConfig config : configs) {
            map.put(config.getTableId(), config);
        }
        return map;
    }

    private String resolveActualTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return tableName;
        }
        String normalized = tableName.trim();
        if (normalized.contains(".")) {
            String[] parts = normalized.split("\\.", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1].trim();
            }
        }
        return normalized;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message : cause.getClass().getSimpleName();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_ERROR_MESSAGE_LENGTH ? value : value.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /** 内部取数结果。 */
    private static final class Probe {
        final LocalDateTime maxLoadedAt;
        final LocalDateTime snapshottedAt;

        Probe(LocalDateTime maxLoadedAt, LocalDateTime snapshottedAt) {
            this.maxLoadedAt = maxLoadedAt;
            this.snapshottedAt = snapshottedAt;
        }
    }

    /** 表 + 已解析契约。 */
    private static final class TableWithContract {
        final DataTable table;
        final FreshnessContract contract;

        TableWithContract(DataTable table, FreshnessContract contract) {
            this.table = table;
            this.contract = contract;
        }
    }
}
