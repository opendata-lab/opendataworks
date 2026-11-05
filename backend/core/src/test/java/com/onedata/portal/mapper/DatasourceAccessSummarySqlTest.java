package com.onedata.portal.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Runs the real {@link DatasourceAccessPermissionMapper#listAccessSummaryRows} SQL on DuckDB. */
class DatasourceAccessSummarySqlTest {
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.duckdb.DuckDBDriver");
        conn = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement st = conn.createStatement()) {
            st.execute(
                    "CREATE TABLE doris_cluster (id BIGINT, cluster_name VARCHAR, deleted"
                            + " INTEGER)");
            st.execute(
                    "CREATE TABLE datasource_access_member (id BIGINT, source_id BIGINT,"
                            + " subject_type VARCHAR, subject_id VARCHAR, status VARCHAR)");
            st.execute(
                    "CREATE TABLE datasource_access_member_role (member_id BIGINT, role_id"
                            + " BIGINT)");
            st.execute("CREATE TABLE datasource_access_role (id BIGINT, name VARCHAR)");
            st.execute(
                    "CREATE TABLE datasource_subject_account (source_id BIGINT, subject_type"
                            + " VARCHAR, subject_id VARCHAR)");
            // USER 7 on source 1 already has a member row; USER 9 is bound without one.
            st.execute(
                    "INSERT INTO datasource_subject_account VALUES (1, 'USER', '7'), (1, 'USER',"
                            + " '9'), (2, 'USER', '9')");
            st.execute("INSERT INTO doris_cluster VALUES (1, 'MySQL A', 0), (2, 'Old PG', 1)");
            st.execute("INSERT INTO datasource_access_role VALUES (10, 'B只读'), (11, 'A只读')");
            // member 100: two roles on live source 1; member 101: no roles; member 102: deleted
            // source
            st.execute(
                    "INSERT INTO datasource_access_member VALUES (100, 1, 'USER', '7', 'ACTIVE'),"
                        + " (101, 1, 'SERVICE', '8', 'ERROR'), (102, 2, 'USER', '7', 'ACTIVE')");
            st.execute(
                    "INSERT INTO datasource_access_member_role VALUES (100, 10), (100, 11), (102,"
                            + " 10)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void listsRolesPerMemberOnLiveSourcesIncludingMembersWithoutRoles() throws Exception {
        String sql =
                DatasourceAccessPermissionMapper.class
                        .getMethod("listAccessSummaryRows")
                        .getAnnotation(Select.class)
                        .value()[0];
        List<String> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) addRow(rows, rs);
        }
        rows.sort(null);
        assertEquals(
                List.of(
                        "SERVICE/8@1 MySQL A ERROR null",
                        "USER/7@1 MySQL A ACTIVE A只读",
                        "USER/7@1 MySQL A ACTIVE B只读",
                        "USER/9@1 MySQL A ACTIVE null"),
                rows);
    }

    private static void addRow(List<String> rows, ResultSet rs) throws Exception {
        rows.add(
                rs.getString("subject_type")
                        + "/"
                        + rs.getString("subject_id")
                        + "@"
                        + rs.getLong("source_id")
                        + " "
                        + rs.getString("cluster_name")
                        + " "
                        + rs.getString("status")
                        + " "
                        + rs.getString("role_name"));
    }
}
