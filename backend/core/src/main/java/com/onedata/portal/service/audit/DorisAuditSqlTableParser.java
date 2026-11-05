package com.onedata.portal.service.audit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;



/**
 * 保持现有访问口径的轻量 SQL 表引用解析器。
 */
@Component
public class DorisAuditSqlTableParser {

    private static final Pattern FROM_JOIN_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_INTO_PATTERN = Pattern.compile(
            "\\bINSERT\\s+INTO\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_PATTERN = Pattern.compile(
            "\\bUPDATE\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM_PATTERN = Pattern.compile(
            "\\bDELETE\\s+FROM\\s+(?:`?([a-zA-Z0-9_]+)`?\\.)?`?([a-zA-Z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 匹配 {@code WITH name AS (}、{@code , name AS (} 形式的 CTE 定义名。
     */
    private static final Pattern CTE_NAME_PATTERN = Pattern.compile(
            "(?:\\bWITH\\b|,)\\s*`?([a-zA-Z0-9_]+)`?\\s*(?:\\([^)]*\\)\\s*)?\\bAS\\b\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * 系统库不是业务资产。审计库尤其重要：同步任务每轮都要查询审计表，
     * 若不排除，审计表会把自己写成永久热点并持续放大汇总表体积。
     */
    private static final Set<String> SYSTEM_SCHEMAS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "information_schema",
                    "__internal_schema",
                    "doris_audit_db__",
                    "mysql",
                    "sys",
                    "performance_schema")));

    public List<AuditTableReference> parse(String sql, String defaultDatabase) {
        if (!StringUtils.hasText(sql)) {
            return new ArrayList<>();
        }
        String scannable = maskLiteralsAndComments(sql);
        Set<String> cteNames = collectCteNames(scannable);
        Map<String, AuditTableReference> references = new LinkedHashMap<>();
        collect(references, scannable, defaultDatabase, cteNames, FROM_JOIN_PATTERN, true, false);
        collect(references, scannable, defaultDatabase, cteNames, INSERT_INTO_PATTERN, false, true);
        collect(references, scannable, defaultDatabase, cteNames, UPDATE_PATTERN, false, true);
        collect(references, scannable, defaultDatabase, cteNames, DELETE_FROM_PATTERN, false, true);
        return new ArrayList<>(references.values());
    }

    /**
     * 用等长空格替换字符串字面量与注释。
     * <p>
     * 保持长度是为了不打乱后续正则的匹配语义，同时让字面量或注释里的 {@code FROM}/{@code JOIN}
     * 不再被误认成表引用。
     */
    private String maskLiteralsAndComments(String sql) {
        char[] chars = sql.toCharArray();
        int index = 0;
        while (index < chars.length) {
            char current = chars[index];
            if (current == '\'' || current == '"') {
                index = maskUntil(chars, index, current);
            } else if (current == '-' && index + 1 < chars.length && chars[index + 1] == '-') {
                index = maskLineComment(chars, index);
            } else if (current == '#') {
                index = maskLineComment(chars, index);
            } else if (current == '/' && index + 1 < chars.length && chars[index + 1] == '*') {
                index = maskBlockComment(chars, index);
            } else {
                index++;
            }
        }
        return new String(chars);
    }

    private int maskUntil(char[] chars, int start, char quote) {
        int index = start + 1;
        chars[start] = ' ';
        while (index < chars.length) {
            char current = chars[index];
            if (current == '\\' && index + 1 < chars.length) {
                chars[index] = ' ';
                chars[index + 1] = ' ';
                index += 2;
                continue;
            }
            chars[index] = ' ';
            index++;
            if (current == quote) {
                // 连续两个引号是转义，必须一并吃掉；只跳过前一个会让字面量提前结束，
                // 残留的引号再开启一段新“字面量”，把后面真正的表引用整段吞掉。
                if (index < chars.length && chars[index] == quote) {
                    chars[index] = ' ';
                    index++;
                    continue;
                }
                return index;
            }
        }
        return index;
    }

    private int maskLineComment(char[] chars, int start) {
        int index = start;
        while (index < chars.length && chars[index] != '\n') {
            chars[index] = ' ';
            index++;
        }
        return index;
    }

    private int maskBlockComment(char[] chars, int start) {
        int index = start;
        while (index < chars.length) {
            boolean end = chars[index] == '*' && index + 1 < chars.length && chars[index + 1] == '/';
            chars[index] = ' ';
            index++;
            if (end) {
                chars[index] = ' ';
                return index + 1;
            }
        }
        return index;
    }

    /**
     * CTE 名称是语句内的临时结果集，不是数据资产，不应写进访问汇总。
     */
    private Set<String> collectCteNames(String sql) {
        Set<String> names = new HashSet<>();
        Matcher matcher = CTE_NAME_PATTERN.matcher(sql);
        while (matcher.find()) {
            names.add(normalize(matcher.group(1)));
        }
        return names;
    }

    private void collect(Map<String, AuditTableReference> references,
            String sql,
            String defaultDatabase,
            Set<String> cteNames,
            Pattern pattern,
            boolean read,
            boolean write) {
        Matcher matcher = pattern.matcher(sql);
        while (matcher.find()) {
            String database = normalize(matcher.group(1));
            String table = normalize(matcher.group(2));
            if (!StringUtils.hasText(table)) {
                continue;
            }
            // CTE 只能以无限定名引用，带库名的同名表仍是真实资产。
            if (!StringUtils.hasText(database) && cteNames.contains(table)) {
                continue;
            }
            if (!StringUtils.hasText(database)) {
                database = normalize(defaultDatabase);
            }
            if (!StringUtils.hasText(database) || SYSTEM_SCHEMAS.contains(database)) {
                continue;
            }
            String key = database + "." + table;
            String resolvedDatabase = database;
            String resolvedTable = table;
            AuditTableReference reference = references.computeIfAbsent(
                    key, ignored -> new AuditTableReference(resolvedDatabase, resolvedTable));
            reference.setRead(reference.isRead() || read);
            reference.setWrite(reference.isWrite() || write);
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }
}
