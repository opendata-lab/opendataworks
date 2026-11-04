package com.onedata.portal.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内存 DuckDB 直接跑 {@link TableTaskRelationMapper#selectWorkflowTableRelations} 和
 * {@link TableTaskRelationMapper#selectAllTableIdsByWorkflow} 注解里的真实 SQL。
 */
@DisplayName("工作流关联表查询 SQL(DuckDB)")
class TableTaskRelationMapperWorkflowTablesSqlTest {

    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE data_task (id BIGINT, deleted INTEGER)");
            st.execute("CREATE TABLE workflow_task_relation (workflow_id BIGINT, task_id BIGINT, deleted INTEGER)");
            st.execute("CREATE TABLE table_task_relation (table_id BIGINT, task_id BIGINT, relation_type VARCHAR, deleted INTEGER)");

            // 目标工作流 100 的有效任务 10：写出 1000、读取 1001
            st.execute("INSERT INTO data_task VALUES (10, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 10, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (1000, 10, 'write', 0)");
            st.execute("INSERT INTO table_task_relation VALUES (1001, 10, 'read', 0)");

            // 目标工作流 100 的有效任务 13：也写出 1001（使 1001 成为既读又写）
            st.execute("INSERT INTO data_task VALUES (13, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 13, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (1001, 13, 'write', 0)");

            // 任务 11 属于工作流 100，但任务本身被软删 -> 关联应排除
            st.execute("INSERT INTO data_task VALUES (11, 1)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 11, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (1002, 11, 'write', 0)");

            // 任务 12 的工作流关系被软删 -> 关联应排除
            st.execute("INSERT INTO data_task VALUES (12, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 12, 1)");
            st.execute("INSERT INTO table_task_relation VALUES (1003, 12, 'read', 0)");

            // 任务 10 另有一条被软删的关联 1004 -> 应排除
            st.execute("INSERT INTO table_task_relation VALUES (1004, 10, 'write', 1)");

            // 其它工作流 200 的表 2000 -> 查询 100 时应排除
            st.execute("INSERT INTO data_task VALUES (20, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (200, 20, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (2000, 20, 'read', 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("selectWorkflowTableRelations 返回工作流有效读写关联")
    void returnsActiveWorkflowTableRelations() throws Exception {
        String sql = annotatedSql("selectWorkflowTableRelations").replace("#{workflowId}", "?");
        List<String> items = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 100L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(rs.getLong(1) + ":" + rs.getString(2));
                }
            }
        }
        assertEquals(3, items.size(), "工作流 100 应命中 3 条有效关联记录 (1000:write, 1001:read, 1001:write)");
        assertTrue(items.contains("1000:write"));
        assertTrue(items.contains("1001:read"));
        assertTrue(items.contains("1001:write"));
    }

    @Test
    @DisplayName("selectAllTableIdsByWorkflow 去重返回所有读写关联表 ID")
    void returnsAllDistinctTableIdsOfWorkflow() throws Exception {
        String sql = annotatedSql("selectAllTableIdsByWorkflow").replace("#{workflowId}", "?");
        Set<Long> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 100L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        assertEquals(2, ids.size(), "去重后应有两张表：1000 与 1001");
        assertTrue(ids.contains(1000L));
        assertTrue(ids.contains(1001L));
    }

    private static String annotatedSql(String methodName) throws NoSuchMethodException {
        Select select = TableTaskRelationMapper.class
            .getMethod(methodName, Long.class)
            .getAnnotation(Select.class);
        return String.join("\n", select.value());
    }
}
