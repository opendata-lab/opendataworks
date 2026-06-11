package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.*;
import com.onedata.portal.mapper.*;
import com.onedata.portal.util.DorisCreateTableUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 巡检服务 - 检查数据表和任务的合规性
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {

    private final InspectionRecordMapper inspectionRecordMapper;
    private final InspectionIssueMapper inspectionIssueMapper;
    private final InspectionRuleMapper inspectionRuleMapper;
    private final DataTableMapper dataTableMapper;
    private final DataTaskMapper dataTaskMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    private final DataLineageMapper dataLineageMapper;
    private final ObjectMapper objectMapper;
    private final HealthCheckService healthCheckService;
    private final DorisClusterService dorisClusterService;
    private final DorisConnectionService dorisConnectionService;
    private final TableMetadataVersionService tableMetadataVersionService;

    private static final Pattern RECOMMENDED_REPLICA_PATTERN = Pattern.compile("推荐\\s*[:：]?\\s*(\\d+)");
    private static final Pattern RANGE_REPLICA_PATTERN = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern MIN_REPLICA_PATTERN = Pattern.compile(">=\\s*(\\d+)");
    private static final Pattern CREATE_VIEW_DDL_PATTERN = Pattern.compile(
        "^CREATE\\s+(OR\\s+REPLACE\\s+)?VIEW\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * 执行全量巡检
     */
    @Transactional
    public InspectionRecord runFullInspection(String triggerType, String createdBy) {
        log.info("Starting full inspection, trigger={}, createdBy={}", triggerType, createdBy);

        InspectionRecord record = new InspectionRecord();
        record.setInspectionType("full");
        record.setInspectionTime(LocalDateTime.now());
        record.setTriggerType(triggerType);
        record.setCreatedBy(createdBy);
        record.setStatus("running");
        inspectionRecordMapper.insert(record);

        LocalDateTime startTime = LocalDateTime.now();
        int totalIssues = 0;

        try {
            // 获取所有启用的巡检规则
            List<InspectionRule> rules = inspectionRuleMapper.selectList(
                new LambdaQueryWrapper<InspectionRule>()
                    .eq(InspectionRule::getEnabled, true)
            );

            for (InspectionRule rule : rules) {
                List<InspectionIssue> issues = executeRule(record.getId(), rule);
                totalIssues += issues.size();
            }

            // 更新巡检记录
            record.setStatus("completed");
            record.setIssueCount(totalIssues);
            record.setDurationSeconds((int) Duration.between(startTime, LocalDateTime.now()).getSeconds());
            inspectionRecordMapper.updateById(record);

            log.info("Inspection completed: recordId={}, issues={}, duration={}s",
                record.getId(), totalIssues, record.getDurationSeconds());

        } catch (Exception e) {
            log.error("Inspection failed: recordId={}", record.getId(), e);
            record.setStatus("failed");
            record.setDurationSeconds((int) Duration.between(startTime, LocalDateTime.now()).getSeconds());
            inspectionRecordMapper.updateById(record);
            throw new RuntimeException("Inspection failed", e);
        }

        return record;
    }

    /**
     * 执行单个巡检规则
     */
    private List<InspectionIssue> executeRule(Long recordId, InspectionRule rule) {
        log.debug("Executing rule: {}", rule.getRuleCode());

        switch (rule.getRuleType()) {
            case "table_naming":
                return checkTableNaming(recordId, rule);
            case "replica_count":
                return checkReplicaCount(recordId, rule);
            case "tablet_count":
                return checkTabletCount(recordId, rule);
            case "tablet_size":
                return checkTabletSize(recordId, rule);
            case "table_owner":
                return checkTableOwner(recordId, rule);
            case "table_comment":
                return checkTableComment(recordId, rule);
            case "task_failure":
                return checkTaskFailure(recordId, rule);
            case "task_schedule":
                return checkTaskSchedule(recordId, rule);
            case "table_layer":
                return checkTableLayer(recordId, rule);
            case "data_freshness":
                return checkDataFreshness(recordId, rule);
            case "data_volume_spike":
                return checkDataVolumeSpike(recordId, rule);
            case "service_health":
                return checkServiceHealth(recordId, rule);
            case "doris_node_resources":
                return checkDorisNodeResources(recordId, rule);
            case "orphan_tables":
                return checkOrphanTables(recordId, rule);
            case "deprecated_tables":
                return checkDeprecatedTables(recordId, rule);
            default:
                log.warn("Unknown rule type: {}", rule.getRuleType());
                return Collections.emptyList();
        }
    }

    /**
     * 检查表命名规范
     */
    private List<InspectionIssue> checkTableNaming(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        String patternStr = (String) config.getOrDefault("pattern", "^(ods|dwd|dim|dws|ads)_[a-z][a-z0-9_]*$");
        String errorMessage = (String) config.getOrDefault("errorMessage", "表名不符合命名规范");
        Pattern pattern = Pattern.compile(patternStr);

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active");
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (!pattern.matcher(table.getTableName()).matches()) {
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setIssueDescription(errorMessage);
                issue.setCurrentValue(table.getTableName());
                issue.setExpectedValue("符合正则: " + patternStr);
                issue.setSuggestion("请修改表名使其符合命名规范,格式: {layer}_xxx_xxx");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 检查副本数
     */
    private List<InspectionIssue> checkReplicaCount(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        int minReplicas = ((Number) config.getOrDefault("minReplicas", 1)).intValue();
        Integer maxReplicas = null;
        if (config.containsKey("maxReplicas") && config.get("maxReplicas") != null) {
            maxReplicas = ((Number) config.get("maxReplicas")).intValue();
        }
        int recommendedReplicas = ((Number) config.getOrDefault("recommendedReplicas", 3)).intValue();
        Set<Long> dorisClusterIds = resolveDorisClusterIds();
        if (dorisClusterIds.isEmpty()) {
            return issues;
        }

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getClusterId)
            .in(DataTable::getClusterId, dorisClusterIds)
            .isNotNull(DataTable::getReplicaNum);
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (isViewTable(table)) {
                continue;
            }
            Integer replicaNum = table.getReplicaNum();
            boolean outOfRange = replicaNum < minReplicas || (maxReplicas != null && replicaNum > maxReplicas);
            if (outOfRange) {
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setIssueDescription("副本数不在合理范围内");
                issue.setCurrentValue(String.valueOf(replicaNum));
                if (maxReplicas == null) {
                    issue.setExpectedValue(String.format(">= %d (推荐: %d)", minReplicas, recommendedReplicas));
                } else {
                    issue.setExpectedValue(String.format("%d-%d (推荐: %d)", minReplicas, maxReplicas, recommendedReplicas));
                }
                String tableName = resolveActualTableName(table.getTableName());
                String sql = (StringUtils.hasText(table.getDbName()) && StringUtils.hasText(tableName))
                    ? String.format("ALTER TABLE `%s`.`%s` SET (\"replication_num\" = \"%d\")",
                        table.getDbName(), tableName, recommendedReplicas)
                    : String.format("ALTER TABLE <db>.<table> SET (\"replication_num\" = \"%d\")",
                        recommendedReplicas);
                issue.setSuggestion(String.format("建议设置副本数为 %d 以保证数据可靠性\n修复脚本: %s",
                    recommendedReplicas, sql));
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 检查 Tablet 数量
     */
    private List<InspectionIssue> checkTabletCount(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        int maxTablets = ((Number) config.getOrDefault("maxTablets", 200)).intValue();
        int warningTablets = ((Number) config.getOrDefault("warningTablets", 100)).intValue();
        Set<Long> dorisClusterIds = resolveDorisClusterIds();
        if (dorisClusterIds.isEmpty()) {
            return issues;
        }

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getClusterId)
            .in(DataTable::getClusterId, dorisClusterIds)
            .isNotNull(DataTable::getDbName)
            .isNotNull(DataTable::getTableName);
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (isViewTable(table)) {
                continue;
            }
            String actualTableName = resolveActualTableName(table.getTableName());
            if (!StringUtils.hasText(actualTableName)) {
                continue;
            }

            Optional<DorisConnectionService.TableTabletStats> tabletStatsOptional =
                dorisConnectionService.getTableTabletStats(table.getClusterId(), table.getDbName(), actualTableName);
            if (!tabletStatsOptional.isPresent()) {
                continue;
            }
            DorisConnectionService.TableTabletStats tabletStats = tabletStatsOptional.get();
            long tabletCount = tabletStats.getTabletCount();
            if (tabletCount <= 0) {
                continue;
            }
            long totalDataSize = tabletStats.getTotalDataSizeBytes();
            long avgTabletSize = tabletStats.getAvgTabletSizeBytes();

            if (tabletCount > maxTablets) {
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setSeverity("high");
                issue.setIssueDescription("Tablet数量过多,可能影响性能");
                issue.setCurrentValue(String.valueOf(tabletCount));
                issue.setExpectedValue("<= " + maxTablets);
                issue.setSuggestion(String.format(
                    "当前总数据量 %s，平均Tablet大小 %s。建议优先调整分桶数和分区策略，使Tablet数量降到 %d 以下",
                    formatBytes(totalDataSize), formatBytes(avgTabletSize), maxTablets));

                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            } else if (tabletCount > warningTablets) {
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setSeverity("medium");
                issue.setIssueDescription("Tablet数量较多,需要关注");
                issue.setCurrentValue(String.valueOf(tabletCount));
                issue.setExpectedValue("<= " + warningTablets + " (推荐)");
                issue.setSuggestion(String.format(
                    "当前总数据量 %s，平均Tablet大小 %s。建议关注分桶与分区增长趋势，必要时提前调整",
                    formatBytes(totalDataSize), formatBytes(avgTabletSize)));
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 检查 Tablet 大小（真实值）
     * 通过 Doris SHOW TABLETS 获取真实 Tablet DataSize，避免按分区/分桶估算带来的误差。
     */
    private List<InspectionIssue> checkTabletSize(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        int minTabletSizeMb = ((Number) config.getOrDefault("minTabletSizeMb", 1024)).intValue(); // 1 GB
        int maxTabletSizeMb = ((Number) config.getOrDefault("maxTabletSizeMb", 10240)).intValue(); // 10 GB
        int targetTabletSizeMb = ((Number) config.getOrDefault("targetTabletSizeMb", 4096)).intValue(); // 4 GB
        int minTableSizeGbForSmallCheck = ((Number) config.getOrDefault("minTableSizeGbForSmallCheck", 20)).intValue();

        long minTabletBytes = minTabletSizeMb * 1024L * 1024;
        long maxTabletBytes = maxTabletSizeMb * 1024L * 1024;
        long targetTabletBytes = targetTabletSizeMb * 1024L * 1024;
        long minTableBytesForSmallCheck = minTableSizeGbForSmallCheck * 1024L * 1024 * 1024;
        Set<Long> dorisClusterIds = resolveDorisClusterIds();
        if (dorisClusterIds.isEmpty()) {
            return issues;
        }

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getClusterId)
            .in(DataTable::getClusterId, dorisClusterIds)
            .isNotNull(DataTable::getDbName)
            .isNotNull(DataTable::getTableName);
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (isViewTable(table)) {
                continue;
            }
            if (table == null || table.getClusterId() == null || !StringUtils.hasText(table.getDbName())
                || !StringUtils.hasText(table.getTableName())) {
                continue;
            }

            String actualTableName = resolveActualTableName(table.getTableName());
            if (!StringUtils.hasText(actualTableName)) {
                continue;
            }

            Optional<DorisConnectionService.TableTabletStats> tabletStatsOptional =
                dorisConnectionService.getTableTabletStats(table.getClusterId(), table.getDbName(), actualTableName);
            if (!tabletStatsOptional.isPresent()) {
                continue;
            }

            DorisConnectionService.TableTabletStats tabletStats = tabletStatsOptional.get();
            long dataSize = tabletStats.getTotalDataSizeBytes();
            long tabletCount = tabletStats.getTabletCount();
            if (dataSize <= 0 || tabletCount <= 0) {
                continue;
            }

            long avgTabletBytes = tabletStats.getAvgTabletSizeBytes();

            boolean tooLarge = avgTabletBytes > maxTabletBytes;
            boolean tooSmall = avgTabletBytes < minTabletBytes && dataSize >= minTableBytesForSmallCheck;
            if (!tooLarge && !tooSmall) {
                continue;
            }

            InspectionIssue issue = createIssue(recordId, rule, table);
            if (tooLarge) {
                issue.setSeverity("high");
                issue.setIssueDescription("平均Tablet大小过大,可能影响 compaction/导入性能");
            } else {
                issue.setSeverity("medium");
                issue.setIssueDescription("平均Tablet大小偏小,可能导致Tablet数量过多");
            }

            issue.setCurrentValue(String.format("%s (真实: tablets=%d, total=%s)", formatBytes(avgTabletBytes),
                tabletCount, formatBytes(dataSize)));
            issue.setExpectedValue(String.format("%s ~ %s (目标: %s)", formatBytes(minTabletBytes),
                formatBytes(maxTabletBytes), formatBytes(targetTabletBytes)));
            issue.setSuggestion(generateTabletSizeSuggestion(dataSize, tabletCount,
                minTabletBytes, maxTabletBytes, targetTabletBytes, tooLarge, tooSmall));

            inspectionIssueMapper.insert(issue);
            issues.add(issue);
        }

        return issues;
    }

    private String resolveActualTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
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

    private Set<Long> resolveDorisClusterIds() {
        List<DorisCluster> clusters = dorisClusterService.listAll();
        Set<Long> dorisClusterIds = new HashSet<>();
        for (DorisCluster cluster : clusters) {
            if (cluster == null || cluster.getId() == null) {
                continue;
            }
            if ("DORIS".equalsIgnoreCase(cluster.getSourceType())) {
                dorisClusterIds.add(cluster.getId());
            }
        }
        return dorisClusterIds;
    }

    private boolean isViewTable(DataTable table) {
        if (table == null) {
            return false;
        }
        if (StringUtils.hasText(table.getTableType())) {
            String tableType = table.getTableType().trim().toUpperCase(Locale.ROOT);
            if (tableType.contains("VIEW")) {
                return true;
            }
        }
        if (!StringUtils.hasText(table.getDorisDdl())) {
            return false;
        }
        return CREATE_VIEW_DDL_PATTERN.matcher(table.getDorisDdl().trim()).find();
    }

    private String generateTabletSizeSuggestion(long dataSize, long tabletCount,
                                                long minTabletBytes, long maxTabletBytes, long targetTabletBytes,
                                                boolean tooLarge, boolean tooSmall) {
        long targetTabletCount = Math.max(1L, (dataSize + targetTabletBytes - 1) / targetTabletBytes);

        if (tooLarge) {
            long minTabletsToMeetMax = Math.max(1L, (dataSize + maxTabletBytes - 1) / maxTabletBytes);
            return String.format(
                "当前Tablet数量约 %d，建议提升到 %d 以上(目标约 %d)，可通过增加分桶数或优化分区粒度实现",
                tabletCount, minTabletsToMeetMax, targetTabletCount);
        }

        if (tooSmall) {
            long maxTabletsToMeetMin = Math.max(1L, dataSize / minTabletBytes);
            return String.format(
                "当前Tablet数量约 %d，建议收敛到 %d 以下(目标约 %d)，可通过减少分桶数或减少动态分区数量实现",
                tabletCount, maxTabletsToMeetMin, targetTabletCount);
        }

        return "建议联合调整分桶与分区策略，使单Tablet大小落在推荐范围内";
    }

    /**
     * 检查表负责人
     */
    private List<InspectionIssue> checkTableOwner(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getOwner)
                .or().eq(DataTable::getOwner, ""));
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = createIssue(recordId, rule, table);
            issue.setIssueDescription("表未配置负责人");
            issue.setCurrentValue("null");
            issue.setExpectedValue("有效的负责人");
            issue.setSuggestion("请为表配置负责人,以便问题追踪和权限管理");
            inspectionIssueMapper.insert(issue);
            issues.add(issue);
        }

        return issues;
    }

    /**
     * 检查表注释
     */
    private List<InspectionIssue> checkTableComment(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getTableComment)
                .or().eq(DataTable::getTableComment, ""));
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = createIssue(recordId, rule, table);
            issue.setIssueDescription("表缺少注释说明");
            issue.setCurrentValue("null");
            issue.setExpectedValue("有意义的注释");
            issue.setSuggestion("请为表添加注释,说明表的用途和业务含义");
            inspectionIssueMapper.insert(issue);
            issues.add(issue);
        }

        return issues;
    }

    /**
     * 检查任务失败
     */
    private List<InspectionIssue> checkTaskFailure(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        int checkDays = ((Number) config.getOrDefault("checkDays", 1)).intValue();
        int maxFailures = ((Number) config.getOrDefault("maxFailures", 3)).intValue();

        LocalDateTime since = LocalDateTime.now().minusDays(checkDays);

        // 查询最近失败的任务执行
        List<TaskExecutionLog> failedLogs = executionLogMapper.selectList(
            new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getStatus, "failed")
                .ge(TaskExecutionLog::getStartTime, since)
                .orderByDesc(TaskExecutionLog::getStartTime)
        );

        // 按任务ID分组统计失败次数
        Map<Long, Long> failureCountByTask = new HashMap<>();
        for (TaskExecutionLog log : failedLogs) {
            failureCountByTask.merge(log.getTaskId(), 1L, Long::sum);
        }

        // 检查失败次数超过阈值的任务
        for (Map.Entry<Long, Long> entry : failureCountByTask.entrySet()) {
            if (entry.getValue() >= maxFailures) {
                DataTask task = dataTaskMapper.selectById(entry.getKey());
                if (task != null) {
                    InspectionIssue issue = new InspectionIssue();
                    issue.setRecordId(recordId);
                    issue.setIssueType(rule.getRuleType());
                    issue.setSeverity("critical");
                    issue.setResourceType("task");
                    issue.setResourceId(task.getId());
                    issue.setResourceName(task.getTaskName());
                    issue.setIssueDescription(String.format("任务最近%d天内失败%d次", checkDays, entry.getValue()));
                    issue.setCurrentValue(String.format("%d次失败", entry.getValue()));
                    issue.setExpectedValue("< " + maxFailures + "次");
                    issue.setSuggestion("请检查任务执行日志,排查失败原因并修复");
                    issue.setStatus("open");
                    inspectionIssueMapper.insert(issue);
                    issues.add(issue);
                }
            }
        }

        return issues;
    }

    /**
     * 检查任务调度 - 长期未执行的已发布任务
     */
    private List<InspectionIssue> checkTaskSchedule(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        int checkDays = ((Number) config.getOrDefault("checkDays", 7)).intValue();

        LocalDateTime since = LocalDateTime.now().minusDays(checkDays);

        // 查询已发布但长期未执行的任务
        List<DataTask> publishedTasks = dataTaskMapper.selectList(
            new LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getStatus, "published")
        );

        for (DataTask task : publishedTasks) {
            // 查询最近的执行记录
            TaskExecutionLog latestLog = executionLogMapper.selectOne(
                new LambdaQueryWrapper<TaskExecutionLog>()
                    .eq(TaskExecutionLog::getTaskId, task.getId())
                    .orderByDesc(TaskExecutionLog::getStartTime)
                    .last("LIMIT 1")
            );

            // 如果没有执行记录或最近执行时间超过阈值
            // Defensive null handling for start_time
            boolean isOverdue = latestLog == null ||
                                latestLog.getStartTime() == null ||
                                latestLog.getStartTime().isBefore(since);

            if (isOverdue) {
                InspectionIssue issue = new InspectionIssue();
                issue.setRecordId(recordId);
                issue.setIssueType(rule.getRuleType());
                issue.setSeverity("medium");
                issue.setResourceType("task");
                issue.setResourceId(task.getId());
                issue.setResourceName(task.getTaskName());
                issue.setIssueDescription(String.format("已发布任务超过%d天未执行", checkDays));
                issue.setCurrentValue(latestLog != null && latestLog.getStartTime() != null
                    ? latestLog.getStartTime().toString()
                    : "从未执行");
                issue.setExpectedValue("定期执行");
                issue.setSuggestion("请检查任务调度配置或下线不需要的任务");
                issue.setStatus("open");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 检查数据层级
     */
    private List<InspectionIssue> checkTableLayer(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());
        @SuppressWarnings("unchecked")
        List<String> validLayers = (List<String>) config.getOrDefault("validLayers",
            Arrays.asList("ODS", "DWD", "DIM", "DWS", "ADS"));

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getLayer)
                .or().eq(DataTable::getLayer, ""));
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = createIssue(recordId, rule, table);
            issue.setIssueDescription("表未配置数据层级");
            issue.setCurrentValue("null");
            issue.setExpectedValue(String.join(", ", validLayers));
            issue.setSuggestion("请为表配置正确的数据层级(ODS/DWD/DIM/DWS/ADS)");
            inspectionIssueMapper.insert(issue);
            issues.add(issue);
        }

        return issues;
    }

    /**
     * 检查数据新鲜度 - 根据表的更新频率检查数据是否及时更新
     */
    private List<InspectionIssue> checkDataFreshness(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        // 默认延迟容忍度(小时),允许一定的延迟
        int toleranceHours = ((Number) config.getOrDefault("toleranceHours", 2)).intValue();

        // 查询有更新频率配置的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getStatisticsCycle)
            .ne(DataTable::getStatisticsCycle, "");
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            String cycle = table.getStatisticsCycle();
            if (cycle == null || cycle.isEmpty()) {
                continue;
            }

            // 解析更新周期并计算预期更新时间阈值
            Integer expectedHours = parseUpdateCycle(cycle);
            if (expectedHours == null) {
                log.warn("Unknown statistics cycle: {} for table: {}", cycle, table.getTableName());
                continue;
            }

            // 实际阈值 = 预期更新周期 + 容忍延迟
            int thresholdHours = expectedHours + toleranceHours;
            LocalDateTime threshold = LocalDateTime.now().minusHours(thresholdHours);

            // 检查最后更新时间
            if (table.getDorisUpdateTime() == null || table.getDorisUpdateTime().isBefore(threshold)) {
                InspectionIssue issue = createIssue(recordId, rule, table);

                // 根据延迟时间设置严重程度
                long delayHours = calculateDelayHours(table.getDorisUpdateTime(), expectedHours);
                issue.setSeverity(calculateFreshnessSeverity(delayHours, expectedHours));

                issue.setIssueDescription(String.format("表更新频率为 %s,但数据已 %d 小时未更新",
                    getCycleDescription(cycle), delayHours));
                issue.setCurrentValue(table.getDorisUpdateTime() != null ?
                    table.getDorisUpdateTime().toString() + " (已延迟 " + delayHours + " 小时)" : "从未更新");
                issue.setExpectedValue("更新频率: " + getCycleDescription(cycle));
                issue.setSuggestion(generateFreshnessSuggestion(cycle, delayHours));

                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 解析更新周期配置,返回预期的更新间隔(小时)
     * 支持格式:
     * - 1d, 2d, 7d: 天
     * - 1h, 6h, 12h: 小时
     * - 1w, 2w: 周
     * - 1m: 月
     */
    private Integer parseUpdateCycle(String cycle) {
        if (cycle == null || cycle.isEmpty()) {
            return null;
        }

        cycle = cycle.toLowerCase().trim();

        try {
            // 匹配天 (1d, 2d, etc.)
            if (cycle.endsWith("d")) {
                int days = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return days * 24;
            }

            // 匹配小时 (1h, 6h, etc.)
            if (cycle.endsWith("h")) {
                return Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            }

            // 匹配周 (1w, 2w, etc.)
            if (cycle.endsWith("w")) {
                int weeks = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return weeks * 7 * 24;
            }

            // 匹配月 (1m)
            if (cycle.endsWith("m")) {
                int months = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
                return months * 30 * 24; // 简化为30天
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse update cycle: {}", cycle, e);
        }

        return null;
    }

    /**
     * 计算数据延迟的小时数
     */
    private long calculateDelayHours(LocalDateTime dorisUpdateTime, int expectedHours) {
        if (dorisUpdateTime == null) {
            return Long.MAX_VALUE; // 从未更新
        }

        LocalDateTime expectedTime = LocalDateTime.now().minusHours(expectedHours);
        if (dorisUpdateTime.isBefore(expectedTime)) {
            return Duration.between(dorisUpdateTime, LocalDateTime.now()).toHours();
        }

        return 0;
    }

    /**
     * 根据延迟时间计算数据新鲜度问题的严重程度
     */
    private String calculateFreshnessSeverity(long delayHours, int expectedHours) {
        if (delayHours == Long.MAX_VALUE) {
            return "critical"; // 从未更新
        }

        // 计算延迟倍数
        double delayRatio = (double) delayHours / expectedHours;

        if (delayRatio >= 3.0) {
            return "critical"; // 延迟超过3倍预期周期
        } else if (delayRatio >= 2.0) {
            return "high"; // 延迟超过2倍预期周期
        } else if (delayRatio >= 1.5) {
            return "medium"; // 延迟超过1.5倍预期周期
        } else {
            return "low"; // 轻微延迟
        }
    }

    /**
     * 获取更新周期的中文描述
     */
    private String getCycleDescription(String cycle) {
        cycle = cycle.toLowerCase().trim();

        if (cycle.endsWith("d")) {
            int days = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return days == 1 ? "每天" : "每 " + days + " 天";
        }

        if (cycle.endsWith("h")) {
            int hours = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return "每 " + hours + " 小时";
        }

        if (cycle.endsWith("w")) {
            int weeks = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return weeks == 1 ? "每周" : "每 " + weeks + " 周";
        }

        if (cycle.endsWith("m")) {
            int months = Integer.parseInt(cycle.substring(0, cycle.length() - 1));
            return months == 1 ? "每月" : "每 " + months + " 月";
        }

        return cycle;
    }

    /**
     * 生成数据新鲜度问题的修复建议
     */
    private String generateFreshnessSuggestion(String cycle, long delayHours) {
        StringBuilder suggestion = new StringBuilder();

        if (delayHours == Long.MAX_VALUE) {
            suggestion.append("该表从未更新过数据,请检查:");
        } else if (delayHours >= 72) {
            suggestion.append("数据已延迟超过3天,建议立即处理:");
        } else if (delayHours >= 48) {
            suggestion.append("数据已延迟超过2天,建议尽快处理:");
        } else {
            suggestion.append("数据更新延迟,建议检查:");
        }

        suggestion.append("\n1. 检查数据同步任务是否正常运行");
        suggestion.append("\n2. 确认上游数据源是否有数据产出");
        suggestion.append("\n3. 检查任务调度配置是否正确");
        suggestion.append("\n4. 查看任务执行日志排查失败原因");

        if (delayHours >= 168) { // 超过一周
            suggestion.append("\n5. 考虑是否需要调整表的更新频率配置");
        }

        return suggestion.toString();
    }

    /**
     * 检查数据量暴增/暴降 - 通过对比历史数据
     */
    private List<InspectionIssue> checkDataVolumeSpike(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        // 暴增阈值,默认增长超过2倍视为异常
        double increaseThreshold = ((Number) config.getOrDefault("increaseThreshold", 2.0)).doubleValue();
        // 暴降阈值,默认降低到50%以下视为异常
        double decreaseThreshold = ((Number) config.getOrDefault("decreaseThreshold", 0.5)).doubleValue();
        // 对比的历史天数,默认对比7天前的数据
        int compareDays = ((Number) config.getOrDefault("compareDays", 7)).intValue();
        // 最小行数阈值,小于此值的表不检查(避免小表波动)
        long minRowThreshold = ((Number) config.getOrDefault("minRowThreshold", 1000)).longValue();

        LocalDateTime compareTime = LocalDateTime.now().minusDays(compareDays);

        // 查询有数据量记录的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getRowCount)
            .gt(DataTable::getRowCount, minRowThreshold); // 只检查数据量超过阈值的表
        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            Long currentRowCount = table.getRowCount();
            if (currentRowCount == null || currentRowCount < minRowThreshold) {
                continue;
            }

            // 获取历史数据量
            // 注意:这需要有表统计历史记录,如果没有历史数据则跳过
            // 这里假设有 TableStatisticsHistory 表和相应的 Mapper
            // 由于当前可能没有实现,我们使用一个简化的逻辑

            // TODO: 查询 table_statistics_history 表获取历史数据
            // 临时实现:检查数据量是否异常大或异常小
            if (currentRowCount > 100_000_000) {
                // 数据量超过1亿,给出告警
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setSeverity("medium");
                issue.setIssueDescription("表数据量异常大,可能存在数据堆积");
                issue.setCurrentValue(String.format("%,d 行", currentRowCount));
                issue.setExpectedValue("正常数据量范围");
                issue.setSuggestion("请检查:\n1. 是否存在数据重复写入\n2. 是否需要启用数据归档\n3. 是否需要调整分区策略\n4. 考虑数据生命周期管理");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            } else if (currentRowCount < 100 && table.getDorisUpdateTime() != null &&
                       table.getDorisUpdateTime().isBefore(LocalDateTime.now().minusDays(1))) {
                // 数据量很小且长时间未更新
                InspectionIssue issue = createIssue(recordId, rule, table);
                issue.setSeverity("low");
                issue.setIssueDescription("表数据量异常少");
                issue.setCurrentValue(String.format("%,d 行", currentRowCount));
                issue.setExpectedValue("正常数据量");
                issue.setSuggestion("请检查:\n1. 数据是否正常写入\n2. 是否存在数据丢失\n3. 上游数据源是否正常");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }

            // 实际的历史对比逻辑(需要 TableStatisticsHistoryMapper)
            // Long historicalRowCount = getHistoricalRowCount(table.getId(), compareTime);
            // if (historicalRowCount != null && historicalRowCount > minRowThreshold) {
            //     double changeRatio = (double) currentRowCount / historicalRowCount;
            //
            //     if (changeRatio >= increaseThreshold) {
            //         // 数据量暴增
            //         InspectionIssue issue = createIssue(recordId, rule, table);
            //         issue.setSeverity(calculateVolumeSeverity(changeRatio, true));
            //         issue.setIssueDescription(String.format("数据量异常增长 %.1f 倍", changeRatio));
            //         issue.setCurrentValue(String.format("%,d 行 (增长 %.1f%%)",
            //             currentRowCount, (changeRatio - 1) * 100));
            //         issue.setExpectedValue(String.format("历史数据量: %,d 行", historicalRowCount));
            //         issue.setSuggestion(generateVolumeSuggestion(changeRatio, true));
            //         inspectionIssueMapper.insert(issue);
            //         issues.add(issue);
            //     } else if (changeRatio <= decreaseThreshold) {
            //         // 数据量暴降
            //         InspectionIssue issue = createIssue(recordId, rule, table);
            //         issue.setSeverity(calculateVolumeSeverity(changeRatio, false));
            //         issue.setIssueDescription(String.format("数据量异常下降 %.1f%%", (1 - changeRatio) * 100));
            //         issue.setCurrentValue(String.format("%,d 行 (下降 %.1f%%)",
            //             currentRowCount, (1 - changeRatio) * 100));
            //         issue.setExpectedValue(String.format("历史数据量: %,d 行", historicalRowCount));
            //         issue.setSuggestion(generateVolumeSuggestion(changeRatio, false));
            //         inspectionIssueMapper.insert(issue);
            //         issues.add(issue);
            //     }
            // }
        }

        return issues;
    }

    /**
     * 检查服务健康状态
     */
    private List<InspectionIssue> checkServiceHealth(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, HealthCheckService.ServiceHealthStatus> healthStatuses =
            healthCheckService.checkAllServices();

        for (Map.Entry<String, HealthCheckService.ServiceHealthStatus> entry : healthStatuses.entrySet()) {
            HealthCheckService.ServiceHealthStatus status = entry.getValue();

            if (!status.isHealthy()) {
                InspectionIssue issue = new InspectionIssue();
                issue.setRecordId(recordId);
                issue.setIssueType(rule.getRuleType());
                issue.setSeverity("critical");
                issue.setResourceType("service");
                issue.setResourceName(status.getServiceName());
                issue.setIssueDescription("服务健康检查失败: " + status.getMessage());
                issue.setCurrentValue("不健康");
                issue.setExpectedValue("服务正常运行");
                issue.setSuggestion(generateServiceHealthSuggestion(status));
                issue.setStatus("open");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 检查 Doris 节点资源使用
     */
    private List<InspectionIssue> checkDorisNodeResources(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        // 磁盘使用率告警阈值
        double diskWarningThreshold = ((Number) config.getOrDefault("diskWarningThreshold", 80.0)).doubleValue();
        double diskCriticalThreshold = ((Number) config.getOrDefault("diskCriticalThreshold", 90.0)).doubleValue();

        // 内存使用率告警阈值
        double memoryWarningThreshold = ((Number) config.getOrDefault("memoryWarningThreshold", 80.0)).doubleValue();
        double memoryCriticalThreshold = ((Number) config.getOrDefault("memoryCriticalThreshold", 90.0)).doubleValue();

        List<HealthCheckService.DorisNodeResourceStatus> nodeStatuses =
            healthCheckService.checkDorisNodeResources();

        for (HealthCheckService.DorisNodeResourceStatus nodeStatus : nodeStatuses) {
            // 检查磁盘使用率
            if (nodeStatus.getDiskUsagePercent() >= diskCriticalThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 磁盘使用率过高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%%", nodeStatus.getDiskUsagePercent()));
                issue.setExpectedValue(String.format("< %.0f%%", diskCriticalThreshold));
                issue.setSuggestion("立即处理:\n1. 清理过期数据和临时文件\n2. 检查数据归档策略\n3. 考虑扩容磁盘\n4. 检查是否有异常大表");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            } else if (nodeStatus.getDiskUsagePercent() >= diskWarningThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("节点 %s 磁盘使用率接近上限", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%%", nodeStatus.getDiskUsagePercent()));
                issue.setExpectedValue(String.format("< %.0f%%", diskWarningThreshold));
                issue.setSuggestion("建议:\n1. 规划磁盘扩容\n2. 检查数据增长趋势\n3. 优化数据生命周期策略");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }

            // 检查内存使用率
            if (nodeStatus.getMemoryUsagePercent() >= memoryCriticalThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 内存使用率过高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%% (%s / %s)",
                    nodeStatus.getMemoryUsagePercent(),
                    formatBytes(nodeStatus.getMemoryUsedBytes()),
                    formatBytes(nodeStatus.getMemoryLimitBytes())));
                issue.setExpectedValue(String.format("< %.0f%%", memoryCriticalThreshold));
                issue.setSuggestion("立即处理:\n1. 检查是否有慢查询占用过多内存\n2. 优化查询计划\n3. 考虑增加节点内存配置\n4. 重启服务释放内存(谨慎操作)");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            } else if (nodeStatus.getMemoryUsagePercent() >= memoryWarningThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("节点 %s 内存使用率较高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%% (%s / %s)",
                    nodeStatus.getMemoryUsagePercent(),
                    formatBytes(nodeStatus.getMemoryUsedBytes()),
                    formatBytes(nodeStatus.getMemoryLimitBytes())));
                issue.setExpectedValue(String.format("< %.0f%%", memoryWarningThreshold));
                issue.setSuggestion("建议:\n1. 监控内存使用趋势\n2. 优化频繁执行的查询\n3. 规划内存扩容");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }

            // 检查节点存活状态
            if (!nodeStatus.isAlive()) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 离线", nodeStatus.getHost()));
                issue.setCurrentValue("离线");
                issue.setExpectedValue("在线");
                issue.setSuggestion("紧急处理:\n1. 检查节点服务是否运行\n2. 检查网络连接\n3. 查看节点日志排查问题\n4. 联系运维团队");
                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 创建 Doris 节点问题记录
     */
    private InspectionIssue createDorisNodeIssue(Long recordId, InspectionRule rule,
                                                   HealthCheckService.DorisNodeResourceStatus nodeStatus) {
        InspectionIssue issue = new InspectionIssue();
        issue.setRecordId(recordId);
        issue.setIssueType(rule.getRuleType());
        issue.setSeverity(rule.getSeverity());
        issue.setResourceType("doris_node");
        issue.setResourceName(String.format("%s:%d", nodeStatus.getHost(), nodeStatus.getPort()));
        issue.setStatus("open");
        return issue;
    }

    /**
     * 生成服务健康问题的建议
     */
    private String generateServiceHealthSuggestion(HealthCheckService.ServiceHealthStatus status) {
        StringBuilder suggestion = new StringBuilder();
        suggestion.append("服务 ").append(status.getServiceName()).append(" 异常,请检查:\n");
        suggestion.append("1. 检查服务是否正常运行\n");
        suggestion.append("2. 检查网络连接是否正常\n");
        suggestion.append("3. 查看服务日志排查问题\n");
        suggestion.append("4. 检查服务配置是否正确\n");

        if (status.getError() != null) {
            suggestion.append("5. 错误类型: ").append(status.getError()).append("\n");
        }

        suggestion.append("6. 如问题持续,请联系运维团队");
        return suggestion.toString();
    }

    /**
     * 格式化字节数
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 检查孤立表 - 没有上下游依赖关系的表
     */
    private List<InspectionIssue> checkOrphanTables(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        // 是否检查所有状态的表,默认只检查 active 和 inactive 状态
        boolean includeDeprecated = (boolean) config.getOrDefault("includeDeprecated", false);
        // 孤立表的最短存在时间(天),避免刚创建的表被误判
        int minExistDays = ((Number) config.getOrDefault("minExistDays", 30)).intValue();

        LocalDateTime minCreateTime = LocalDateTime.now().minusDays(minExistDays);

        // 查询候选表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .le(DataTable::getCreatedAt, minCreateTime);

        if (!includeDeprecated) {
            tableWrapper.in(DataTable::getStatus, "active", "inactive");
        }

        applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            // 检查是否有血缘关系
            boolean hasUpstream = hasUpstreamLineage(table.getId());
            boolean hasDownstream = hasDownstreamLineage(table.getId());

            if (!hasUpstream && !hasDownstream) {
                // 没有任何上下游依赖关系,是孤立表
                InspectionIssue issue = createIssue(recordId, rule, table);

                // 根据表的状态和数据量判断严重程度
                String severity = calculateOrphanTableSeverity(table);
                issue.setSeverity(severity);

                long existDays = Duration.between(table.getCreatedAt(), LocalDateTime.now()).toDays();
                issue.setIssueDescription(String.format("表没有任何上下游依赖关系,已存在 %d 天", existDays));
                issue.setCurrentValue("无上游,无下游");
                issue.setExpectedValue("至少有一个上游或下游依赖");
                issue.setSuggestion(generateOrphanTableSuggestion(table, existDays));

                inspectionIssueMapper.insert(issue);
                issues.add(issue);
            }
        }

        log.info("Found {} orphan tables", issues.size());
        return issues;
    }

    /**
     * 检查废弃表 - 状态为 deprecated 且没有依赖的表
     */
    private List<InspectionIssue> checkDeprecatedTables(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = parseRuleConfig(rule.getRuleConfig());

        // 废弃表的最短存在时间(天),超过这个时间建议删除
        int deprecatedThresholdDays = ((Number) config.getOrDefault("deprecatedThresholdDays", 90)).intValue();
        // 是否检查有下游依赖的废弃表
        boolean checkWithDownstream = (boolean) config.getOrDefault("checkWithDownstream", false);

        LocalDateTime deprecatedThreshold = LocalDateTime.now().minusDays(deprecatedThresholdDays);

        // 查询废弃状态的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "deprecated");
        applyTableScope(tableWrapper, config);
        List<DataTable> deprecatedTables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : deprecatedTables) {
            // 检查血缘关系
            boolean hasUpstream = hasUpstreamLineage(table.getId());
            boolean hasDownstream = hasDownstreamLineage(table.getId());

            // 如果不检查有下游的表,且该表有下游,则跳过
            if (!checkWithDownstream && hasDownstream) {
                continue;
            }

            // 计算废弃时长
            long deprecatedDays = 0;
            if (table.getUpdatedAt() != null) {
                deprecatedDays = Duration.between(table.getUpdatedAt(), LocalDateTime.now()).toDays();
            }

            InspectionIssue issue = createIssue(recordId, rule, table);

            if (hasDownstream) {
                // 有下游依赖的废弃表,严重程度更高
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("废弃表仍有下游依赖,已废弃 %d 天", deprecatedDays));
                issue.setCurrentValue(String.format("状态: deprecated, 有下游依赖"));
                issue.setExpectedValue("清理下游依赖后删除");
                issue.setSuggestion("紧急处理:\n1. 检查下游表和任务的使用情况\n2. 迁移下游依赖到其他表\n3. 确认下游已停止使用后删除该表\n4. 或者恢复该表的 active 状态");
            } else if (!hasUpstream && !hasDownstream) {
                // 没有任何依赖的废弃表
                if (deprecatedDays >= deprecatedThresholdDays) {
                    issue.setSeverity("medium");
                    issue.setIssueDescription(String.format("废弃表无依赖关系,已废弃 %d 天,建议删除", deprecatedDays));
                    issue.setCurrentValue(String.format("状态: deprecated, 无依赖, 数据量: %s",
                        table.getRowCount() != null ? String.format("%,d 行", table.getRowCount()) : "未知"));
                    issue.setExpectedValue("已删除");
                    issue.setSuggestion(generateDeprecatedTableSuggestion(table, deprecatedDays));
                } else {
                    issue.setSeverity("low");
                    issue.setIssueDescription(String.format("废弃表无依赖关系,已废弃 %d 天", deprecatedDays));
                    issue.setCurrentValue(String.format("状态: deprecated, 无依赖"));
                    issue.setExpectedValue(String.format("废弃超过 %d 天后删除", deprecatedThresholdDays));
                    issue.setSuggestion(String.format("建议:\n1. 废弃时间未超过 %d 天,暂时保留\n2. 确认表确实不再使用\n3. 如需恢复,可修改状态为 active", deprecatedThresholdDays));
                }
            } else {
                // 只有上游依赖的废弃表
                issue.setSeverity("medium");
                issue.setIssueDescription(String.format("废弃表仅有上游依赖,已废弃 %d 天", deprecatedDays));
                issue.setCurrentValue("状态: deprecated, 有上游无下游");
                issue.setExpectedValue("评估后删除");
                issue.setSuggestion("建议:\n1. 确认上游写入已停止\n2. 检查是否还需要保留历史数据\n3. 考虑归档后删除\n4. 或者将数据迁移到其他表");
            }

            inspectionIssueMapper.insert(issue);
            issues.add(issue);
        }

        log.info("Found {} deprecated tables need attention", issues.size());
        return issues;
    }

    /**
     * 检查表是否有上游血缘关系
     */
    private boolean hasUpstreamLineage(Long tableId) {
        return dataLineageMapper.selectCount(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getDownstreamTableId, tableId)
        ) > 0;
    }

    /**
     * 检查表是否有下游血缘关系
     */
    private boolean hasDownstreamLineage(Long tableId) {
        return dataLineageMapper.selectCount(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getUpstreamTableId, tableId)
        ) > 0;
    }

    /**
     * 计算孤立表的严重程度
     */
    private String calculateOrphanTableSeverity(DataTable table) {
        // 如果是 deprecated 状态,严重程度较低
        if ("deprecated".equals(table.getStatus())) {
            return "low";
        }

        // 如果是 inactive 状态
        if ("inactive".equals(table.getStatus())) {
            return "medium";
        }

        // 如果是 active 状态的孤立表,需要关注
        // 根据数据量判断
        if (table.getRowCount() != null && table.getRowCount() > 0) {
            // 有数据的 active 孤立表,严重程度较高
            return "high";
        } else {
            // 没有数据的 active 孤立表
            return "medium";
        }
    }

    /**
     * 生成孤立表的修复建议
     */
    private String generateOrphanTableSuggestion(DataTable table, long existDays) {
        StringBuilder suggestion = new StringBuilder();

        if ("active".equals(table.getStatus())) {
            suggestion.append("该表状态为 active 但没有任何依赖关系,建议:\n");
            suggestion.append("1. 确认该表是否仍在使用\n");
            suggestion.append("2. 如果不再使用,修改状态为 deprecated\n");
            suggestion.append("3. 如果仍在使用,建立正确的血缘关系\n");
            suggestion.append("4. 检查是否为临时表或测试表\n");

            if (table.getRowCount() != null && table.getRowCount() > 0) {
                suggestion.append(String.format("5. 该表有数据(%,d 行),请谨慎处理\n", table.getRowCount()));
            }
        } else if ("inactive".equals(table.getStatus())) {
            suggestion.append("该表状态为 inactive 且无依赖关系,建议:\n");
            suggestion.append("1. 确认该表是否还需要\n");
            suggestion.append("2. 如确认不需要,修改状态为 deprecated\n");
            suggestion.append(String.format("3. 该表已存在 %d 天,考虑清理\n", existDays));
        } else if ("deprecated".equals(table.getStatus())) {
            suggestion.append("该表已废弃且无依赖关系,建议:\n");
            suggestion.append("1. 确认数据已备份(如需要)\n");
            suggestion.append("2. 可以安全删除该表\n");
            suggestion.append(String.format("3. 该表已存在 %d 天\n", existDays));
        }

        if (existDays > 180) {
            suggestion.append(String.format("注意: 该表已存在 %d 天(超过6个月),建议尽快处理", existDays));
        }

        return suggestion.toString();
    }

    /**
     * 生成废弃表的修复建议
     */
    private String generateDeprecatedTableSuggestion(DataTable table, long deprecatedDays) {
        StringBuilder suggestion = new StringBuilder();

        suggestion.append(String.format("该表已废弃 %d 天,建议执行以下操作:\n", deprecatedDays));
        suggestion.append("1. 确认数据是否需要归档备份\n");

        if (table.getRowCount() != null && table.getRowCount() > 0) {
            suggestion.append(String.format("2. 该表有 %,d 行数据,如需保留请先备份\n", table.getRowCount()));
            suggestion.append("3. 可以先清空数据,观察是否有影响\n");
            suggestion.append("4. 确认无影响后删除表结构\n");
        } else {
            suggestion.append("2. 该表数据量很少或为空,可以直接删除\n");
        }

        if (table.getStorageSize() != null && table.getStorageSize() > 0) {
            suggestion.append(String.format("5. 删除可释放约 %s 存储空间\n",
                formatBytes(table.getStorageSize())));
        }

        suggestion.append("6. 执行删除命令: DROP TABLE IF EXISTS `" + table.getTableName() + "`");

        return suggestion.toString();
    }

    /**
     * 创建问题记录
     */
    private InspectionIssue createIssue(Long recordId, InspectionRule rule, DataTable table) {
        InspectionIssue issue = new InspectionIssue();
        issue.setRecordId(recordId);
        issue.setClusterId(table.getClusterId());
        issue.setDbName(table.getDbName());
        issue.setIssueType(rule.getRuleType());
        issue.setSeverity(rule.getSeverity());
        issue.setResourceType("table");
        issue.setResourceId(table.getId());
        issue.setResourceName(table.getTableName());
        issue.setStatus("open");
        return issue;
    }

    /**
     * 解析规则配置
     */
    private Map<String, Object> parseRuleConfig(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.trim().isEmpty() || "{}".equals(ruleConfig.trim())) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(ruleConfig, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse rule config: {}", ruleConfig, e);
            return new HashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private void applyTableScope(LambdaQueryWrapper<DataTable> wrapper, Map<String, Object> ruleConfig) {
        if (wrapper == null || ruleConfig == null || ruleConfig.isEmpty()) {
            return;
        }

        Object scopeObj = ruleConfig.get("scope");
        if (!(scopeObj instanceof Map)) {
            return;
        }

        Map<String, Object> scope = (Map<String, Object>) scopeObj;

        List<Long> clusterIds = new ArrayList<>();
        clusterIds.addAll(toLongList(firstNonNull(scope, "clusterIds", "clusterId")));

        if (clusterIds.isEmpty()) {
            List<String> clusterNames = toStringList(firstNonNull(scope, "clusterNames", "clusterName", "dataSources", "dataSource"));
            for (String name : clusterNames) {
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                DorisCluster cluster = dorisClusterService.getByName(name.trim());
                if (cluster != null && cluster.getId() != null) {
                    clusterIds.add(cluster.getId());
                }
            }
        }

        List<String> dbNames = toStringList(firstNonNull(scope, "dbNames", "dbName", "schemas", "schema"));
        List<String> tableTypes = normalizeTableTypes(
            toStringList(firstNonNull(scope, "tableTypes", "tableType", "resourceTypes", "resourceType")));

        if (!clusterIds.isEmpty()) {
            wrapper.in(DataTable::getClusterId, clusterIds);
        }
        if (!dbNames.isEmpty()) {
            wrapper.in(DataTable::getDbName, dbNames);
        }
        if (!tableTypes.isEmpty()) {
            wrapper.in(DataTable::getTableType, tableTypes);
        }
    }

    private List<String> normalizeTableTypes(List<String> tableTypes) {
        if (tableTypes == null || tableTypes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String item : tableTypes) {
            if (!StringUtils.hasText(item)) {
                continue;
            }
            String value = item.trim().toUpperCase(Locale.ROOT);
            if ("TABLE".equals(value) || "BASE_TABLE".equals(value)) {
                normalized.add("BASE TABLE");
            } else {
                normalized.add(value);
            }
        }
        return normalized;
    }

    private Object firstNonNull(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private List<Long> toLongList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        if (value instanceof Number) {
            result.add(((Number) value).longValue());
            return result;
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            if (!str.isEmpty()) {
                try {
                    result.add(Long.parseLong(str));
                } catch (NumberFormatException ignore) {
                    // ignore invalid numbers
                }
            }
            return result;
        }
        if (value instanceof Collection) {
            for (Object item : (Collection<?>) value) {
                if (item instanceof Number) {
                    result.add(((Number) item).longValue());
                } else if (item instanceof String) {
                    String str = ((String) item).trim();
                    if (!str.isEmpty()) {
                        try {
                            result.add(Long.parseLong(str));
                        } catch (NumberFormatException ignore) {
                            // ignore invalid numbers
                        }
                    }
                }
            }
            return result;
        }
        return result;
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }
        if (value instanceof String) {
            String str = ((String) value).trim();
            return StringUtils.hasText(str) ? Collections.singletonList(str) : Collections.emptyList();
        }
        if (value instanceof Collection) {
            List<String> result = new ArrayList<>();
            for (Object item : (Collection<?>) value) {
                if (item == null) {
                    continue;
                }
                String str = String.valueOf(item).trim();
                if (StringUtils.hasText(str)) {
                    result.add(str);
                }
            }
            return result;
        }
        String str = String.valueOf(value).trim();
        return StringUtils.hasText(str) ? Collections.singletonList(str) : Collections.emptyList();
    }

    /**
     * 获取巡检记录列表
     */
    public List<InspectionRecord> getInspectionRecords(Integer limit) {
        LambdaQueryWrapper<InspectionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(InspectionRecord::getInspectionTime);
        if (limit != null && limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return inspectionRecordMapper.selectList(wrapper);
    }

    /**
     * 获取巡检问题列表
     */
    public List<InspectionIssue> getInspectionIssues(Long recordId, String status, String severity) {
        return getInspectionIssues(recordId, status, severity, null, null, null);
    }

    /**
     * 获取巡检问题列表(支持按数据源/Schema/表过滤)
     */
    public List<InspectionIssue> getInspectionIssues(Long recordId, String status, String severity,
                                                     Long clusterId, String dbName, String tableName) {
        LambdaQueryWrapper<InspectionIssue> wrapper = new LambdaQueryWrapper<>();
        if (recordId != null) {
            wrapper.eq(InspectionIssue::getRecordId, recordId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(InspectionIssue::getStatus, status);
        }
        if (severity != null && !severity.isEmpty()) {
            wrapper.eq(InspectionIssue::getSeverity, severity);
        }
        if (clusterId != null) {
            wrapper.eq(InspectionIssue::getClusterId, clusterId);
        }
        if (dbName != null && !dbName.trim().isEmpty()) {
            wrapper.eq(InspectionIssue::getDbName, dbName.trim());
        }
        if (tableName != null && !tableName.trim().isEmpty()) {
            wrapper.eq(InspectionIssue::getResourceType, "table");
            wrapper.eq(InspectionIssue::getResourceName, tableName.trim());
        }
        wrapper.orderByDesc(InspectionIssue::getCreatedTime);
        return inspectionIssueMapper.selectList(wrapper);
    }

    /**
     * 更新问题状态
     */
    @Transactional
    public void updateIssueStatus(Long issueId, String status, String resolvedBy, String resolutionNote) {
        InspectionIssue issue = inspectionIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("Issue not found: " + issueId);
        }

        issue.setStatus(status);
        if ("resolved".equals(status) || "ignored".equals(status)) {
            issue.setResolvedBy(resolvedBy);
            issue.setResolvedTime(LocalDateTime.now());
            issue.setResolutionNote(resolutionNote);
        }
        inspectionIssueMapper.updateById(issue);
    }

    /**
     * 一键修复问题（按问题类型执行）
     */
    @Transactional
    public Map<String, Object> fixIssue(Long issueId, String fixedBy) {
        InspectionIssue issue = inspectionIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("Issue not found: " + issueId);
        }
        if (!"open".equals(issue.getStatus())) {
            throw new IllegalArgumentException("仅待处理(open)问题支持一键修复");
        }

        if ("replica_count".equals(issue.getIssueType())) {
            return fixReplicaCountIssue(issue, fixedBy);
        }
        if (isTabletIssueType(issue.getIssueType())) {
            throw new IllegalArgumentException("tablet 相关问题仅提供修复方案与脚本，请先查看修复方案后手工执行");
        }

        throw new IllegalArgumentException("暂不支持该问题类型一键修复: " + issue.getIssueType());
    }

    /**
     * 获取问题修复方案
     */
    public Map<String, Object> getIssueFixPlan(Long issueId) {
        InspectionIssue issue = inspectionIssueMapper.selectById(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("Issue not found: " + issueId);
        }
        if ("replica_count".equals(issue.getIssueType())) {
            return buildReplicaIssueFixPlan(issue);
        }
        if (isTabletIssueType(issue.getIssueType())) {
            return buildTabletIssueFixPlan(issue);
        }
        throw new IllegalArgumentException("暂不支持该问题类型修复方案: " + issue.getIssueType());
    }

    private boolean isTabletIssueType(String issueType) {
        return "tablet_count".equals(issueType) || "tablet_size".equals(issueType);
    }

    private Map<String, Object> fixReplicaCountIssue(InspectionIssue issue, String fixedBy) {
        IssueTableContext context = resolveIssueTableContext(issue);

        int targetReplicaNum = resolveTargetReplicaNum(issue);
        dorisConnectionService.setReplicationNum(context.getClusterId(), context.getDatabase(), context.getTableName(), targetReplicaNum);

        DataTable updateTable = new DataTable();
        updateTable.setId(context.getTable().getId());
        updateTable.setReplicaNum(targetReplicaNum);
        dataTableMapper.updateById(updateTable);

        String operator = StringUtils.hasText(fixedBy) ? fixedBy.trim() : "system";
        tableMetadataVersionService.captureVersion(context.getTable().getId(),
                TableMetadataVersionService.TRIGGER_INSPECTION_FIX, operator);
        issue.setStatus("resolved");
        issue.setResolvedBy(operator);
        issue.setResolvedTime(LocalDateTime.now());
        issue.setResolutionNote(String.format("一键修复：副本数已调整为 %d", targetReplicaNum));
        issue.setCurrentValue(String.valueOf(targetReplicaNum));
        inspectionIssueMapper.updateById(issue);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("issueId", issue.getId());
        result.put("issueType", issue.getIssueType());
        result.put("dbName", context.getDatabase());
        result.put("tableName", context.getTableName());
        result.put("targetReplicaNum", targetReplicaNum);
        return result;
    }

    private Map<String, Object> buildReplicaIssueFixPlan(InspectionIssue issue) {
        IssueTableContext context = resolveIssueTableContext(issue);
        int targetReplicaNum = resolveTargetReplicaNum(issue);

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("replicaNum", context.getTable().getReplicaNum());

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("replicaNum", targetReplicaNum);

        List<String> sqls = Collections.singletonList(String.format(
            "ALTER TABLE `%s`.`%s` SET (\"replication_num\" = \"%d\")",
            context.getDatabase(), context.getTableName(), targetReplicaNum));
        List<String> solutions = Collections.singletonList("直接调整副本数，适用于副本不足或副本过多场景");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueId", issue.getId());
        result.put("issueType", issue.getIssueType());
        result.put("clusterId", context.getClusterId());
        result.put("dbName", context.getDatabase());
        result.put("tableName", context.getTableName());
        result.put("strategy", "set_replication_num");
        result.put("autoFixable", true);
        result.put("current", current);
        result.put("target", target);
        result.put("officialRecommendations", Collections.singletonList("生产环境推荐副本数通常不少于 3（结合集群规模）"));
        result.put("solutions", solutions);
        result.put("sqls", sqls);
        return result;
    }

    private Map<String, Object> buildTabletIssueFixPlan(InspectionIssue issue) {
        IssueTableContext context = resolveIssueTableContext(issue);
        Map<String, Object> createInfo = dorisConnectionService.getTableCreateInfo(
            context.getClusterId(), context.getDatabase(), context.getTableName());
        String createSql = toStringValue(createInfo.get("createTableSql"));
        Map<String, String> properties = DorisCreateTableUtils.extractProperties(createSql);
        boolean dynamicPartitionEnabled = "true".equalsIgnoreCase(properties.get("dynamic_partition.enable"));

        Optional<DorisConnectionService.TableTabletStats> tabletStatsOptional = dorisConnectionService.getTableTabletStats(
            context.getClusterId(), context.getDatabase(), context.getTableName());
        if (!tabletStatsOptional.isPresent()) {
            throw new IllegalArgumentException("无法获取真实 Tablet 统计信息，请确认 Doris SHOW TABLETS 权限");
        }
        DorisConnectionService.TableTabletStats tabletStats = tabletStatsOptional.get();
        long tabletCount = Math.max(1L, tabletStats.getTabletCount());
        long totalDataSizeBytes = Math.max(1L, tabletStats.getTotalDataSizeBytes());
        long avgTabletSizeBytes = Math.max(1L, tabletStats.getAvgTabletSizeBytes());

        int currentBucketNum = safePositiveInt(createInfo.get("bucketNum"),
            context.getTable().getBucketNum() != null ? context.getTable().getBucketNum() : 1);
        int estimatedPartitionCount = (int) Math.max(1L,
            (long) Math.ceil((double) tabletCount / Math.max(1, currentBucketNum)));

        Map<String, Object> tabletSizeRule = loadRuleConfig("tablet_size");
        int minTabletSizeMb = ((Number) tabletSizeRule.getOrDefault("minTabletSizeMb", 1024)).intValue();
        int maxTabletSizeMb = ((Number) tabletSizeRule.getOrDefault("maxTabletSizeMb", 10240)).intValue();
        int targetTabletSizeMb = ((Number) tabletSizeRule.getOrDefault("targetTabletSizeMb", 4096)).intValue();

        long minTabletBytes = minTabletSizeMb * 1024L * 1024L;
        long maxTabletBytes = maxTabletSizeMb * 1024L * 1024L;
        long targetTabletBytes = targetTabletSizeMb * 1024L * 1024L;
        long recommendedTabletCountBySize = Math.max(1L, (totalDataSizeBytes + targetTabletBytes - 1) / targetTabletBytes);

        Map<String, Object> tabletCountRule = loadRuleConfig("tablet_count");
        int maxTablets = ((Number) tabletCountRule.getOrDefault("maxTablets", 200)).intValue();
        int warningTablets = ((Number) tabletCountRule.getOrDefault("warningTablets", 100)).intValue();

        long targetTabletCount = recommendedTabletCountBySize;
        if ("tablet_count".equals(issue.getIssueType()) && tabletCount > maxTablets) {
            targetTabletCount = Math.min(tabletCount, maxTablets);
        } else if ("tablet_count".equals(issue.getIssueType()) && tabletCount > warningTablets) {
            targetTabletCount = Math.min(tabletCount, warningTablets);
        }

        int targetBucketNum = (int) Math.max(1L,
            (targetTabletCount + Math.max(1, estimatedPartitionCount) - 1) / Math.max(1, estimatedPartitionCount));
        if (targetBucketNum == currentBucketNum) {
            if (avgTabletSizeBytes > maxTabletBytes) {
                targetBucketNum = currentBucketNum + 1;
            } else if (avgTabletSizeBytes < minTabletBytes && currentBucketNum > 1) {
                targetBucketNum = currentBucketNum - 1;
            }
        }

        String distributionColumn = toStringValue(createInfo.get("distributionColumn"));
        String partitionColumn = toStringValue(createInfo.get("partitionColumn"));
        String partitionMode = resolvePartitionMode(dynamicPartitionEnabled, createSql);

        List<String> officialRecommendations = Arrays.asList(
            "单 Tablet 大小建议控制在 1GB~10GB 区间",
            "优先通过分桶(BUCKETS)与分区策略联合控制 Tablet 数量",
            "动态分区适合时间序列滚动数据，静态分区适合固定分区模型");

        List<String> sqls = new ArrayList<>();
        List<String> solutions = new ArrayList<>();
        String strategy;
        String modeRecommendation = "keep_current_mode";

        boolean likelyTimeSeries = isLikelyTimeSeriesPartitionColumn(partitionColumn);

        if (dynamicPartitionEnabled) {
            strategy = "adjust_dynamic_partition_buckets";
            sqls.add(String.format(
                "ALTER TABLE `%s`.`%s` SET (\"dynamic_partition.buckets\" = \"%d\")",
                context.getDatabase(), context.getTableName(), targetBucketNum));
            solutions.add("动态分区表优先调整 dynamic_partition.buckets，影响新生成分区（建议评估后手工执行）");
            if (!likelyTimeSeries) {
                modeRecommendation = "dynamic_to_static";
                solutions.add("如果业务不是时间序列且分区固定，可评估迁移为静态分区");
            }
        } else {
            boolean hasPartition = StringUtils.hasText(createSql)
                && createSql.toUpperCase(Locale.ROOT).contains("PARTITION BY");
            if (hasPartition && likelyTimeSeries) {
                modeRecommendation = "static_to_dynamic";
            }
            if (!hasPartition || estimatedPartitionCount <= 1) {
                strategy = "adjust_distribution_buckets";
                String hashColumns = normalizeHashColumns(distributionColumn);
                if (StringUtils.hasText(hashColumns)) {
                    sqls.add(String.format(
                        "ALTER TABLE `%s`.`%s` MODIFY DISTRIBUTION DISTRIBUTED BY HASH(%s) BUCKETS %d",
                        context.getDatabase(), context.getTableName(), hashColumns, targetBucketNum));
                    solutions.add("非动态分区且分区较少时，可直接调整表分桶数（建议评估后手工执行）");
                } else {
                    solutions.add("当前表无法解析 HASH 分桶列，请先确认分桶策略后手工调整");
                }
            } else {
                strategy = "partition_mode_migration";
                solutions.add("当前为非动态分区且分区数量较多，建议评估是否迁移为动态分区");
                solutions.add("若需保持固定分区模型，则保留静态分区并重新评估 BUCKETS");
                sqls.add(String.format("-- 静态 -> 动态迁移示例: CREATE TABLE `%s`.`%s_new` LIKE `%s`.`%s`",
                    context.getDatabase(), context.getTableName(), context.getDatabase(), context.getTableName()));
                sqls.add(String.format("-- ALTER TABLE `%s`.`%s_new` SET (\"dynamic_partition.enable\"=\"true\", \"dynamic_partition.buckets\"=\"%d\")",
                    context.getDatabase(), context.getTableName(), targetBucketNum));
                sqls.add(String.format("-- INSERT INTO `%s`.`%s_new` SELECT * FROM `%s`.`%s`",
                    context.getDatabase(), context.getTableName(), context.getDatabase(), context.getTableName()));
            }
        }

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("tabletCount", tabletCount);
        current.put("totalDataSizeBytes", totalDataSizeBytes);
        current.put("totalDataSizeReadable", formatBytes(totalDataSizeBytes));
        current.put("avgTabletSizeBytes", avgTabletSizeBytes);
        current.put("avgTabletSizeReadable", formatBytes(avgTabletSizeBytes));
        current.put("bucketNum", currentBucketNum);
        current.put("estimatedPartitionCount", estimatedPartitionCount);
        current.put("partitionMode", partitionMode);
        current.put("dynamicPartitionEnabled", dynamicPartitionEnabled);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetTabletCount", targetTabletCount);
        target.put("targetBucketNum", targetBucketNum);
        target.put("tabletSizeRange", formatBytes(minTabletBytes) + " ~ " + formatBytes(maxTabletBytes));
        target.put("targetTabletSize", formatBytes(targetTabletBytes));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueId", issue.getId());
        result.put("issueType", issue.getIssueType());
        result.put("clusterId", context.getClusterId());
        result.put("dbName", context.getDatabase());
        result.put("tableName", context.getTableName());
        result.put("strategy", strategy);
        result.put("autoFixable", false);
        result.put("modeRecommendation", modeRecommendation);
        result.put("current", current);
        result.put("target", target);
        result.put("targetBucketNum", targetBucketNum);
        result.put("officialRecommendations", officialRecommendations);
        result.put("solutions", solutions);
        result.put("sqls", sqls);
        return result;
    }

    private String resolvePartitionMode(boolean dynamicPartitionEnabled, String createSql) {
        if (dynamicPartitionEnabled) {
            return "dynamic_partition";
        }
        if (!StringUtils.hasText(createSql)) {
            return "unknown";
        }
        return createSql.toUpperCase(Locale.ROOT).contains("PARTITION BY") ? "static_partition" : "single_partition";
    }

    private boolean isLikelyTimeSeriesPartitionColumn(String partitionColumn) {
        if (!StringUtils.hasText(partitionColumn)) {
            return false;
        }
        String normalized = partitionColumn.toLowerCase(Locale.ROOT);
        return normalized.contains("dt") || normalized.contains("date") || normalized.contains("time")
            || normalized.contains("day");
    }

    private String normalizeHashColumns(String distributionColumn) {
        if (!StringUtils.hasText(distributionColumn)) {
            return "";
        }
        String[] cols = distributionColumn.split(",");
        List<String> wrapped = new ArrayList<>();
        for (String col : cols) {
            if (!StringUtils.hasText(col)) {
                continue;
            }
            String trimmed = col.trim().replace("`", "");
            if (StringUtils.hasText(trimmed)) {
                wrapped.add("`" + trimmed + "`");
            }
        }
        return String.join(", ", wrapped);
    }

    private Map<String, Object> loadRuleConfig(String ruleType) {
        InspectionRule rule = inspectionRuleMapper.selectOne(new LambdaQueryWrapper<InspectionRule>()
            .eq(InspectionRule::getRuleType, ruleType)
            .orderByDesc(InspectionRule::getId)
            .last("LIMIT 1"));
        if (rule == null || !StringUtils.hasText(rule.getRuleConfig())) {
            return new HashMap<>();
        }
        return parseRuleConfig(rule.getRuleConfig());
    }

    private IssueTableContext resolveIssueTableContext(InspectionIssue issue) {
        if (!"table".equals(issue.getResourceType()) || issue.getResourceId() == null) {
            throw new IllegalArgumentException("问题缺少表资源信息");
        }
        DataTable table = dataTableMapper.selectById(issue.getResourceId());
        if (table == null) {
            throw new IllegalArgumentException("关联表不存在: " + issue.getResourceId());
        }

        Long clusterId = issue.getClusterId() != null ? issue.getClusterId() : table.getClusterId();
        if (clusterId == null) {
            throw new IllegalArgumentException("缺少数据源(clusterId)");
        }

        String database = StringUtils.hasText(issue.getDbName()) ? issue.getDbName().trim() : table.getDbName();
        if (!StringUtils.hasText(database)) {
            throw new IllegalArgumentException("缺少数据库名(dbName)");
        }

        String tableName = resolveActualTableName(table.getTableName());
        if (!StringUtils.hasText(tableName) && StringUtils.hasText(issue.getResourceName())) {
            tableName = issue.getResourceName().trim();
        }
        if (!StringUtils.hasText(tableName)) {
            throw new IllegalArgumentException("缺少表名");
        }
        return new IssueTableContext(table, clusterId, database, tableName);
    }

    private int safePositiveInt(Object value, int defaultValue) {
        Integer parsed = toInteger(value);
        if (parsed == null || parsed <= 0) {
            return Math.max(1, defaultValue);
        }
        return parsed;
    }

    private Integer toInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private static class IssueTableContext {
        private final DataTable table;
        private final Long clusterId;
        private final String database;
        private final String tableName;

        private IssueTableContext(DataTable table, Long clusterId, String database, String tableName) {
            this.table = table;
            this.clusterId = clusterId;
            this.database = database;
            this.tableName = tableName;
        }

        private DataTable getTable() {
            return table;
        }

        private Long getClusterId() {
            return clusterId;
        }

        private String getDatabase() {
            return database;
        }

        private String getTableName() {
            return tableName;
        }
    }

    private int resolveTargetReplicaNum(InspectionIssue issue) {
        InspectionRule replicaRule = inspectionRuleMapper.selectOne(new LambdaQueryWrapper<InspectionRule>()
            .eq(InspectionRule::getRuleType, "replica_count")
            .orderByDesc(InspectionRule::getId)
            .last("LIMIT 1"));
        if (replicaRule != null && StringUtils.hasText(replicaRule.getRuleConfig())) {
            Map<String, Object> ruleConfig = parseRuleConfig(replicaRule.getRuleConfig());
            Object recommended = ruleConfig.get("recommendedReplicas");
            if (recommended instanceof Number && ((Number) recommended).intValue() > 0) {
                return ((Number) recommended).intValue();
            }
            Object minReplicas = ruleConfig.get("minReplicas");
            if (minReplicas instanceof Number && ((Number) minReplicas).intValue() > 0) {
                return ((Number) minReplicas).intValue();
            }
        }

        Integer recommendedFromExpected = extractFirstPositiveInt(issue.getExpectedValue(), RECOMMENDED_REPLICA_PATTERN, 1);
        if (recommendedFromExpected != null) {
            return recommendedFromExpected;
        }
        Integer maxFromRange = extractFirstPositiveInt(issue.getExpectedValue(), RANGE_REPLICA_PATTERN, 2);
        if (maxFromRange != null) {
            return maxFromRange;
        }
        Integer minFromExpected = extractFirstPositiveInt(issue.getExpectedValue(), MIN_REPLICA_PATTERN, 1);
        if (minFromExpected != null) {
            return minFromExpected;
        }
        Integer fromSuggestion = extractFirstPositiveInt(issue.getSuggestion(), RECOMMENDED_REPLICA_PATTERN, 1);
        if (fromSuggestion != null) {
            return fromSuggestion;
        }

        return 3;
    }

    private Integer extractFirstPositiveInt(String text, Pattern pattern, int group) {
        if (!StringUtils.hasText(text) || pattern == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        if (group > matcher.groupCount()) {
            return null;
        }
        String value = matcher.group(group);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取巡检规则列表
     */
    public List<InspectionRule> getInspectionRules(Boolean enabled) {
        LambdaQueryWrapper<InspectionRule> wrapper = new LambdaQueryWrapper<>();
        if (enabled != null) {
            wrapper.eq(InspectionRule::getEnabled, enabled);
        }
        wrapper.orderByAsc(InspectionRule::getRuleType).orderByAsc(InspectionRule::getId);
        return inspectionRuleMapper.selectList(wrapper);
    }

    /**
     * 更新巡检规则启用状态
     */
    @Transactional
    public void updateRuleEnabled(Long ruleId, Boolean enabled) {
        if (ruleId == null) {
            throw new IllegalArgumentException("ruleId is required");
        }
        if (enabled == null) {
            throw new IllegalArgumentException("enabled is required");
        }

        InspectionRule rule = inspectionRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }

        rule.setEnabled(enabled);
        inspectionRuleMapper.updateById(rule);
    }
}
