package com.onedata.portal.service.table;

import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Plain JDBC executor for non-Doris table engines configured in doris_cluster.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableEngineJdbcExecutor {

    private final DorisClusterMapper dorisClusterMapper;
    private final DorisConnectionService connectionService;

    public void execute(Long datasourceId, String database, String sql, String expectedType) {
        DorisCluster datasource = resolveDatasource(datasourceId, expectedType);
        try (Connection connection = getConnection(datasource, database);
                Statement statement = connection.createStatement()) {
            log.info("Executing SQL on {} datasource {} (db={}): {}",
                    expectedType, datasource.getClusterName(), database, abbreviate(sql));
            statement.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL on {} datasource {} (db={})",
                    expectedType, datasource.getClusterName(), database, e);
            throw new RuntimeException("执行 " + expectedType + " SQL 失败: " + e.getMessage(), e);
        }
    }

    private Connection getConnection(DorisCluster datasource, String database) throws SQLException {
        return connectionService.getConnection(datasource.getId(), database);
    }

    private DorisCluster resolveDatasource(Long datasourceId, String expectedType) {
        if (datasourceId == null) {
            throw new RuntimeException("请指定数据源");
        }
        DorisCluster datasource = dorisClusterMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new RuntimeException("数据源不存在: " + datasourceId);
        }
        String actualType = DatasourceAdapterRegistry.normalize(datasource.getSourceType());
        if (!actualType.equals(DatasourceAdapterRegistry.normalize(expectedType))) {
            throw new RuntimeException("数据源类型不匹配，期望 " + expectedType + "，实际 " + actualType);
        }
        return datasource;
    }

    private String abbreviate(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        String trimmed = sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }
}
