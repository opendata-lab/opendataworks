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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 用内存 DuckDB 直接跑 {@link TableTaskRelationMapper#selectWriteTableIdsByWorkflow} 注解里的真实 SQL，
 * 锁住「工作流写出表」查询契约：必须经 workflow_task_relation 关联，并尊重 write / deleted 过滤。
 *
 * <p>不依赖 MySQL、不启动 Spring：反射取注解 SQL、把 {@code #{workflowId}} 换成 JDBC 占位符后执行。
 * 一旦回归成历史上的 {@code data_task.workflow_id}（该列不存在），DuckDB 解析即报错，测试立即失败。
 */
@DisplayName("工作流写出表查询 SQL(DuckDB)")
class TableTaskRelationMapperWriteTablesSqlTest {

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

            // 任务 11 属于工作流 100，但任务本身被软删 -> 写出 1002 应排除
            st.execute("INSERT INTO data_task VALUES (11, 1)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 11, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (1002, 11, 'write', 0)");

            // 任务 12 的工作流关系被软删 -> 写出 1003 应排除
            st.execute("INSERT INTO data_task VALUES (12, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (100, 12, 1)");
            st.execute("INSERT INTO table_task_relation VALUES (1003, 12, 'write', 0)");

            // 任务 10 另有一条被软删的写关系 1004 -> 应排除
            st.execute("INSERT INTO table_task_relation VALUES (1004, 10, 'write', 1)");

            // 其它工作流 200 的写出 2000 -> 查询 100 时应排除
            st.execute("INSERT INTO data_task VALUES (20, 0)");
            st.execute("INSERT INTO workflow_task_relation VALUES (200, 20, 0)");
            st.execute("INSERT INTO table_task_relation VALUES (2000, 20, 'write', 0)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("只返回经 workflow_task_relation 关联、write、未删除的写出表")
    void returnsOnlyActiveWriteTablesOfWorkflow() throws Exception {
        String sql = annotatedSql().replace("#{workflowId}", "?");
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, 100L);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        assertEquals(1, ids.size(), "工作流 100 只应命中一张有效写出表，实际: " + ids);
        assertEquals(1000L, ids.get(0).longValue(), "命中的应是有效写出表 1000");
    }

    /** 反射取出 mapper 方法上的真实 @Select SQL，保证测试锁住的是生产注解本身。 */
    private static String annotatedSql() throws NoSuchMethodException {
        Select select = TableTaskRelationMapper.class
            .getMethod("selectWriteTableIdsByWorkflow", Long.class)
            .getAnnotation(Select.class);
        return String.join("\n", select.value());
    }
}
