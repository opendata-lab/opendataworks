package com.onedata.portal.service.datasource.adapters.mysql;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.onedata.portal.service.permission.PermissionModels.Atom;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** MySQL 8 native roles: roles are lockable accounts {@code 'role'@'%'}. */
public class MysqlPermissionAdapter extends MysqlProtocolPermissionAdapter {
    private boolean literalDatabaseNames;

    @Override
    protected String engine() {
        return "MYSQL";
    }

    @Override
    public void configure(Connection c) throws SQLException {
        if (c.getMetaData().getDatabaseMajorVersion() < 8)
            throw new IllegalArgumentException("UNSUPPORTED_ACTION: 原生角色管理要求MySQL8及以上");
        literalDatabaseNames =
                ((Number) rows(c, "SELECT @@partial_revokes AS enabled").get(0).get("enabled"))
                                .intValue()
                        != 0;
    }

    @Override
    public boolean literalDatabaseNames() {
        return literalDatabaseNames;
    }

    @Override
    public void validateSchemaScope(String database) {
        if (!literalDatabaseNames && (database.contains("_") || database.contains("%")))
            throw new IllegalArgumentException(
                    "UNSUPPORTED_ACTION:"
                        + " 当前MySQL库级角色授权对含_或%的库名存在原生通配符限制（Bug110582）；请按表／视图授权，或由DBA评估数据库修复版本／partial_revokes配置。平台不会放宽授权或修改全局配置。");
    }

    @Override
    public String role(String role) {
        return literal(role) + "@'%'";
    }

    @Override
    public String grantSql(String name, Atom atom, boolean grant) {
        boolean schema = "SCHEMA".equals(atom.getKind());
        String database = atom.getDatabase();
        if (schema && !literalDatabaseNames)
            database = database.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
        String target = identifier(database) + (schema ? ".*" : "." + identifier(atom.getObject()));
        return grantStatement(atom.getPrivilege(), target, grant, role(name));
    }

    @Override
    protected List<String> nativeActions(String action, boolean schema, boolean view) {
        switch (action) {
            case "QUERY":
                return Collections.singletonList("SELECT");
            case "WRITE":
                return Arrays.asList("INSERT", "UPDATE", "DELETE");
            case "INSERT":
            case "UPDATE":
            case "DELETE":
                return Collections.singletonList(action);
            case "CREATE":
                return schema ? Arrays.asList("CREATE", "CREATE VIEW") : null;
            case "ALTER":
                return view ? null : Collections.singletonList("ALTER");
            case "DROP":
                return Collections.singletonList("DROP");
            case "SHOW_VIEW":
                return schema || view ? Collections.singletonList("SHOW VIEW") : null;
            default:
                return null;
        }
    }

    @Override
    protected String scopeNote() {
        return "库级权限覆盖当前及未来对象；DROP可能删库。MySQL原生角色在partial_revokes关闭且库名含_／%时有兼容限制，请使用表／视图授权；预览会明确校验。";
    }

    @Override
    public boolean roleExists(Connection c, String name) throws SQLException {
        return !rows(c, "SELECT User FROM mysql.user WHERE User=? AND Host='%'", name).isEmpty();
    }

