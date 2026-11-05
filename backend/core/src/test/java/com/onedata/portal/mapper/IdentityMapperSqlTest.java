package com.onedata.portal.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.onedata.auth.user.SysUserMapper;

import org.apache.ibatis.annotations.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.*;

/**
 * Exercise production annotation SQL, not test-owned copies. Lock semantics are verified with MySQL
 * smoke.
 */
class IdentityMapperSqlTest {
    private String sql(Class<?> type, String name) {
        for (Method method : type.getMethods())
            if (method.getName().equals(name)) {
                Select select = method.getAnnotation(Select.class);
                Update update = method.getAnnotation(Update.class);
                return String.join(" ", select != null ? select.value() : update.value())
                        .replaceAll("#\\{[^}]+}", "?");
            }
        throw new AssertionError(name);
    }

    @Test
    void userSessionInvalidationAndSoftDeleteRetainContract() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE sys_user(id BIGINT,username VARCHAR,role VARCHAR,enabled"
                        + " INTEGER,deleted INTEGER,session_version INTEGER,password_hash"
                        + " VARCHAR,failed_attempts INTEGER,locked_until TIMESTAMP,updated_at"
                        + " TIMESTAMP)");
            s.execute(
                    "INSERT INTO sys_user"
                        + " VALUES(1,'admin','admin',1,0,2,'old',3,NULL,NULL),(2,'removed','user',0,1,9,'old',0,NULL,NULL)");
            try (PreparedStatement q =
                    c.prepareStatement(sql(SysUserMapper.class, "countAllByUsername"))) {
                q.setString(1, "removed");
                try (ResultSet rs = q.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong(1));
                }
            }
            try (PreparedStatement q =
                    c.prepareStatement(sql(SysUserMapper.class, "incrementSessionVersion"))) {
                q.setLong(1, 1);
                assertEquals(1, q.executeUpdate());
            }
            try (PreparedStatement q =
                    c.prepareStatement(
                            sql(SysUserMapper.class, "updatePasswordAndInvalidateSession"))) {
                q.setString(1, "new");
                q.setLong(2, 1);
                assertEquals(1, q.executeUpdate());
            }
            try (ResultSet rs =
                    s.executeQuery(
                            "SELECT session_version,password_hash,failed_attempts FROM sys_user"
                                + " WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1));
                assertEquals("new", rs.getString(2));
                assertEquals(0, rs.getInt(3));
            }
            try (PreparedStatement q =
                    c.prepareStatement(
                            sql(SysUserMapper.class, "softDeleteAndInvalidateSession"))) {
                q.setLong(1, 1);
                assertEquals(1, q.executeUpdate());
                assertEquals(0, q.executeUpdate());
            }
            try (ResultSet rs =
                    s.executeQuery(
                            "SELECT deleted,enabled,session_version FROM sys_user WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
                assertEquals(0, rs.getInt(2));
                assertEquals(5, rs.getInt(3));
            }
        }
    }

    @Test
    void uncertainMemberGuardIncludesRoleStateAndIsolatesSubjects() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE datasource_access_member(id BIGINT,source_id BIGINT,subject_type"
                        + " VARCHAR,subject_id VARCHAR,status VARCHAR)");
            s.execute(
                    "CREATE TABLE datasource_access_member_role(member_id BIGINT,role_id BIGINT)");
            s.execute("CREATE TABLE datasource_access_role(id BIGINT,status VARCHAR)");
            s.execute(
                    "INSERT INTO datasource_access_member"
                        + " VALUES(1,10,'SERVICE','7','ACTIVE'),(2,10,'USER','7','ERROR')");
            s.execute("INSERT INTO datasource_access_member_role VALUES(1,3)");
            s.execute("INSERT INTO datasource_access_role VALUES(3,'APPLYING')");
            try (PreparedStatement q =
                    c.prepareStatement(
                            sql(DatasourceAccessPermissionMapper.class, "countUncertain"))) {
                q.setLong(1, 10);
                q.setString(2, "SERVICE");
                q.setString(3, "7");
                try (ResultSet rs = q.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getLong(1));
                }
                s.execute("UPDATE datasource_access_role SET status='ACTIVE'");
                try (ResultSet rs = q.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getLong(1));
                }
            }
        }
    }
}
