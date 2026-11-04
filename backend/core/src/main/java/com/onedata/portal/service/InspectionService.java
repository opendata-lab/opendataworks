package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.*;
import com.onedata.portal.mapper.*;
import com.onedata.portal.service.inspection.InspectionRuleRegistry;
import com.onedata.portal.service.inspection.InspectionSupport;
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
 * 巡检服务 - 检查数据表和任务的合规性。
 *
 * <p>规则分发已注册表化：{@code runFullInspection} 遍历启用规则后交由 {@link InspectionRuleRegistry} 按 {@code
 * ruleType} 分发到各 {@code InspectionRuleHandler}。 本服务保留巡检入口编排、问题修复链路与记录/问题/规则查询接口；共享辅助逻辑统一复用 {@link
 * InspectionSupport}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionService {
    private final com.onedata.portal.service.table.DorisTableEngineHandler dorisTableEngineHandler;

    private final InspectionRecordMapper inspectionRecordMapper;
    private final InspectionIssueMapper inspectionIssueMapper;
    private final InspectionRuleMapper inspectionRuleMapper;
    private final DataTableMapper dataTableMapper;
    private final DorisConnectionService dorisConnectionService;
    private final TableMetadataVersionService tableMetadataVersionService;
    private final InspectionRuleRegistry ruleRegistry;
    private final InspectionSupport support;

    private static final Pattern RECOMMENDED_REPLICA_PATTERN =
            Pattern.compile("推荐\\s*[:：]?\\s*(\\d+)");
    private static final Pattern RANGE_REPLICA_PATTERN = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)");
    private static final Pattern MIN_REPLICA_PATTERN = Pattern.compile(">=\\s*(\\d+)");

    /** 执行全量巡检 */
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
            List<InspectionRule> rules =
                    inspectionRuleMapper.selectList(
                            new LambdaQueryWrapper<InspectionRule>()
                                    .eq(InspectionRule::getEnabled, true));

            for (InspectionRule rule : rules) {
                List<InspectionIssue> issues = ruleRegistry.dispatch(record.getId(), rule);
                totalIssues += issues.size();
            }

            // 更新巡检记录
            record.setStatus("completed");
            record.setIssueCount(totalIssues);
            record.setDurationSeconds(
                    (int) Duration.between(startTime, LocalDateTime.now()).getSeconds());
            inspectionRecordMapper.updateById(record);

            log.info(
                    "Inspection completed: recordId={}, issues={}, duration={}s",
                    record.getId(),
                    totalIssues,
                    record.getDurationSeconds());

        } catch (Exception e) {
            log.error("Inspection failed: recordId={}", record.getId(), e);
            record.setStatus("failed");
            record.setDurationSeconds(
                    (int) Duration.between(startTime, LocalDateTime.now()).getSeconds());
            inspectionRecordMapper.updateById(record);
            throw new RuntimeException("Inspection failed", e);
        }

        return record;
    }

    /** 获取巡检记录列表 */
    public List<InspectionRecord> getInspectionRecords(Integer limit) {
        LambdaQueryWrapper<InspectionRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(InspectionRecord::getInspectionTime);
        if (limit != null && limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return inspectionRecordMapper.selectList(wrapper);
    }

    /** 获取巡检问题列表 */
    public List<InspectionIssue> getInspectionIssues(
            Long recordId, String status, String severity) {
        return getInspectionIssues(recordId, status, severity, null, null, null);
    }

    /** 获取巡检问题列表(支持按数据源/Schema/表过滤) */
    public List<InspectionIssue> getInspectionIssues(
            Long recordId,
            String status,
            String severity,
            Long clusterId,
            String dbName,
            String tableName) {
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

    /** 更新问题状态 */
    @Transactional
    public void updateIssueStatus(
            Long issueId, String status, String resolvedBy, String resolutionNote) {
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

    /** 一键修复问题（按问题类型执行） */
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

    /** 获取问题修复方案 */
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
        dorisTableEngineHandler.setReplicationNumAsSystem(
                context.getClusterId(),
                context.getDatabase(),
                context.getTableName(),
                targetReplicaNum);

        DataTable updateTable = new DataTable();
        updateTable.setId(context.getTable().getId());
        updateTable.setReplicaNum(targetReplicaNum);
        dataTableMapper.updateById(updateTable);

        String operator = StringUtils.hasText(fixedBy) ? fixedBy.trim() : "system";
        tableMetadataVersionService.captureVersion(
                context.getTable().getId(),
                TableMetadataVersionService.TRIGGER_INSPECTION_FIX,
                operator);
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

        List<String> sqls =
                Collections.singletonList(
                        String.format(
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
        result.put(
                "officialRecommendations", Collections.singletonList("生产环境推荐副本数通常不少于 3（结合集群规模）"));
        result.put("solutions", solutions);
        result.put("sqls", sqls);
        return result;
    }

    private Map<String, Object> buildTabletIssueFixPlan(InspectionIssue issue) {
        IssueTableContext context = resolveIssueTableContext(issue);
        Map<String, Object> createInfo =
                dorisConnectionService.getTableCreateInfo(
                        context.getClusterId(), context.getDatabase(), context.getTableName());
        String createSql = toStringValue(createInfo.get("createTableSql"));
        Map<String, String> properties = DorisCreateTableUtils.extractProperties(createSql);
        boolean dynamicPartitionEnabled =
                "true".equalsIgnoreCase(properties.get("dynamic_partition.enable"));

        Optional<DorisConnectionService.TableTabletStats> tabletStatsOptional =
                dorisConnectionService.getTableTabletStats(
                        context.getClusterId(), context.getDatabase(), context.getTableName());
        if (!tabletStatsOptional.isPresent()) {
            throw new IllegalArgumentException("无法获取真实 Tablet 统计信息，请确认 Doris SHOW TABLETS 权限");
        }
        DorisConnectionService.TableTabletStats tabletStats = tabletStatsOptional.get();
        long tabletCount = Math.max(1L, tabletStats.getTabletCount());
        long totalDataSizeBytes = Math.max(1L, tabletStats.getTotalDataSizeBytes());
        long avgTabletSizeBytes = Math.max(1L, tabletStats.getAvgTabletSizeBytes());

        int currentBucketNum =
                safePositiveInt(
                        createInfo.get("bucketNum"),
                        context.getTable().getBucketNum() != null
                                ? context.getTable().getBucketNum()
                                : 1);
        int estimatedPartitionCount =
                (int)
                        Math.max(
                                1L,
                                (long)
                                        Math.ceil(
                                                (double) tabletCount
                                                        / Math.max(1, currentBucketNum)));

        Map<String, Object> tabletSizeRule = loadRuleConfig("tablet_size");
        int minTabletSizeMb =
                ((Number) tabletSizeRule.getOrDefault("minTabletSizeMb", 1024)).intValue();
        int maxTabletSizeMb =
                ((Number) tabletSizeRule.getOrDefault("maxTabletSizeMb", 10240)).intValue();
        int targetTabletSizeMb =
                ((Number) tabletSizeRule.getOrDefault("targetTabletSizeMb", 4096)).intValue();

        long minTabletBytes = minTabletSizeMb * 1024L * 1024L;
        long maxTabletBytes = maxTabletSizeMb * 1024L * 1024L;
        long targetTabletBytes = targetTabletSizeMb * 1024L * 1024L;
        long recommendedTabletCountBySize =
                Math.max(1L, (totalDataSizeBytes + targetTabletBytes - 1) / targetTabletBytes);

        Map<String, Object> tabletCountRule = loadRuleConfig("tablet_count");
        int maxTablets = ((Number) tabletCountRule.getOrDefault("maxTablets", 200)).intValue();
        int warningTablets =
                ((Number) tabletCountRule.getOrDefault("warningTablets", 100)).intValue();

        long targetTabletCount = recommendedTabletCountBySize;
        if ("tablet_count".equals(issue.getIssueType()) && tabletCount > maxTablets) {
            targetTabletCount = Math.min(tabletCount, maxTablets);
        } else if ("tablet_count".equals(issue.getIssueType()) && tabletCount > warningTablets) {
            targetTabletCount = Math.min(tabletCount, warningTablets);
        }

        int targetBucketNum =
                (int)
                        Math.max(
                                1L,
                                (targetTabletCount + Math.max(1, estimatedPartitionCount) - 1)
                                        / Math.max(1, estimatedPartitionCount));
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

        List<String> officialRecommendations =
                Arrays.asList(
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
            sqls.add(
                    String.format(
                            "ALTER TABLE `%s`.`%s` SET (\"dynamic_partition.buckets\" = \"%d\")",
                            context.getDatabase(), context.getTableName(), targetBucketNum));
            solutions.add("动态分区表优先调整 dynamic_partition.buckets，影响新生成分区（建议评估后手工执行）");
            if (!likelyTimeSeries) {
                modeRecommendation = "dynamic_to_static";
                solutions.add("如果业务不是时间序列且分区固定，可评估迁移为静态分区");
            }
        } else {
            boolean hasPartition =
                    StringUtils.hasText(createSql)
                            && createSql.toUpperCase(Locale.ROOT).contains("PARTITION BY");
            if (hasPartition && likelyTimeSeries) {
                modeRecommendation = "static_to_dynamic";
            }
            if (!hasPartition || estimatedPartitionCount <= 1) {
                strategy = "adjust_distribution_buckets";
                String hashColumns = normalizeHashColumns(distributionColumn);
                if (StringUtils.hasText(hashColumns)) {
                    sqls.add(
                            String.format(
                                    "ALTER TABLE `%s`.`%s` MODIFY DISTRIBUTION DISTRIBUTED BY"
                                        + " HASH(%s) BUCKETS %d",
                                    context.getDatabase(),
                                    context.getTableName(),
                                    hashColumns,
                                    targetBucketNum));
                    solutions.add("非动态分区且分区较少时，可直接调整表分桶数（建议评估后手工执行）");
                } else {
                    solutions.add("当前表无法解析 HASH 分桶列，请先确认分桶策略后手工调整");
                }
            } else {
                strategy = "partition_mode_migration";
                solutions.add("当前为非动态分区且分区数量较多，建议评估是否迁移为动态分区");
                solutions.add("若需保持固定分区模型，则保留静态分区并重新评估 BUCKETS");
                sqls.add(
                        String.format(
                                "-- 静态 -> 动态迁移示例: CREATE TABLE `%s`.`%s_new` LIKE `%s`.`%s`",
                                context.getDatabase(),
                                context.getTableName(),
                                context.getDatabase(),
                                context.getTableName()));
                sqls.add(
                        String.format(
                                "-- ALTER TABLE `%s`.`%s_new` SET"
                                    + " (\"dynamic_partition.enable\"=\"true\","
                                    + " \"dynamic_partition.buckets\"=\"%d\")",
                                context.getDatabase(), context.getTableName(), targetBucketNum));
                sqls.add(
                        String.format(
                                "-- INSERT INTO `%s`.`%s_new` SELECT * FROM `%s`.`%s`",
                                context.getDatabase(),
                                context.getTableName(),
                                context.getDatabase(),
                                context.getTableName()));
            }
        }

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("tabletCount", tabletCount);
        current.put("totalDataSizeBytes", totalDataSizeBytes);
        current.put("totalDataSizeReadable", support.formatBytes(totalDataSizeBytes));
        current.put("avgTabletSizeBytes", avgTabletSizeBytes);
        current.put("avgTabletSizeReadable", support.formatBytes(avgTabletSizeBytes));
        current.put("bucketNum", currentBucketNum);
        current.put("estimatedPartitionCount", estimatedPartitionCount);
        current.put("partitionMode", partitionMode);
        current.put("dynamicPartitionEnabled", dynamicPartitionEnabled);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("targetTabletCount", targetTabletCount);
        target.put("targetBucketNum", targetBucketNum);
        target.put(
                "tabletSizeRange",
                support.formatBytes(minTabletBytes) + " ~ " + support.formatBytes(maxTabletBytes));
        target.put("targetTabletSize", support.formatBytes(targetTabletBytes));

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
        return createSql.toUpperCase(Locale.ROOT).contains("PARTITION BY")
                ? "static_partition"
                : "single_partition";
    }

    private boolean isLikelyTimeSeriesPartitionColumn(String partitionColumn) {
        if (!StringUtils.hasText(partitionColumn)) {
            return false;
        }
        String normalized = partitionColumn.toLowerCase(Locale.ROOT);
        return normalized.contains("dt")
                || normalized.contains("date")
                || normalized.contains("time")
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
        InspectionRule rule =
                inspectionRuleMapper.selectOne(
                        new LambdaQueryWrapper<InspectionRule>()
                                .eq(InspectionRule::getRuleType, ruleType)
                                .orderByDesc(InspectionRule::getId)
                                .last("LIMIT 1"));
        if (rule == null || !StringUtils.hasText(rule.getRuleConfig())) {
            return new HashMap<>();
        }
        return support.parseRuleConfig(rule.getRuleConfig());
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

        String database =
                StringUtils.hasText(issue.getDbName())
                        ? issue.getDbName().trim()
                        : table.getDbName();
        if (!StringUtils.hasText(database)) {
            throw new IllegalArgumentException("缺少数据库名(dbName)");
        }

        String tableName = support.resolveActualTableName(table.getTableName());
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

        private IssueTableContext(
                DataTable table, Long clusterId, String database, String tableName) {
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
        InspectionRule replicaRule =
                inspectionRuleMapper.selectOne(
                        new LambdaQueryWrapper<InspectionRule>()
                                .eq(InspectionRule::getRuleType, "replica_count")
                                .orderByDesc(InspectionRule::getId)
                                .last("LIMIT 1"));
        if (replicaRule != null && StringUtils.hasText(replicaRule.getRuleConfig())) {
            Map<String, Object> ruleConfig = support.parseRuleConfig(replicaRule.getRuleConfig());
            Object recommended = ruleConfig.get("recommendedReplicas");
            if (recommended instanceof Number && ((Number) recommended).intValue() > 0) {
                return ((Number) recommended).intValue();
            }
            Object minReplicas = ruleConfig.get("minReplicas");
            if (minReplicas instanceof Number && ((Number) minReplicas).intValue() > 0) {
                return ((Number) minReplicas).intValue();
            }
        }

        Integer recommendedFromExpected =
                extractFirstPositiveInt(issue.getExpectedValue(), RECOMMENDED_REPLICA_PATTERN, 1);
        if (recommendedFromExpected != null) {
            return recommendedFromExpected;
        }
        Integer maxFromRange =
                extractFirstPositiveInt(issue.getExpectedValue(), RANGE_REPLICA_PATTERN, 2);
        if (maxFromRange != null) {
            return maxFromRange;
        }
        Integer minFromExpected =
                extractFirstPositiveInt(issue.getExpectedValue(), MIN_REPLICA_PATTERN, 1);
        if (minFromExpected != null) {
            return minFromExpected;
        }
        Integer fromSuggestion =
                extractFirstPositiveInt(issue.getSuggestion(), RECOMMENDED_REPLICA_PATTERN, 1);
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

    /** 获取巡检规则列表 */
    public List<InspectionRule> getInspectionRules(Boolean enabled) {
        LambdaQueryWrapper<InspectionRule> wrapper = new LambdaQueryWrapper<>();
        if (enabled != null) {
            wrapper.eq(InspectionRule::getEnabled, enabled);
        }
        wrapper.orderByAsc(InspectionRule::getRuleType).orderByAsc(InspectionRule::getId);
        return inspectionRuleMapper.selectList(wrapper);
    }

    /** 更新巡检规则启用状态 */
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

    /** 更新规则配置。ruleType 与 ruleCode 不可改；仅覆盖非空字段。 */
    @Transactional
    public void updateRule(
            Long ruleId, String ruleName, String description, String severity, String ruleConfig) {
        if (ruleId == null) {
            throw new IllegalArgumentException("ruleId is required");
        }
        InspectionRule rule = inspectionRuleMapper.selectById(ruleId);
        if (rule == null) {
            throw new IllegalArgumentException("Rule not found: " + ruleId);
        }
        if (StringUtils.hasText(ruleName)) {
            rule.setRuleName(ruleName);
        }
        if (description != null) {
            rule.setDescription(description);
        }
        if (StringUtils.hasText(severity)) {
            rule.setSeverity(severity);
        }
        if (ruleConfig != null) {
            rule.setRuleConfig(ruleConfig);
        }
        inspectionRuleMapper.updateById(rule);
    }
}