    @Override
    public Set<Atom> readRole(Connection c, String name) throws SQLException {
        Set<Atom> result = new LinkedHashSet<>();
        String grantee = literal(name) + "@'%'";
        // mysql.db exposes the actual database pattern; information_schema renders escaped SQL
        // text.
        for (Map<String, Object> r :
                rows(c, "SELECT * FROM mysql.db WHERE User=? AND Host='%'", name)) {
            String database = decodeDatabasePattern(string(r, "db"));
            for (Map.Entry<String, Object> entry : r.entrySet())
                if (entry.getKey().endsWith("_priv") && "Y".equals(entry.getValue())) {
                    if ("grant_priv".equals(entry.getKey()))
                        throw new IllegalArgumentException("托管角色存在转授权权限，请先核对");
                    result.add(
                            new Atom(
                                    "SCHEMA",
                                    database,
                                    null,
                                    entry.getKey()
                                            .substring(0, entry.getKey().length() - 5)
                                            .replace('_', ' ')
                                            .toUpperCase(Locale.ROOT)));
                }
        }
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT TABLE_SCHEMA AS db,TABLE_NAME AS obj,PRIVILEGE_TYPE AS priv"
                                + " FROM information_schema.TABLE_PRIVILEGES WHERE GRANTEE=?",
                        grantee))
            result.add(new Atom("TABLE", string(r, "db"), string(r, "obj"), string(r, "priv")));
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT PRIVILEGE_TYPE AS priv FROM information_schema.USER_PRIVILEGES"
                                + " WHERE GRANTEE=?",
                        grantee))
            if (!"USAGE".equals(string(r, "priv")))
                throw new IllegalArgumentException("托管角色存在全局权限，请先在数据库核对");
        if (!rows(c, "SELECT 1 FROM mysql.role_edges WHERE TO_USER=? AND TO_HOST='%'", name)
                .isEmpty()) throw new IllegalArgumentException("托管角色包含外部角色，请先核对");
        return result;
    }

    private String decodeDatabasePattern(String pattern) throws SQLException {
        if (literalDatabaseNames) return pattern;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length()) result.append(pattern.charAt(++i));
            else if (ch == '_' || ch == '%')
                throw new SQLException("托管角色存在数据库通配模式，请先在数据库核对，不能按单一库展示");
            else result.append(ch);
        }
        return result.toString();
    }

    @Override
    public Set<String> memberships(Connection c, Identity identity) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT FROM_USER AS name FROM mysql.role_edges WHERE TO_USER=? AND"
                                + " TO_HOST=?",
                        identity.getUser(),
                        identity.getHost())) result.add(string(r, "name"));
        return result;
    }

    @Override
    public boolean identityExists(Connection c, Identity identity) throws SQLException {
        return !rows(
                        c,
                        "SELECT User FROM mysql.user WHERE User=? AND Host=?",
                        identity.getUser(),
                        identity.getHost())
                .isEmpty();
    }

    /** Preserve external defaults; do not activate every externally granted role. */
    @Override
    public String defaultRoleSql(
            Connection c, Identity identity, Set<String> managedBefore, Set<String> managedAfter)
            throws SQLException {
        Set<String> defaults = new LinkedHashSet<>();
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT DEFAULT_ROLE_USER AS name,DEFAULT_ROLE_HOST AS host FROM"
                                + " mysql.default_roles WHERE USER=? AND HOST=?",
                        identity.getUser(),
                        identity.getHost())) {
            if (!managedBefore.contains(string(r, "name")))
                defaults.add(literal(string(r, "name")) + "@" + literal(string(r, "host")));
        }
        for (String role : managedAfter) defaults.add(role(role));
        return "SET DEFAULT ROLE "
                + (defaults.isEmpty() ? "NONE" : String.join(", ", defaults))
                + " TO "
                + account(identity);
    }

    @Override
    public void verifyActiveRoles(Connection c, Set<String> desired) throws SQLException {
        Set<String> enabled = new HashSet<>();
        for (Map<String, Object> row :
                rows(c, "SELECT ROLE_NAME AS name FROM information_schema.ENABLED_ROLES"))
            enabled.add(string(row, "name"));
        if (!enabled.containsAll(desired)) throw new SQLException("成员角色未在新连接中默认激活");
    }

    @Override
    public List<String> inspect(Connection c, Identity identity) throws SQLException {
        // Inspect the already verified member session, never another account through a manager.
        String sql = "SHOW GRANTS FOR CURRENT_USER";
        List<String> enabled = new ArrayList<>();
        for (Map<String, Object> row :
                rows(
                        c,
                        "SELECT ROLE_NAME AS name, ROLE_HOST AS host FROM"
                                + " information_schema.ENABLED_ROLES ORDER BY ROLE_NAME,"
                                + " ROLE_HOST")) {
            enabled.add(account(new Identity(string(row, "name"), string(row, "host"))));
        }
        if (!enabled.isEmpty()) sql += " USING " + String.join(", ", enabled);
        return grantText(c, sql);
    }
}
