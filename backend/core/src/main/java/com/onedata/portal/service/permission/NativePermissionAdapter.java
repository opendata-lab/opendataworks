package com.onedata.portal.service.permission;

import com.onedata.portal.service.permission.PermissionModels.Atom;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.*;
import java.util.*;

/**
 * Native ACL operations of one engine; no SQL query rewriting or user permission emulation. Each
 * engine's {@code DatasourceAdapter#permissions()} returns its own subclass, so engine quirks stay
 * in that subclass. This base only holds what every engine shares.
 */
public abstract class NativePermissionAdapter {
    protected static final List<String> RESOURCES = Arrays.asList("SCHEMA", "TABLE", "VIEW");

    /** Engine key used in user-facing messages, e.g. {@code MYSQL}. */
    protected abstract String engine();

    public boolean postgres() {
        return false;
    }

    public boolean doris() {
        return false;
    }

    /** Checks the server version and reads connection-level settings. */
    public void configure(Connection c) throws SQLException {}

    public boolean literalDatabaseNames() {
        return false;
    }

    public void validateSchemaScope(String database) {}

    protected abstract String quote();

    public String identifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("无效的数据库对象名");
        String quote = quote();
        return quote + name.replace(quote, quote + quote) + quote;
    }

    public static String literal(String text) {
        if (text == null || text.indexOf('\0') >= 0 || text.indexOf('\\') >= 0)
            throw new IllegalArgumentException("账号标识不能含反斜线或空字符");
        return "'" + text.replace("'", "''") + "'";
    }

    /** A managed role as it appears in GRANT/REVOKE statements. */
    public abstract String role(String role);

    public abstract String account(Identity identity);

    public abstract String createLogin(Identity identity, String password);

    public abstract String dropLogin(Identity identity);

    public String createRole(String name) {
        return "CREATE ROLE " + role(name);
    }

    public String dropRole(String name) {
        return "DROP ROLE " + role(name);
    }

    public String membership(String name, Identity user, boolean grant) {
        return (grant ? "GRANT " : "REVOKE ")
                + role(name)
                + (grant ? " TO " : " FROM ")
                + account(user);
    }

    public abstract String grantSql(String name, Atom atom, boolean grant);

    protected static String grantStatement(
            String privilege, String target, boolean grant, String grantee) {
        return (grant ? "GRANT " : "REVOKE ")
                + privilege
                + " ON "
                + target
                + (grant ? " TO " : " FROM ")
                + grantee;
    }

    public List<String> nativeActions(String action, String resourceType) {
        if (!RESOURCES.contains(resourceType))
            throw new IllegalArgumentException("资源类型必须为SCHEMA、TABLE或VIEW");
        List<String> result =
                nativeActions(action, "SCHEMA".equals(resourceType), "VIEW".equals(resourceType));
        if (result != null) return result;
        throw new IllegalArgumentException(
                "UNSUPPORTED_ACTION: "
                        + action
                        + " 不适用于 "
                        + engine()
                        + "/"
                        + resourceType
                        + unsupportedHint(action));
    }

    /** Native privileges for a platform action at one scope, or null when not applicable. */
    protected abstract List<String> nativeActions(String action, boolean schema, boolean view);

    protected String unsupportedHint(String action) {
        return "";
    }

    protected String actionNote(String action) {
        return "";
    }

    protected String schemaLabel() {
        return "数据库";
    }

    protected abstract String scopeNote();

    public Map<String, Object> capabilities() {
        List<Map<String, Object>> actions = new ArrayList<>();
        String[][] labels = {
            {"QUERY", "查询数据"},
            {"WRITE", "写入数据"},
            {"INSERT", "新增数据"},
            {"UPDATE", "更新数据"},
            {"DELETE", "删除数据"},
            {"CREATE", "创建对象"},
            {"ALTER", "修改结构"},
            {"DROP", "删除对象"},
            {"SHOW_VIEW", "查看视图定义"}
        };
        for (String[] label : labels) {
            List<String> kinds = new ArrayList<>();
            List<String> nativeNames = new ArrayList<>();
            for (String kind : RESOURCES) {
                try {
                    List<String> names = nativeActions(label[0], kind);
                    kinds.add(kind);
                    if (nativeNames.isEmpty()) nativeNames.addAll(names);
                } catch (IllegalArgumentException ignored) {
                    /* The action is not applicable at this scope. */
                }
            }
            if (kinds.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", label[0]);
            row.put("label", label[1]);
            row.put("resourceTypes", kinds);
            row.put("nativeActions", nativeNames);
            row.put("note", actionNote(label[0]));
            actions.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceTypes", RESOURCES);
        result.put("actions", actions);
        result.put("schemaLabel", schemaLabel());
        result.put("note", scopeNote());
        return result;
    }

    public abstract boolean roleExists(Connection c, String name) throws SQLException;

    public abstract Set<Atom> readRole(Connection c, String name) throws SQLException;

    /** The verified identity of the connection, which must match the requested account name. */
    public abstract Identity identity(Connection c, String requestedName) throws SQLException;

    public abstract Set<String> memberships(Connection c, Identity identity) throws SQLException;

    public abstract boolean identityExists(Connection c, Identity identity) throws SQLException;

    /** SQL that makes the managed roles active by default, or null when the engine needs none. */
    public String defaultRoleSql(
            Connection c, Identity identity, Set<String> managedBefore, Set<String> managedAfter)
            throws SQLException {
        return null;
    }

    public void verifyActiveRoles(Connection c, Set<String> desired) throws SQLException {}

    /** Readable native grants of the already verified member session. */
    public abstract List<String> inspect(Connection c, Identity identity) throws SQLException;

    public List<Atom> sequences(Connection c, String database, String table) throws SQLException {
        return Collections.emptyList();
    }
}
