package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.InspectionIssueMapper;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 巡检规则共享支撑。
 *
 * <p>承载被多个规则 handler 复用的辅助逻辑（问题构造与入库、规则配置解析、表范围过滤、
 * 视图判定、Doris 集群解析、血缘判定、字节格式化等）。方法体由原 {@code InspectionService}
 * 逐字迁移，保持行为不变。规则**专属**的辅助逻辑不放在此处，随对应 handler 维护。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionSupport {

    private final DatasourceAdapterRegistry adapterRegistry;
    private final InspectionIssueMapper inspectionIssueMapper;
    private final DataLineageMapper dataLineageMapper;
    private final DorisClusterService dorisClusterService;
    private final ObjectMapper objectMapper;

    private static final Pattern CREATE_VIEW_DDL_PATTERN = Pattern.compile(
        "^CREATE\\s+(OR\\s+REPLACE\\s+)?VIEW\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * 持久化问题记录（统一入库入口）。
     */
    public void insertIssue(InspectionIssue issue) {
        inspectionIssueMapper.insert(issue);
    }

    /**
     * 创建问题记录
     */
    public InspectionIssue createIssue(Long recordId, InspectionRule rule, DataTable table) {
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
    public Map<String, Object> parseRuleConfig(String ruleConfig) {
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
    public void applyTableScope(LambdaQueryWrapper<DataTable> wrapper, Map<String, Object> ruleConfig) {
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

    public String resolveActualTableName(String tableName) {
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

    public Set<Long> resolveDorisClusterIds() {
        List<DorisCluster> clusters = dorisClusterService.listAll();
        Set<Long> dorisClusterIds = new HashSet<>();
        for (DorisCluster cluster : clusters) {
            if (cluster == null || cluster.getId() == null) {
                continue;
            }
            if (adapterRegistry.require(cluster.getSourceType()).descriptor().supports("tabletInspection")) {
                dorisClusterIds.add(cluster.getId());
            }
        }
        return dorisClusterIds;
    }

    public boolean isViewTable(DataTable table) {
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

    /**
     * 格式化字节数
     */
    public String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 检查表是否有上游血缘关系
     */
    public boolean hasUpstreamLineage(Long tableId) {
        return dataLineageMapper.selectCount(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getDownstreamTableId, tableId)
        ) > 0;
    }

    /**
     * 检查表是否有下游血缘关系
     */
    public boolean hasDownstreamLineage(Long tableId) {
        return dataLineageMapper.selectCount(
            new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getUpstreamTableId, tableId)
        ) > 0;
    }
}
