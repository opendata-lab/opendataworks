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
    private static final String SIMPLE_IDENTIFIER = "[A-Za-z0-9_]+";
    private static final String OPTIONAL_SCHEMA = "(?:(?<schema>" + SIMPLE_IDENTIFIER + ")\\.)?" + SIMPLE_IDENTIFIER;

    /**
     * 数据范围生效且 SQL 无法解析时，唯一允许的语句形式：全部为整句匹配，只含简单标识符，
     * 不接受注释、引号、FROM/IN 库名子句或 DESC 后接查询。有 schema 限定时取出来校验。
     */
    private static final List<Pattern> SCOPE_FALLBACK_FORMS = Arrays.asList(
            Pattern.compile("(?i)SHOW\\s+(?:FULL\\s+)?TABLES(?:\\s+LIKE\\s+'[A-Za-z0-9_%]*')?"),
            Pattern.compile("(?i)SHOW\\s+(?:FULL\\s+)?(?:COLUMNS|FIELDS)\\s+(?:FROM|IN)\\s+" + OPTIONAL_SCHEMA),
            Pattern.compile("(?i)(?:DESC|DESCRIBE)\\s+" + OPTIONAL_SCHEMA)
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
        int limit = normalizeLimit(request.getLimit());
        int timeoutSeconds = normalizeTimeout(request.getTimeoutSeconds());
        String preferredEngine = trimToLower(request.getPreferredEngine());

        AgentDatasourceResolution datasource = agentMetadataService.resolveDatasource(database, preferredEngine);
        // 标识符语义取决于引擎（PostgreSQL 未加引号的名称折叠为小写），所以在解析出数据源之后校验
        validateSqlReferencesInScope(sql, datasource == null ? null : datasource.getEngine());
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

    private void validateSqlReferencesInScope(String sql, String engine) {
        if (!AgentDataScopeContext.isActive()) {
            return;
        }
        boolean postgresql = "postgresql".equals(engine);
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            TablesNamesFinder finder = new TablesNamesFinder();
            for (String tableName : finder.getTableList(statement)) {
                String schema = schemaFromTableName(tableName, postgresql);
                if (schema != null) {
                    AgentDataScopeContext.requireSqlSchemaAllowedExactly(schema);
                }
            }
        } catch (JSQLParserException | UnsupportedOperationException e) {
            // TablesNamesFinder 对 SHOW 等非 DML 语句会抛 UnsupportedOperationException
            // （如 "Finding tables from ShowTablesStatement is not supported"）。这类语句没有
            // 可解析的 schema 引用，应回退到词法范围校验，而不是把内部异常当成执行失败抛出。
            log.trace("SQL 结构化解析失败，回退到词法范围校验", e);
            validateSqlReferencesInScopeLexically(sql, postgresql);
        }
    }

    private void validateSqlReferencesInScopeLexically(String sql, boolean postgresql) {
        // 数据范围生效时，无法完整解析的 SQL 不做词法猜测（逗号连接、DESC SELECT、SHOW ... FROM db、
        // 可执行注释等都能骗过正则）。只放行下面几种结构完全固定的 MySQL/Doris 元数据语句，其余拒绝。
        String statement = sql.trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (!postgresql) {
            for (Pattern form : SCOPE_FALLBACK_FORMS) {
                Matcher matcher = form.matcher(statement);
                if (matcher.matches()) {
                    String schema = matcher.groupCount() >= 1 ? matcher.group("schema") : null;
                    if (schema != null) {
                        AgentDataScopeContext.requireSqlSchemaAllowedExactly(schema);
                    }
                    return;
                }
            }
        }
        throw new IllegalArgumentException("数据范围限制: 无法完整解析 SQL 的表引用，请改写为可解析的标准 SELECT");
    }

    /**
     * 解析表名的限定部分，返回目标引擎实际使用的 schema 名；没有限定时返回 {@code null}。
     *
     * <p>带引号的部分（反引号或双引号，支持重复引号转义）原样保留，包括其中的空白和点；
     * PostgreSQL 未加引号的名称按其规则折叠为小写，MySQL/Doris 保持原样。
     */
    static String schemaFromTableName(String tableName, boolean postgresql) {
        if (tableName == null || tableName.isEmpty()) {
            return null;
        }
        String value = tableName.trim();
        char first = value.charAt(0);
        if (first == '`' || first == '"') {
            StringBuilder part = new StringBuilder();
            int i = 1;
            while (i < value.length()) {
                char current = value.charAt(i);
                if (current == first) {
                    if (i + 1 < value.length() && value.charAt(i + 1) == first) {
                        part.append(first);
                        i += 2;
                        continue;
                    }
                    break;
                }
                part.append(current);
                i++;
            }
            int rest = i + 1;
            boolean qualified = rest < value.length() && value.substring(rest).trim().startsWith(".");
            return qualified ? part.toString() : null;
        }
        int index = value.indexOf('.');
        if (index <= 0) {
            return null;
        }
        String schema = value.substring(0, index).trim();
        return postgresql ? foldPostgresqlIdentifier(schema) : schema;
    }

    /** PostgreSQL 只把未加引号标识符中的 ASCII 大写字母折叠为小写，非 ASCII 字符保持原样。 */
    static String foldPostgresqlIdentifier(String identifier) {
        StringBuilder folded = new StringBuilder(identifier.length());
        for (int i = 0; i < identifier.length(); i++) {
            char current = identifier.charAt(i);
            folded.append(current >= 'A' && current <= 'Z' ? (char) (current + ('a' - 'A')) : current);
        }
        return folded.toString();
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
