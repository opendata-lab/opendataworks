package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentReadQueryRequest;
import com.onedata.portal.agentapi.dto.AgentReadQueryResponse;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class BackendAgentQueryService implements AgentQueryService {

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 10000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 120;
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
        response.setRows(execution.getRows());
        response.setRowCount(execution.getRowCount());
        response.setHasMore(execution.isHasMore());
        response.setDurationMs(execution.getDurationMs());
        return response;
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
