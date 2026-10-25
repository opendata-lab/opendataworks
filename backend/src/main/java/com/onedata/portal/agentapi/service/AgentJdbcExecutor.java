package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceReferenceDdlReader;
import com.onedata.portal.util.JdbcValues;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJdbcExecutor {

    private final DatasourceAdapterRegistry adapterRegistry;
    private final DatasourceConnectionFactory connectionFactory;

    public QueryExecutionResult executeReadOnlyQuery(
            AgentDatasourceResolution datasource,
            String sql,
            int limit,
            int timeoutSeconds
    ) {
        long start = System.currentTimeMillis();
        try (Connection connection = openConnection(datasource, datasource.getDatabase());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            // 只多取一行用于判断 has_more；不设上限时驱动会把整个结果集读进内存
            statement.setMaxRows(limit + 1);
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                throw new IllegalArgumentException("只读 SQL 必须返回结果集");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            boolean hasMore = false;
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next()) {
                    if (rows.size() >= limit) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), JdbcValues.read(resultSet, i));
                    }
                    rows.add(row);
                }
            }

            QueryExecutionResult result = new QueryExecutionResult();
            result.setRows(rows);
            result.setRowCount(rows.size());
            result.setHasMore(hasMore);
            result.setDurationMs((int) (System.currentTimeMillis() - start));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("执行只读 SQL 失败: " + e.getMessage(), e);
        }
    }

    public String fetchTableDdl(
            AgentDatasourceResolution datasource,
            String database,
            String tableName,
            int timeoutSeconds
    ) {
        String type = StringUtils.hasText(datasource.getSourceType())
                ? datasource.getSourceType() : datasource.getEngine();
        DatasourceAdapter adapter = adapterRegistry.require(type);
        if (!(adapter instanceof DatasourceReferenceDdlReader)) {
            throw new UnsupportedOperationException("此数据源不支持参考结构读取");
        }
        try (Connection connection = openConnection(datasource, database)) {
            return ((DatasourceReferenceDdlReader) adapter)
                    .referenceDdl(connection, database, tableName, timeoutSeconds);
        } catch (SQLException e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }
    }

    private Connection openConnection(AgentDatasourceResolution datasource, String database) throws SQLException {
        String type = datasource.getSourceType();
        if (!StringUtils.hasText(type)) {
            type = datasource.getEngine();
        }
        String target = StringUtils.hasText(database) ? database.trim() : datasource.getDatabase();
        DatasourceAdapter adapter = adapterRegistry.require(type);
        ConnectionConfig config =
                new ConnectionConfig(
                        datasource.getHost(), datasource.getPort() == null
                            ? adapter.descriptor().getDefaultPort() : datasource.getPort(),
                        trimToEmpty(datasource.getUser()), datasource.getConnectionDatabase(),
                        datasource.getConnectionOptions());
        return connectionFactory.open(type, config, target,
                trimToEmpty(datasource.getUser()), trimToEmpty(datasource.getPassword()), true);
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    public static class QueryExecutionResult {
        private List<Map<String, Object>> rows = new ArrayList<>();
        private int rowCount;
        private boolean hasMore;
        private int durationMs;
    }
}
