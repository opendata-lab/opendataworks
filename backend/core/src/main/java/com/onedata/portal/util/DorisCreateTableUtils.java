package com.onedata.portal.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;



/**
 * Doris SHOW CREATE TABLE SQL 解析工具
 */
public final class DorisCreateTableUtils {

    private static final Pattern PROPERTY_PATTERN = Pattern.compile("\"([^\"]+)\"\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ALLOCATION_REPLICA_PATTERN = Pattern.compile(":\\s*(\\d+)");
    private static final Pattern PARTITION_BY_PATTERN = Pattern.compile("(?is)PARTITION\\s+BY\\b");
    private static final Pattern ESCAPED_BACKTICK_PATTERN = Pattern.compile("\\\\+`");

    private DorisCreateTableUtils() {
    }

    /**
     * 从 SHOW CREATE TABLE 的 SQL 文本中提取 PROPERTIES 键值对。
     * <p>
     * Doris 的建表语句中通常使用双引号表示属性项，如：
     * {@code "replication_allocation" = "tag.location.default: 3"}
     */
    public static Map<String, String> extractProperties(String createTableSql) {
        Map<String, String> properties = new HashMap<>();
        if (!StringUtils.hasText(createTableSql)) {
            return properties;
        }
        Matcher matcher = PROPERTY_PATTERN.matcher(createTableSql);
        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2));
        }
        return properties;
    }

    /**
     * 解析表的副本数。
     * <ul>
     *   <li>动态分区：优先解析 {@code dynamic_partition.replication_allocation}</li>
     *   <li>非动态分区：优先解析 {@code replication_allocation}</li>
     *   <li>兜底：{@code replication_num}（以及旧版本的 {@code dynamic_partition.replication_num}）</li>
     * </ul>
     */
    public static Integer parseReplicationNum(String createTableSql) {
        if (!StringUtils.hasText(createTableSql)) {
            return null;
        }

        Map<String, String> properties = extractProperties(createTableSql);
        if (properties.isEmpty()) {
            return null;
        }

        if (isDynamicPartitionEnabled(properties)) {
            Integer dynamicAllocation = parseReplicationFromAllocation(
                    properties.get("dynamic_partition.replication_allocation"));
            if (dynamicAllocation != null) {
                return dynamicAllocation;
            }
            Integer dynamicReplicationNum = parsePositiveInt(properties.get("dynamic_partition.replication_num"));
            if (dynamicReplicationNum != null) {
                return dynamicReplicationNum;
            }
        }

        Integer allocation = parseReplicationFromAllocation(properties.get("replication_allocation"));
        if (allocation != null) {
            return allocation;
        }

        return parsePositiveInt(properties.get("replication_num"));
    }

    /**
     * 解析 PARTITION BY 中的分区字段（或分区表达式）。
     */
    public static String parsePartitionColumn(String createTableSql) {
        if (!StringUtils.hasText(createTableSql)) {
            return null;
        }

        Matcher matcher = PARTITION_BY_PATTERN.matcher(createTableSql);
        if (!matcher.find()) {
            return null;
        }

        int openIndex = createTableSql.indexOf('(', matcher.end());
        if (openIndex < 0 || openIndex >= createTableSql.length() - 1) {
            return null;
        }

        String raw = extractBracketContent(createTableSql, openIndex);
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        String normalized = normalizePartitionExpression(raw);
        if (normalized.startsWith("`") && normalized.endsWith("`")
                && normalized.indexOf('`', 1) == normalized.length() - 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String extractBracketContent(String sql, int openIndex) {
        int depth = 1;
        int cursor = openIndex + 1;
        while (cursor < sql.length()) {
            char ch = sql.charAt(cursor);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(openIndex + 1, cursor);
                }
            }
            cursor++;
        }
        return null;
    }

    private static String normalizePartitionExpression(String expression) {
        String normalized = expression == null ? "" : expression.trim();
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }
        // Some JDBC / SQL transport chains return escaped backticks such as \`dt\`.
        return ESCAPED_BACKTICK_PATTERN.matcher(normalized).replaceAll("`");
    }

    private static boolean isDynamicPartitionEnabled(Map<String, String> properties) {
        String enabled = properties.get("dynamic_partition.enable");
        return "true".equalsIgnoreCase(enabled);
    }

    private static Integer parseReplicationFromAllocation(String allocation) {
        if (!StringUtils.hasText(allocation)) {
            return null;
        }

        int total = 0;
        boolean found = false;
        for (String part : allocation.split(",")) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            Matcher matcher = ALLOCATION_REPLICA_PATTERN.matcher(part);
            if (matcher.find()) {
                Integer replica = parsePositiveInt(matcher.group(1));
                if (replica != null) {
                    total += replica;
                    found = true;
                }
            }
        }
        return found ? total : null;
    }

    private static Integer parsePositiveInt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(trimmed);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
