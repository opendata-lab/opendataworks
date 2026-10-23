package com.onedata.portal.service.table;

import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.util.DatasourceType;
import com.onedata.portal.util.JdbcUrlParts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
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

    public void execute(Long datasourceId, String database, String sql, DatasourceType expectedType) {
        DorisCluster datasource = resolveDatasource(datasourceId, expectedType);
        try (Connection connection = getConnection(datasource, database);
                Statement statement = connection.createStatement()) {
            log.info("Executing SQL on {} datasource {} (db={}): {}",
                    expectedType.name(), datasource.getClusterName(), database, abbreviate(sql));
            statement.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL on {} datasource {} (db={})",
                    expectedType.name(), datasource.getClusterName(), database, e);
            throw new RuntimeException("执行 " + expectedType.name() + " SQL 失败: " + e.getMessage(), e);
        }
    }

    private Connection getConnection(DorisCluster datasource, String database) throws SQLException {
        String url = buildJdbcUrl(datasource, database);
        String username = datasource.getUsername();
        String password = datasource.getPassword() == null ? "" : datasource.getPassword();
        return DriverManager.getConnection(url, username, password);
    }

    private String buildJdbcUrl(DorisCluster datasource, String database) {
        String targetDb = StringUtils.hasText(database) ? database : "information_schema";
        return String.format(
                "jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true",
                JdbcUrlParts.requireHost(datasource.getFeHost()), datasource.getFePort(),
                JdbcUrlParts.requireDatabase(targetDb));
    }

    private DorisCluster resolveDatasource(Long datasourceId, DatasourceType expectedType) {
        if (datasourceId == null) {
            throw new RuntimeException("请指定数据源");
        }
        DorisCluster datasource = dorisClusterMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new RuntimeException("数据源不存在: " + datasourceId);
        }
        DatasourceType actualType = DatasourceType.from(datasource.getSourceType());
        if (actualType != expectedType) {
            throw new RuntimeException("数据源类型不匹配，期望 " + expectedType.name() + "，实际 " + actualType.name());
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
