package com.onedata.portal.agentapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentReadQueryRequest;
import com.onedata.portal.agentapi.dto.AgentReadQueryResponse;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentQueryService implements AgentQueryService {

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 10000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
    /** 单行间分隔与外层数组括号的近似开销，避免低估实际序列化字节。 */
    private static final int ROW_SERIALIZATION_OVERHEAD_BYTES = 2;
    private static final Pattern LEADING_KEYWORD_PATTERN = Pattern.compile("^\\s*([a-zA-Z]+)");
    private static final Pattern MUTATING_FALLBACK_KEYWORD_PATTERN = Pattern.compile(
            "(^|[^a-zA-Z0-9_])(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|REPLACE|MERGE|CALL|GRANT|REVOKE)([^a-zA-Z0-9_]|$)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> READ_ONLY_PARSE_FALLBACK_KEYWORDS = new LinkedHashSet<>(
            Arrays.asList("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")
    );
    private static final Set<String> READ_ONLY_FALLBACK_KEYWORDS = new LinkedHashSet<>(
            Arrays.asList("SHOW", "DESC", "DESCRIBE", "EXPLAIN")
    );

    private final AgentMetadataService agentMetadataService;
    private final AgentJdbcExecutor agentJdbcExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 单次只读查询返回行的字节预算。源头守卫：超预算即截断，防止单条工具结果帧
     * 撑爆 claude-agent-sdk 的 stdout JSON 缓冲（默认 1MB）。默认 512KB，显著低于
     * SDK 默认缓冲上限，并为下游脚本路径的美化与信封预留余量。
     */
    @Value("${agent.query.max-result-bytes:524288}")
    private int maxResultBytes = 524288;

    @Override
    public AgentReadQueryResponse readQuery(AgentReadQueryRequest request) {
        String database = trimToNull(request.getDatabase());
        String sql = trimToNull(request.getSql());
        if (!StringUtils.hasText(database)) {
            throw new IllegalArgumentException("database 不能为空");
        }
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("sql 不能为空");
        }

        validateReadOnlySql(sql);
        AgentDataScopeContext.requireDatabaseNameAllowed(database);
        validateSqlReferencesInScope(sql);
        int limit = normalizeLimit(request.getLimit());
        int timeoutSeconds = normalizeTimeout(request.getTimeoutSeconds());
        String preferredEngine = trimToLower(request.getPreferredEngine());

        AgentDatasourceResolution datasource = agentMetadataService.resolveDatasource(database, preferredEngine);
        AgentJdbcExecutor.QueryExecutionResult execution = agentJdbcExecutor.executeReadOnlyQuery(
                datasource,
                sql,
                limit,
                timeoutSeconds
        );

        AgentReadQueryResponse response = new AgentReadQueryResponse();
        response.setDatabase(database);
        response.setEngine(datasource.getEngine());
        response.setSql(sql);
        response.setLimit(limit);
        response.setDurationMs(execution.getDurationMs());

        List<Map<String, Object>> rows = execution.getRows();
        boolean forExport = Boolean.TRUE.equals(request.getForExport());
        int keep = forExport ? rows.size() : resultByteBudgetKeepCount(rows);
        if (keep < rows.size()) {
            int total = rows.size();
            List<Map<String, Object>> kept = new ArrayList<>(rows.subList(0, keep));
            response.setRows(kept);
            response.setRowCount(kept.size());
            response.setHasMore(true);
            response.setTruncatedBySize(true);
            response.setNotice(String.format(
                    "结果体积超过单次返回上限（约 %d KB），已截断为前 %d 行（原始返回 %d 行）。"
                            + "如需完整或精确结果，请缩小查询范围：增加过滤条件、做聚合，或降低 LIMIT；"
                            + "不要对同一口径重复执行。",
                    maxResultBytes / 1024, keep, total));
            log.warn("agent readQuery result truncated by size budget. kept={} total={} maxBytes={} sql={}",
                    keep, total, maxResultBytes, sql);
        } else {
            response.setRows(rows);
            response.setRowCount(execution.getRowCount());
            response.setHasMore(execution.isHasMore());
        }
        return response;
    }

    /**
     * 逐行估算紧凑 JSON 字节并累加，返回在字节预算内可保留的行数。
     * 至少保留 1 行（即便首行已超预算），使模型仍有上下文；预算远低于 SDK 缓冲上限，单行安全。
     */
    private int resultByteBudgetKeepCount(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        long accumulated = 0;
        for (int i = 0; i < rows.size(); i++) {
            long rowBytes;
            try {
                rowBytes = objectMapper.writeValueAsBytes(rows.get(i)).length + ROW_SERIALIZATION_OVERHEAD_BYTES;
            } catch (JsonProcessingException e) {
                // 无法序列化的单行视为超大，保守在此截断（但至少保留已通过的行）。
                return Math.max(1, i);
            }
            accumulated += rowBytes;
            if (accumulated > maxResultBytes) {
                return Math.max(1, i);
            }
        }
        return rows.size();
    }

    void validateReadOnlySql(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            validateReadOnlySqlWithLexicalFallback(sql);
            return;
        }
        if (statements == null || statements.getStatements() == null || statements.getStatements().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        if (statements.getStatements().size() != 1) {
            throw new IllegalArgumentException("仅支持单条只读 SQL");
        }

        Statement statement = statements.getStatements().get(0);
        if (!isReadOnlyStatement(statement, sql)) {
            throw new IllegalArgumentException("仅支持只读 SQL");
        }
    }

    private boolean isReadOnlyStatement(Statement statement, String sql) {
        if (statement instanceof Select) {
            return true;
        }

        String statementType = statement == null ? "" : statement.getClass().getSimpleName();
        if (statementType.startsWith("Show")
                || "DescribeStatement".equals(statementType)
                || "ExplainStatement".equals(statementType)) {
            return true;
        }

        return READ_ONLY_FALLBACK_KEYWORDS.contains(detectLeadingKeyword(sql));
    }

    private void validateSqlReferencesInScope(String sql) {
        if (!AgentDataScopeContext.isActive()) {
            return;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            for (String tableName : finder.getTableList(statement)) {
                String schema = schemaFromTableName(tableName);
                if (StringUtils.hasText(schema)) {
                    AgentDataScopeContext.requireSqlSchemaAllowed(schema);
                }
            }
        } catch (JSQLParserException ignored) {
            validateSqlReferencesInScopeLexically(sql);
        }
    }

    private void validateSqlReferencesInScopeLexically(String sql) {
        String normalizedSql = maskCommentsAndQuotedText(sql);
        Matcher matcher = Pattern.compile("(?i)\\b(?:from|join|describe|desc)\\s+`?([A-Za-z0-9_]+)`?\\s*\\.").matcher(normalizedSql);
        while (matcher.find()) {
            AgentDataScopeContext.requireSqlSchemaAllowed(matcher.group(1));
        }
    }

    private String schemaFromTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        String normalized = tableName.replace("`", "").trim();
        int index = normalized.indexOf('.');
        if (index <= 0) {
            return null;
        }
        return normalized.substring(0, index).trim();
    }

    private void validateReadOnlySqlWithLexicalFallback(String sql) {
        String normalizedSql = maskCommentsAndQuotedText(sql);
        if (!isSingleStatement(normalizedSql)) {
            throw new IllegalArgumentException("仅支持单条只读 SQL");
        }
        if (!READ_ONLY_PARSE_FALLBACK_KEYWORDS.contains(detectLeadingKeyword(normalizedSql))) {
            throw new IllegalArgumentException("仅支持只读 SQL");
        }
        if (MUTATING_FALLBACK_KEYWORD_PATTERN.matcher(normalizedSql).find()) {
            throw new IllegalArgumentException("仅支持只读 SQL");
        }
    }

    private boolean isSingleStatement(String normalizedSql) {
        boolean seenTerminator = false;
        for (int i = 0; i < normalizedSql.length(); i++) {
            char current = normalizedSql.charAt(i);
            if (current == ';') {
                if (seenTerminator) {
                    return false;
                }
                seenTerminator = true;
                continue;
            }
            if (seenTerminator && !Character.isWhitespace(current)) {
                return false;
            }
        }
        return true;
    }

    private String maskCommentsAndQuotedText(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                result.append(isLineBreak(current) ? current : ' ');
                if (isLineBreak(current)) {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    result.append(' ');
                    result.append(' ');
                    i++;
                    inBlockComment = false;
                } else {
                    result.append(isLineBreak(current) ? current : ' ');
                }
                continue;
            }
            if (inSingleQuote) {
                result.append(isLineBreak(current) ? current : ' ');
                if (current == '\\' && next != '\0') {
                    result.append(isLineBreak(next) ? next : ' ');
                    i++;
                } else if (current == '\'' && next == '\'') {
                    result.append(' ');
                    i++;
                } else if (current == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                result.append(isLineBreak(current) ? current : ' ');
                if (current == '\\' && next != '\0') {
                    result.append(isLineBreak(next) ? next : ' ');
                    i++;
                } else if (current == '"' && next == '"') {
                    result.append(' ');
                    i++;
                } else if (current == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                result.append(isLineBreak(current) ? current : ' ');
                if (current == '`' && next == '`') {
                    result.append(' ');
                    i++;
                } else if (current == '`') {
                    inBacktick = false;
                }
                continue;
            }

            if (current == '-' && next == '-') {
                result.append(' ');
                result.append(' ');
                i++;
                inLineComment = true;
            } else if (current == '#') {
                result.append(' ');
                inLineComment = true;
            } else if (current == '/' && next == '*') {
                result.append(' ');
                result.append(' ');
                i++;
                inBlockComment = true;
            } else if (current == '\'') {
                result.append(' ');
                inSingleQuote = true;
            } else if (current == '"') {
                result.append(' ');
                inDoubleQuote = true;
            } else if (current == '`') {
                result.append(' ');
                inBacktick = true;
            } else {
                result.append(current);
            }
        }

        return result.toString();
    }

    private boolean isLineBreak(char value) {
        return value == '\n' || value == '\r';
    }

    private String detectLeadingKeyword(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        Matcher matcher = LEADING_KEYWORD_PATTERN.matcher(sql);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : "";
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String trimToLower(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
