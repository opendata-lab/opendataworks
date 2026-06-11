package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.QueryPreview;
import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.dto.SqlQueryResultSet;
import com.onedata.portal.entity.DataQueryHistory;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataQueryHistoryMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.replace.Replace;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 查询服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQueryService {

    private static final Pattern RISK_TRIGGER_KEYWORDS = Pattern.compile(
        "\\b(drop|truncate|delete|update|insert|replace|merge|alter)\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SQL_TYPE_PATTERN = Pattern.compile("^\\s*([a-zA-Z]+)");
    private static final Pattern DROP_TARGET_PATTERN = Pattern.compile(
        "(?i)\\bdrop\\s+(?:table|view|database)\\s+(?:if\\s+exists\\s+)?((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern TRUNCATE_TARGET_PATTERN = Pattern.compile(
        "(?i)\\btruncate\\s+(?:table\\s+)?((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern DELETE_TARGET_PATTERN = Pattern.compile(
        "(?i)\\bdelete\\s+from\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern UPDATE_TARGET_PATTERN = Pattern.compile(
        "(?i)\\bupdate\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern INSERT_TARGET_PATTERN = Pattern.compile(
        "(?i)\\binsert\\s+(?:into|overwrite(?:\\s+table)?)\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern REPLACE_TARGET_PATTERN = Pattern.compile(
        "(?i)\\breplace\\s+into\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern MERGE_TARGET_PATTERN = Pattern.compile(
        "(?i)\\bmerge\\s+into\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );
    private static final Pattern ALTER_TARGET_PATTERN = Pattern.compile(
        "(?i)\\balter\\s+table\\s+((?:`?[a-zA-Z0-9_]+`?\\.)?`?[a-zA-Z0-9_]+`?)"
    );

    private static final int MAX_LIMIT = 10000;
    private static final int DEFAULT_LIMIT = 200;
    private static final int PREVIEW_LIMIT = 100;
    private static final int MAX_STATEMENTS = 50;
    private static final long TOKEN_TTL_SECONDS = 5 * 60;
    private static final String TOKEN_HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_SECRET_ENV = "ODW_SQL_CONFIRM_SECRET";
    private static final String TOKEN_FALLBACK_SECRET = "odw_sql_confirm_secret_v1";

    private static final String RESULT_SET = "RESULT_SET";
    private static final String UPDATE_COUNT = "UPDATE_COUNT";
    private static final String BLOCKED = "BLOCKED";
    private static final String ERROR = "ERROR";
    private static final String SKIPPED = "SKIPPED";

    private static final String SUCCESS_STATUS = "SUCCESS";
    private static final String BLOCKED_STATUS = "BLOCKED";
    private static final String ERROR_STATUS = "ERROR";
    private static final String SKIPPED_STATUS = "SKIPPED";

    private final DorisConnectionService dorisConnectionService;
    private final DorisClusterService dorisClusterService;
    private final DataQueryHistoryMapper historyMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, RunningQuery> runningQueries = new ConcurrentHashMap<>();

    private static class RunningQuery {
        private final Connection connection;
        private final java.sql.Statement statement;
        private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

        private RunningQuery(Connection connection, java.sql.Statement statement) {
            this.connection = connection;
            this.statement = statement;
        }

        private boolean isCancelRequested() {
            return cancelRequested.get();
        }

        private void cancel() {
            cancelRequested.set(true);
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // ignored
            }
            try {
                connection.close();
            } catch (SQLException ignored) {
                // ignored
            }
        }
    }

    @Data
    private static class AnalyzeBundle {
        private List<String> statements;
        private List<AnalyzedStatement> analyzed;
    }

    @Data
    private static class AnalyzedStatement {
        private Integer statementIndex;
        private String sql;
        private String sqlSnippet;
        private String sqlType;
        private String riskLevel;
        private String parseStatus;
        private boolean requiresConfirm;
        private String targetObject;
        private boolean blocked;
        private String blockedReason;
        private String confirmToken;
        private LocalDateTime confirmExpireAt;
        private String fingerprint;
    }

    @Data
    private static class ConfirmTokenPayload {
        private String userId;
        private Long clusterId;
        private String database;
        private Integer statementIndex;
        private String targetObject;
        private String fingerprint;
        private Long exp;
    }

    @Data
    private static class TokenValidation {
        private boolean valid;
        private String reason;

        static TokenValidation ok() {
            TokenValidation validation = new TokenValidation();
            validation.setValid(true);
            return validation;
        }

        static TokenValidation fail(String reason) {
            TokenValidation validation = new TokenValidation();
            validation.setValid(false);
            validation.setReason(reason);
            return validation;
        }
    }

    public SqlAnalyzeResponse analyzeQuery(SqlAnalyzeRequest request) {
        if (!StringUtils.hasText(request.getDatabase())) {
            throw new RuntimeException("数据库不能为空");
        }
        if (!StringUtils.hasText(request.getSql())) {
            throw new RuntimeException("SQL 不能为空");
        }

        String userId = resolveUserId();
        AnalyzeBundle bundle = analyzeStatements(request.getSql(), request.getClusterId(), request.getDatabase(), userId, true);

        SqlAnalyzeResponse response = new SqlAnalyzeResponse();
        List<SqlAnalyzeResponse.StatementInfo> statements = new ArrayList<>();
        List<SqlAnalyzeResponse.RiskItem> riskItems = new ArrayList<>();
        List<SqlAnalyzeResponse.ConfirmChallenge> confirmChallenges = new ArrayList<>();

        boolean blocked = false;
        String blockedReason = null;

        for (AnalyzedStatement analyzed : bundle.getAnalyzed()) {
            SqlAnalyzeResponse.StatementInfo statementInfo = new SqlAnalyzeResponse.StatementInfo();
            statementInfo.setStatementIndex(analyzed.getStatementIndex());
            statementInfo.setSqlSnippet(analyzed.getSqlSnippet());
            statementInfo.setSqlType(analyzed.getSqlType());
            statements.add(statementInfo);

            SqlAnalyzeResponse.RiskItem riskItem = new SqlAnalyzeResponse.RiskItem();
            riskItem.setStatementIndex(analyzed.getStatementIndex());
            riskItem.setSqlType(analyzed.getSqlType());
            riskItem.setRiskLevel(analyzed.getRiskLevel());
            riskItem.setParseStatus(analyzed.getParseStatus());
            riskItem.setRequiresConfirm(analyzed.isRequiresConfirm());
            riskItem.setTargetObject(analyzed.getTargetObject());
            riskItem.setBlocked(analyzed.isBlocked());
            riskItem.setBlockedReason(analyzed.getBlockedReason());
            riskItems.add(riskItem);

            if (analyzed.isBlocked()) {
                blocked = true;
                if (!StringUtils.hasText(blockedReason)) {
                    blockedReason = analyzed.getBlockedReason();
                }
                continue;
            }

            if (analyzed.isRequiresConfirm() && StringUtils.hasText(analyzed.getConfirmToken())) {
                SqlAnalyzeResponse.ConfirmChallenge challenge = new SqlAnalyzeResponse.ConfirmChallenge();
                challenge.setStatementIndex(analyzed.getStatementIndex());
                challenge.setTargetObject(analyzed.getTargetObject());
                challenge.setConfirmTextHint("请输入对象名确认：" + analyzed.getTargetObject());
                challenge.setConfirmToken(analyzed.getConfirmToken());
                challenge.setExpireAt(analyzed.getConfirmExpireAt());
                confirmChallenges.add(challenge);
            }
        }

        response.setStatements(statements);
        response.setRiskItems(riskItems);
        response.setConfirmChallenges(confirmChallenges);
        response.setBlocked(blocked);
        response.setBlockedReason(blockedReason);
        return response;
    }

    public boolean stopQuery(String userId, String clientQueryId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(clientQueryId)) {
            return false;
        }
        String key = buildRunningKey(userId, clientQueryId);
        RunningQuery running = runningQueries.get(key);
        if (running == null) {
            return true;
        }
        running.cancel();
        return true;
    }

    /**
     * 执行查询
     */
    public SqlQueryResponse executeQuery(SqlQueryRequest request) {
        if (!StringUtils.hasText(request.getDatabase())) {
            throw new RuntimeException("数据库不能为空");
        }
        if (!StringUtils.hasText(request.getSql())) {
            throw new RuntimeException("SQL 不能为空");
        }

        String userId = resolveUserId();
        AnalyzeBundle bundle = analyzeStatements(request.getSql(), request.getClusterId(), request.getDatabase(), userId, false);
        List<String> statements = bundle.getStatements();
        List<AnalyzedStatement> analyzedStatements = bundle.getAnalyzed();

        if (statements.isEmpty()) {
            throw new RuntimeException("SQL 不能为空");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new RuntimeException("SQL 语句过多，请分批执行");
        }

        int limit = resolveLimit(request.getLimit());
        long start = System.currentTimeMillis();

        Map<Integer, SqlQueryRequest.SqlConfirmation> confirmationMap = new HashMap<>();
        if (request.getConfirmations() != null) {
            for (SqlQueryRequest.SqlConfirmation confirmation : request.getConfirmations()) {
                if (confirmation == null || confirmation.getStatementIndex() == null) {
                    continue;
                }
                confirmationMap.putIfAbsent(confirmation.getStatementIndex(), confirmation);
            }
        }

        List<SqlQueryResultSet> resultSets = new ArrayList<>();
        boolean cancelled = false;
        String message;

        String clientQueryId = request.getClientQueryId();
        String runningKey = StringUtils.hasText(userId) && StringUtils.hasText(clientQueryId)
            ? buildRunningKey(userId, clientQueryId)
            : null;

        RunningQuery runningQuery = null;

        try (Connection connection = dorisConnectionService.getConnection(request.getClusterId(), request.getDatabase());
             java.sql.Statement statement = connection.createStatement()) {

            if (runningKey != null) {
                runningQuery = new RunningQuery(connection, statement);
                RunningQuery previous = runningQueries.put(runningKey, runningQuery);
                if (previous != null) {
                    previous.cancel();
                }
            }

            try {
                statement.setQueryTimeout(300);
            } catch (SQLException e) {
                log.debug("JDBC driver does not support query timeout, fallback to socketTimeout only", e);
            }
            statement.setMaxRows(limit + 1);

            Integer haltIndex = null;
            String skipReason = null;

            for (int i = 0; i < analyzedStatements.size(); i++) {
                AnalyzedStatement analyzed = analyzedStatements.get(i);
                String sql = statements.get(i);

                if (runningQuery != null && runningQuery.isCancelRequested()) {
                    cancelled = true;
                    haltIndex = analyzed.getStatementIndex();
                    skipReason = "查询已停止，未继续执行后续语句";
                    break;
                }

                if (analyzed.isBlocked()) {
                    addControlResult(resultSets, analyzed, BLOCKED, BLOCKED_STATUS, analyzed.getBlockedReason(), 0L);
                    haltIndex = analyzed.getStatementIndex();
                    skipReason = "前序语句被阻断，后续语句未执行";
                    break;
                }

                if (analyzed.isRequiresConfirm()) {
                    SqlQueryRequest.SqlConfirmation confirmation = confirmationMap.get(analyzed.getStatementIndex());
                    String confirmError = validateConfirmation(confirmation, analyzed, request, userId);
                    if (StringUtils.hasText(confirmError)) {
                        addControlResult(resultSets, analyzed, BLOCKED, BLOCKED_STATUS, confirmError, 0L);
                        haltIndex = analyzed.getStatementIndex();
                        skipReason = "前序语句未通过强确认，后续语句未执行";
                        break;
                    }
                }

                long statementStart = System.currentTimeMillis();
                try {
                    SqlQueryResultSet resultSet = executeStatement(statement, sql, limit);
                    resultSet.setIndex(resultSets.size() + 1);
                    resultSet.setStatementIndex(analyzed.getStatementIndex());
                    resultSet.setSqlSnippet(analyzed.getSqlSnippet());
                    resultSet.setStatus(SUCCESS_STATUS);
                    resultSet.setDurationMs(System.currentTimeMillis() - statementStart);
                    if (!StringUtils.hasText(resultSet.getMessage())) {
                        if (UPDATE_COUNT.equals(resultSet.getResultType()) && resultSet.getAffectedRows() != null) {
                            resultSet.setMessage("影响 " + resultSet.getAffectedRows() + " 行");
                        } else {
                            resultSet.setMessage("执行成功");
                        }
                    }
                    resultSets.add(resultSet);
                } catch (SQLException e) {
                    if (runningQuery != null && runningQuery.isCancelRequested()) {
                        cancelled = true;
                        haltIndex = analyzed.getStatementIndex();
                        skipReason = "查询已停止，未继续执行后续语句";
                        addControlResult(resultSets, analyzed, SKIPPED, SKIPPED_STATUS, "查询已停止", System.currentTimeMillis() - statementStart);
                        break;
                    }
                    log.error("Execute SQL statement failed, statementIndex={}, sql={}", analyzed.getStatementIndex(), abbreviate(sql), e);
                    addControlResult(resultSets, analyzed, ERROR, ERROR_STATUS, "执行 SQL 失败: " + e.getMessage(), System.currentTimeMillis() - statementStart);
                    haltIndex = analyzed.getStatementIndex();
                    skipReason = "前序语句执行失败，后续语句未执行";
                    break;
                }
            }

            if (haltIndex != null) {
                for (AnalyzedStatement analyzed : analyzedStatements) {
                    if (analyzed.getStatementIndex() <= haltIndex) {
                        continue;
                    }
                    addControlResult(resultSets, analyzed, SKIPPED, SKIPPED_STATUS, skipReason, 0L);
                }
            }

            if (cancelled && !StringUtils.hasText(skipReason)) {
                skipReason = "查询已停止";
            }
        } catch (SQLException e) {
            if (runningQuery != null && runningQuery.isCancelRequested()) {
                cancelled = true;
                resultSets.clear();
                for (AnalyzedStatement analyzed : analyzedStatements) {
                    addControlResult(resultSets, analyzed, SKIPPED, SKIPPED_STATUS, "查询已停止", 0L);
                }
            } else {
                log.error("Execute SQL query failed", e);
                throw new RuntimeException("执行 SQL 失败: " + e.getMessage(), e);
            }
        } finally {
            if (runningKey != null) {
                runningQueries.remove(runningKey);
            }
        }

        long duration = System.currentTimeMillis() - start;

        int successCount = countByStatus(resultSets, SUCCESS_STATUS);
        int blockedCount = countByStatus(resultSets, BLOCKED_STATUS);
        int errorCount = countByStatus(resultSets, ERROR_STATUS);
        int skippedCount = countByStatus(resultSets, SKIPPED_STATUS);

        if (cancelled) {
            message = String.format("查询已停止：成功 %d，阻断 %d，失败 %d，跳过 %d", successCount, blockedCount, errorCount, skippedCount);
        } else {
            message = String.format("执行完成：成功 %d，阻断 %d，失败 %d，跳过 %d", successCount, blockedCount, errorCount, skippedCount);
        }

        SqlQueryResponse response = new SqlQueryResponse();
        response.setResultSets(resultSets);
        response.setResultSetCount(resultSets.size());
        response.setCancelled(cancelled);
        response.setMessage(message);
        response.setDurationMs(duration);

        SqlQueryResultSet first = resultSets.isEmpty() ? null : resultSets.get(0);
        List<String> columns = first != null ? safeColumns(first.getColumns()) : new ArrayList<>();
        List<Map<String, Object>> rows = first != null ? safeRows(first.getRows()) : new ArrayList<>();
        boolean hasMore = first != null && first.isHasMore();

        response.setColumns(columns);
        response.setRows(rows);
        response.setPreviewRowCount(rows.size());
        response.setHasMore(hasMore);

        if (!cancelled) {
            DataQueryHistory history = saveHistory(request, columns, rows, hasMore, duration);
            response.setHistoryId(history.getId());
            response.setExecutedAt(history.getExecutedAt());
        } else {
            response.setExecutedAt(LocalDateTime.now());
        }

        return response;
    }

    /**
     * 查询历史记录
     */
    public Page<DataQueryHistory> listHistory(Integer pageNum, Integer pageSize, Long clusterId, String database) {
        Page<DataQueryHistory> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        LambdaQueryWrapper<DataQueryHistory> wrapper = new LambdaQueryWrapper<DataQueryHistory>()
            .orderByDesc(DataQueryHistory::getExecutedAt);
        if (clusterId != null) {
            wrapper.eq(DataQueryHistory::getClusterId, clusterId);
        }
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataQueryHistory::getDatabaseName, database);
        }
        return historyMapper.selectPage(page, wrapper);
    }

    /**
     * 查询指定用户的历史记录
     */
    public Page<DataQueryHistory> listHistoryByUser(String userId, Integer pageNum, Integer pageSize, Long clusterId, String database) {
        Page<DataQueryHistory> page = new Page<>(pageNum == null ? 1 : pageNum, pageSize == null ? 10 : pageSize);
        LambdaQueryWrapper<DataQueryHistory> wrapper = new LambdaQueryWrapper<DataQueryHistory>()
            .eq(DataQueryHistory::getExecutedBy, userId)
            .orderByDesc(DataQueryHistory::getExecutedAt);
        if (clusterId != null) {
            wrapper.eq(DataQueryHistory::getClusterId, clusterId);
        }
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataQueryHistory::getDatabaseName, database);
        }
        return historyMapper.selectPage(page, wrapper);
    }

    void validateSql(String sql) {
        AnalyzeBundle bundle = analyzeStatements(sql, null, "", resolveUserId(), false);
        if (bundle.getAnalyzed().isEmpty()) {
            throw new RuntimeException("SQL 不能为空");
        }
        AnalyzedStatement first = bundle.getAnalyzed().get(0);
        if (first.isBlocked()) {
            throw new RuntimeException(first.getBlockedReason());
        }
        if (first.isRequiresConfirm()) {
            throw new RuntimeException("高风险 SQL 需强确认后执行");
        }
    }

    private AnalyzeBundle analyzeStatements(String sql,
                                            Long clusterId,
                                            String database,
                                            String userId,
                                            boolean includeToken) {
        List<String> statements = splitStatements(sql);
        List<AnalyzedStatement> analyzed = new ArrayList<>();

        int index = 1;
        for (String statement : statements) {
            String trimmed = String.valueOf(statement).trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }
            AnalyzedStatement item = analyzeStatement(index, trimmed, clusterId, database, userId, includeToken);
            analyzed.add(item);
            index++;
        }

        AnalyzeBundle bundle = new AnalyzeBundle();
        bundle.setStatements(statements);
        bundle.setAnalyzed(analyzed);
        return bundle;
    }

    private AnalyzedStatement analyzeStatement(int statementIndex,
                                               String sql,
                                               Long clusterId,
                                               String database,
                                               String userId,
                                               boolean includeToken) {
        AnalyzedStatement analyzed = new AnalyzedStatement();
        analyzed.setStatementIndex(statementIndex);
        analyzed.setSql(sql);
        analyzed.setSqlSnippet(abbreviate(sql));
        analyzed.setRiskLevel("LOW");
        analyzed.setParseStatus("SKIPPED");
        analyzed.setSqlType(guessSqlType(sql));
        analyzed.setFingerprint(fingerprint(sql));

        boolean keywordTriggered = RISK_TRIGGER_KEYWORDS.matcher(sql).find();
        if (!keywordTriggered) {
            return analyzed;
        }

        analyzed.setRiskLevel("HIGH");

        Statement parsed = null;
        try {
            parsed = CCJSqlParserUtil.parse(sql);
            String parsedType = detectSqlType(parsed, sql);
            analyzed.setSqlType(parsedType);
            String target = extractTargetFromParsed(parsed);
            if (!StringUtils.hasText(target)) {
                target = extractTargetByFallback(sql, parsedType);
                analyzed.setParseStatus(StringUtils.hasText(target) ? "FALLBACK" : "PARSED");
            } else {
                analyzed.setParseStatus("PARSED");
            }
            analyzed.setTargetObject(target);
            analyzed.setRequiresConfirm(isHighRiskSqlType(parsedType));
        } catch (JSQLParserException e) {
            log.debug("Parse SQL failed, fallback to keyword extraction, sql={}", abbreviate(sql), e);
            String guessedType = guessSqlType(sql);
            analyzed.setSqlType(guessedType);
            analyzed.setTargetObject(extractTargetByFallback(sql, guessedType));
            analyzed.setParseStatus(StringUtils.hasText(analyzed.getTargetObject()) ? "FALLBACK" : "FAILED");
            analyzed.setRequiresConfirm(true);
        }

        if (analyzed.isRequiresConfirm() && !StringUtils.hasText(analyzed.getTargetObject())) {
            analyzed.setBlocked(true);
            analyzed.setBlockedReason("高风险 SQL 无法识别目标对象，已阻止执行");
            return analyzed;
        }

        if (analyzed.isRequiresConfirm() && includeToken) {
            LocalDateTime expireAt = LocalDateTime.now().plusSeconds(TOKEN_TTL_SECONDS);
            String token = buildConfirmToken(userId, clusterId, database, analyzed.getStatementIndex(),
                analyzed.getTargetObject(), analyzed.getFingerprint(), expireAt);
            analyzed.setConfirmToken(token);
            analyzed.setConfirmExpireAt(expireAt);
        }

        return analyzed;
    }

    private boolean isHighRiskSqlType(String sqlType) {
        if (!StringUtils.hasText(sqlType)) {
            return false;
        }
        switch (sqlType.toUpperCase(Locale.ROOT)) {
            case "DROP":
            case "TRUNCATE":
            case "DELETE":
            case "UPDATE":
            case "INSERT":
            case "REPLACE":
            case "MERGE":
            case "ALTER":
                return true;
            default:
                return false;
        }
    }

    private String detectSqlType(Statement parsed, String sql) {
        if (parsed instanceof Drop) {
            return "DROP";
        }
        if (parsed instanceof Truncate) {
            return "TRUNCATE";
        }
        if (parsed instanceof Delete) {
            return "DELETE";
        }
        if (parsed instanceof Update) {
            return "UPDATE";
        }
        if (parsed instanceof Insert) {
            return "INSERT";
        }
        if (parsed instanceof Replace) {
            return "REPLACE";
        }
        if (parsed instanceof Merge) {
            return "MERGE";
        }
        if (parsed instanceof Alter) {
            return "ALTER";
        }
        return guessSqlType(sql);
    }

    private String extractTargetFromParsed(Statement parsed) {
        if (parsed instanceof Delete) {
            return toQualifiedName(((Delete) parsed).getTable());
        }
        if (parsed instanceof Update) {
            return toQualifiedName(((Update) parsed).getTable());
        }
        if (parsed instanceof Insert) {
            return toQualifiedName(((Insert) parsed).getTable());
        }
        if (parsed instanceof Replace) {
            return toQualifiedName(((Replace) parsed).getTable());
        }
        if (parsed instanceof Truncate) {
            return toQualifiedName(((Truncate) parsed).getTable());
        }
        if (parsed instanceof Alter) {
            return toQualifiedName(((Alter) parsed).getTable());
        }
        if (parsed instanceof Merge) {
            return toQualifiedName(((Merge) parsed).getTable());
        }
        if (parsed instanceof Drop) {
            Drop drop = (Drop) parsed;
            if (drop.getName() != null) {
                return normalizeIdentifier(drop.getName().toString());
            }
        }
        return null;
    }

    private String toQualifiedName(Table table) {
        if (table == null || !StringUtils.hasText(table.getName())) {
            return null;
        }
        String name = normalizeIdentifier(table.getName());
        String schema = normalizeIdentifier(table.getSchemaName());
        if (StringUtils.hasText(schema)) {
            return schema + "." + name;
        }
        return name;
    }

    private String extractTargetByFallback(String sql, String sqlType) {
        if (!StringUtils.hasText(sqlType)) {
            return null;
        }
        Pattern pattern;
        switch (sqlType.toUpperCase(Locale.ROOT)) {
            case "DROP":
                pattern = DROP_TARGET_PATTERN;
                break;
            case "TRUNCATE":
                pattern = TRUNCATE_TARGET_PATTERN;
                break;
            case "DELETE":
                pattern = DELETE_TARGET_PATTERN;
                break;
            case "UPDATE":
                pattern = UPDATE_TARGET_PATTERN;
                break;
            case "INSERT":
                pattern = INSERT_TARGET_PATTERN;
                break;
            case "REPLACE":
                pattern = REPLACE_TARGET_PATTERN;
                break;
            case "MERGE":
                pattern = MERGE_TARGET_PATTERN;
                break;
            case "ALTER":
                pattern = ALTER_TARGET_PATTERN;
                break;
            default:
                return null;
        }

        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            return null;
        }
        return normalizeIdentifier(matcher.group(1));
    }

    private String normalizeIdentifier(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String normalized = raw.trim();
        normalized = normalized.replace("`", "").replace("\"", "");
        normalized = normalized.split("\\s+")[0];
        if (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.trim();
    }

    private String guessSqlType(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "UNKNOWN";
        }
        Matcher matcher = SQL_TYPE_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return "UNKNOWN";
        }
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private SqlQueryResultSet executeStatement(java.sql.Statement statement, String sql, int limit) throws SQLException {
        SqlQueryResultSet output = new SqlQueryResultSet();
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasMore = false;

        boolean hasResultSet = statement.execute(sql);
        if (!hasResultSet) {
            int updateCount = statement.getUpdateCount();
            output.setColumns(columns);
            output.setRows(rows);
            output.setPreviewRowCount(0);
            output.setHasMore(false);
            output.setResultType(UPDATE_COUNT);
            output.setAffectedRows(updateCount >= 0 ? (long) updateCount : null);
            return output;
        }

        try (ResultSet resultSet = statement.getResultSet()) {
            if (resultSet == null) {
                output.setColumns(columns);
                output.setRows(rows);
                output.setPreviewRowCount(0);
                output.setHasMore(false);
                output.setResultType(RESULT_SET);
                return output;
            }
            ResultSetMetaData metaData = resultSet.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                columns.add(metaData.getColumnLabel(i));
            }

            int rowIndex = 0;
            while (resultSet.next()) {
                if (rowIndex >= limit) {
                    hasMore = true;
                    break;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(columns.get(i - 1), resultSet.getObject(i));
                }
                rows.add(row);
                rowIndex++;
            }
        }

        output.setColumns(columns);
        output.setRows(rows);
        output.setPreviewRowCount(rows.size());
        output.setHasMore(hasMore);
        output.setResultType(RESULT_SET);
        return output;
    }

    private SqlQueryResultSet buildControlResult(AnalyzedStatement analyzed,
                                                 String resultType,
                                                 String status,
                                                 String message,
                                                 long durationMs) {
        SqlQueryResultSet resultSet = new SqlQueryResultSet();
        resultSet.setStatementIndex(analyzed.getStatementIndex());
        resultSet.setSqlSnippet(analyzed.getSqlSnippet());
        resultSet.setResultType(resultType);
        resultSet.setStatus(status);
        resultSet.setMessage(message);
        resultSet.setDurationMs(durationMs);
        resultSet.setColumns(new ArrayList<>());
        resultSet.setRows(new ArrayList<>());
        resultSet.setPreviewRowCount(0);
        resultSet.setHasMore(false);
        return resultSet;
    }

    private void addControlResult(List<SqlQueryResultSet> resultSets,
                                  AnalyzedStatement analyzed,
                                  String resultType,
                                  String status,
                                  String message,
                                  long durationMs) {
        SqlQueryResultSet resultSet = buildControlResult(analyzed, resultType, status, message, durationMs);
        resultSet.setIndex(resultSets.size() + 1);
        resultSets.add(resultSet);
    }

    private String validateConfirmation(SqlQueryRequest.SqlConfirmation confirmation,
                                        AnalyzedStatement analyzed,
                                        SqlQueryRequest request,
                                        String userId) {
        if (confirmation == null) {
            return "高风险 SQL 未确认，已阻止执行";
        }
        if (!StringUtils.hasText(confirmation.getInputText())) {
            return "强确认失败：请输入对象名";
        }
        if (!StringUtils.hasText(confirmation.getTargetObject())) {
            return "强确认失败：缺少目标对象";
        }
        String expectedTarget = normalizeIdentifier(analyzed.getTargetObject());
        String actualTarget = normalizeIdentifier(confirmation.getTargetObject());
        if (!StringUtils.hasText(expectedTarget) || !expectedTarget.equals(actualTarget)) {
            return "强确认失败：目标对象不匹配";
        }
        String input = normalizeIdentifier(confirmation.getInputText());
        if (!expectedTarget.equals(input)) {
            return "强确认失败：输入对象名不匹配";
        }
        if (!StringUtils.hasText(confirmation.getConfirmToken())) {
            return "强确认失败：缺少确认令牌";
        }

        TokenValidation validation = validateConfirmToken(
            confirmation.getConfirmToken(),
            userId,
            request.getClusterId(),
            request.getDatabase(),
            analyzed.getStatementIndex(),
            expectedTarget,
            analyzed.getFingerprint()
        );
        if (!validation.isValid()) {
            return "强确认失败：" + validation.getReason();
        }
        return null;
    }

    private TokenValidation validateConfirmToken(String token,
                                                 String userId,
                                                 Long clusterId,
                                                 String database,
                                                 Integer statementIndex,
                                                 String targetObject,
                                                 String fingerprint) {
        if (!StringUtils.hasText(token)) {
            return TokenValidation.fail("确认令牌为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            return TokenValidation.fail("确认令牌格式错误");
        }

        String payloadBase64 = parts[0];
        String signatureBase64 = parts[1];
        String expectedSignature = signPayload(payloadBase64);
        if (!MessageDigest.isEqual(signatureBase64.getBytes(StandardCharsets.UTF_8),
            expectedSignature.getBytes(StandardCharsets.UTF_8))) {
            return TokenValidation.fail("确认令牌签名不合法");
        }

        try {
            String payloadJson = new String(Base64.getUrlDecoder().decode(payloadBase64), StandardCharsets.UTF_8);
            ConfirmTokenPayload payload = objectMapper.readValue(payloadJson, ConfirmTokenPayload.class);

            long now = Instant.now().getEpochSecond();
            if (payload.getExp() == null || payload.getExp() < now) {
                return TokenValidation.fail("确认令牌已过期");
            }
            if (!safeEquals(payload.getUserId(), userId)) {
                return TokenValidation.fail("用户不匹配");
            }
            if (!safeEquals(payload.getClusterId(), clusterId)) {
                return TokenValidation.fail("集群不匹配");
            }
            if (!safeEquals(payload.getDatabase(), database)) {
                return TokenValidation.fail("数据库不匹配");
            }
            if (!safeEquals(payload.getStatementIndex(), statementIndex)) {
                return TokenValidation.fail("语句索引不匹配");
            }
            if (!safeEquals(payload.getTargetObject(), targetObject)) {
                return TokenValidation.fail("目标对象不匹配");
            }
            if (!safeEquals(payload.getFingerprint(), fingerprint)) {
                return TokenValidation.fail("SQL 内容不匹配");
            }
            return TokenValidation.ok();
        } catch (Exception e) {
            return TokenValidation.fail("确认令牌解析失败");
        }
    }

    private String buildConfirmToken(String userId,
                                     Long clusterId,
                                     String database,
                                     Integer statementIndex,
                                     String targetObject,
                                     String fingerprint,
                                     LocalDateTime expireAt) {
        ConfirmTokenPayload payload = new ConfirmTokenPayload();
        payload.setUserId(userId);
        payload.setClusterId(clusterId);
        payload.setDatabase(database);
        payload.setStatementIndex(statementIndex);
        payload.setTargetObject(targetObject);
        payload.setFingerprint(fingerprint);
        payload.setExp(expireAt.toEpochSecond(java.time.ZoneOffset.UTC));

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadBase64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = signPayload(payloadBase64);
            return payloadBase64 + "." + signature;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("生成确认令牌失败", e);
        }
    }

    private String signPayload(String payloadBase64) {
        try {
            Mac mac = Mac.getInstance(TOKEN_HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(resolveTokenSecret().getBytes(StandardCharsets.UTF_8), TOKEN_HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payloadBase64.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("签名失败", e);
        }
    }

    private String resolveTokenSecret() {
        String env = System.getenv(TOKEN_SECRET_ENV);
        return StringUtils.hasText(env) ? env : TOKEN_FALLBACK_SECRET;
    }

    private int countByStatus(List<SqlQueryResultSet> resultSets, String status) {
        int count = 0;
        for (SqlQueryResultSet set : resultSets) {
            if (set != null && status.equals(set.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private List<String> safeColumns(List<String> columns) {
        return columns == null ? new ArrayList<>() : columns;
    }

    private List<Map<String, Object>> safeRows(List<Map<String, Object>> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }

    private String resolveUserId() {
        String userId = com.onedata.auth.context.UserContextHolder.getCurrentUserId();
        return userId == null ? "" : userId;
    }

    private DataQueryHistory saveHistory(SqlQueryRequest request, List<String> columns, List<Map<String, Object>> rows, boolean hasMore, long duration) {
        DataQueryHistory history = new DataQueryHistory();
        history.setClusterId(request.getClusterId());
        history.setClusterName(resolveClusterName(request.getClusterId()));
        history.setDatabaseName(request.getDatabase());
        history.setSqlText(request.getSql().trim());
        history.setPreviewRowCount(rows.size());
        history.setDurationMs(duration);
        history.setHasMore(hasMore ? 1 : 0);
        history.setResultPreview(buildPreviewJson(columns, rows));
        history.setExecutedAt(LocalDateTime.now());
        history.setExecutedBy(com.onedata.auth.context.UserContextHolder.getCurrentUserId());
        historyMapper.insert(history);
        return history;
    }

    private String resolveClusterName(Long clusterId) {
        if (clusterId == null) {
            return "默认集群";
        }
        DorisCluster cluster = dorisClusterService.getById(clusterId);
        return cluster != null ? cluster.getClusterName() : "集群#" + clusterId;
    }

    private String buildPreviewJson(List<String> columns, List<Map<String, Object>> rows) {
        List<Map<String, Object>> previewRows = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> row : rows) {
            if (index >= PREVIEW_LIMIT) {
                break;
            }
            previewRows.add(new LinkedHashMap<>(row));
            index++;
        }
        try {
            return objectMapper.writeValueAsString(new QueryPreview(columns, previewRows));
        } catch (JsonProcessingException e) {
            log.warn("Serialize query preview failed", e);
            return null;
        }
    }

    private String abbreviate(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        String compressed = sql.replaceAll("\\s+", " ").trim();
        return compressed.length() > 200 ? compressed.substring(0, 200) + "..." : compressed;
    }

    private String fingerprint(String sql) {
        String normalized = StringUtils.hasText(sql) ? sql.replaceAll("\\s+", " ").trim() : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return normalized;
        }
    }

    private String buildRunningKey(String userId, String clientQueryId) {
        return userId + "::" + clientQueryId;
    }

    private List<String> splitStatements(String sql) {
        if (!StringUtils.hasText(sql)) {
            return new ArrayList<>();
        }
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inHashComment = false;
        boolean inBlockComment = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    inLineComment = false;
                }
                continue;
            }

            if (inHashComment) {
                current.append(ch);
                if (ch == '\n' || ch == '\r') {
                    inHashComment = false;
                }
                continue;
            }

            if (inBlockComment) {
                current.append(ch);
                if (ch == '*' && next == '/') {
                    current.append(next);
                    inBlockComment = false;
                    i++;
                }
                continue;
            }

            if (inSingleQuote) {
                current.append(ch);
                if (ch == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                    continue;
                }
                if (ch == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }

            if (inDoubleQuote) {
                current.append(ch);
                if (ch == '"' && next == '"') {
                    current.append(next);
                    i++;
                    continue;
                }
                if (ch == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }

            if (ch == '-' && next == '-') {
                inLineComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }

            if (ch == '#') {
                inHashComment = true;
                current.append(ch);
                continue;
            }

            if (ch == '/' && next == '*') {
                inBlockComment = true;
                current.append(ch).append(next);
                i++;
                continue;
            }

            if (ch == '\'') {
                inSingleQuote = true;
                current.append(ch);
                continue;
            }

            if (ch == '"') {
                inDoubleQuote = true;
                current.append(ch);
                continue;
            }

            if (ch == ';') {
                String stmt = current.toString().trim();
                if (StringUtils.hasText(stmt)) {
                    statements.add(stmt);
                }
                current.setLength(0);
                continue;
            }

            current.append(ch);
        }

        String stmt = current.toString().trim();
        if (StringUtils.hasText(stmt)) {
            statements.add(stmt);
        }
        return statements;
    }

    private boolean safeEquals(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
