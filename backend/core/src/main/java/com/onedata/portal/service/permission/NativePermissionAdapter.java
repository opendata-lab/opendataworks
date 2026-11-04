package com.onedata.portal.service.permission;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.onedata.portal.service.permission.PermissionModels.Atom;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.*;
import java.util.*;

/** Native ACL operations; no SQL query rewriting or user permission emulation. */
public final class NativePermissionAdapter {
    private final String engine;
    private boolean mysqlLiteralDatabaseNames;
    private static final List<String> RESOURCES = Arrays.asList("SCHEMA", "TABLE", "VIEW");

    private NativePermissionAdapter(String engine) {
        this.engine = engine;
    }

    public static NativePermissionAdapter forType(String type) {
        String engine = type == null ? "DORIS" : type.toUpperCase(Locale.ROOT);
        if (!Arrays.asList("MYSQL", "POSTGRESQL", "DORIS").contains(engine))
            throw new IllegalArgumentException("不支持该数据源权限管理");
        return new NativePermissionAdapter(engine);
    }

    public boolean postgres() {
        return "POSTGRESQL".equals(engine);
    }

    public boolean doris() {
        return "DORIS".equals(engine);
    }

    public void configure(Connection c) throws SQLException {
        if (!postgres() && !doris()) {
            if (c.getMetaData().getDatabaseMajorVersion() < 8)
                throw new IllegalArgumentException("UNSUPPORTED_ACTION: 原生角色管理要求MySQL8及以上");
            mysqlLiteralDatabaseNames =
                    ((Number) rows(c, "SELECT @@partial_revokes AS enabled").get(0).get("enabled"))
                                    .intValue()
                            != 0;
        }
        if (postgres() && c.getMetaData().getDatabaseMajorVersion() < 16)
            throw new IllegalArgumentException("UNSUPPORTED_ACTION: 当前成员继承适配要求PostgreSQL16及以上");
    }

    public boolean literalDatabaseNames() {
        return mysqlLiteralDatabaseNames;
    }

    public void validateSchemaScope(String database) {
        if (!postgres()
                && !doris()
                && !mysqlLiteralDatabaseNames
                && (database.contains("_") || database.contains("%")))
            throw new IllegalArgumentException(
                    "UNSUPPORTED_ACTION:"
                        + " 当前MySQL库级角色授权对含_或%的库名存在原生通配符限制（Bug110582）；请按表／视图授权，或由DBA评估数据库修复版本／partial_revokes配置。平台不会放宽授权或修改全局配置。");
    }

    public String identifier(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || name.indexOf('\0') >= 0)
            throw new IllegalArgumentException("无效的数据库对象名");
        String quote = postgres() ? "\"" : "`";
        return quote + name.replace(quote, quote + quote) + quote;
    }

    public static String literal(String text) {
        if (text == null || text.indexOf('\0') >= 0 || text.indexOf('\\') >= 0)
            throw new IllegalArgumentException("账号标识不能含反斜线或空字符");
        return "'" + text.replace("'", "''") + "'";
    }

    public String role(String role) {
        return postgres() ? identifier(role) : literal(role) + (doris() ? "" : "@'%'");
    }

    public String account(Identity identity) {
        return postgres()
                ? identifier(identity.getUser())
                : literal(identity.getUser()) + "@" + literal(identity.getHost());
    }

    public String createRole(String name) {
        return "CREATE ROLE " + role(name) + (postgres() ? " NOLOGIN" : "");
    }

    public String dropRole(String name) {
        return "DROP ROLE " + role(name);
    }

    public String membership(String name, Identity user, boolean grant) {
        return (grant ? "GRANT " : "REVOKE ")
                + role(name)
                + (grant ? " TO " : " FROM ")
                + account(user)
                + (grant && postgres() ? " WITH INHERIT TRUE" : "");
    }

    public String grantSql(String name, Atom atom, boolean grant) {
        String database = atom.getDatabase();
        if ("SCHEMA".equals(atom.getKind())
                && !postgres()
                && !doris()
                && !mysqlLiteralDatabaseNames)
            database = database.replace("\\", "\\\\").replace("_", "\\_").replace("%", "\\%");
        String object = identifier(database);
        if (!"SCHEMA".equals(atom.getKind())) object += "." + identifier(atom.getObject());
        else if (!postgres()) object += ".*";
        if (doris()) object = "`internal`." + object;
        String kind =
                postgres()
                        ? ("SCHEMA".equals(atom.getKind())
                                ? "SCHEMA "
                                : "SEQUENCE".equals(atom.getKind()) ? "SEQUENCE " : "TABLE ")
                        : "";
        return (grant ? "GRANT " : "REVOKE ")
                + atom.getPrivilege()
                + " ON "
                + kind
                + object
                + (grant ? " TO " : " FROM ")
                + (doris() ? "ROLE " : "")
                + role(name);
    }

    public List<String> nativeActions(String action, String resourceType) {
        if (!RESOURCES.contains(resourceType))
            throw new IllegalArgumentException("资源类型必须为SCHEMA、TABLE或VIEW");
        boolean schema = "SCHEMA".equals(resourceType), view = "VIEW".equals(resourceType);
        switch (action) {
            case "QUERY":
                return Collections.singletonList(doris() ? "SELECT_PRIV" : "SELECT");
            case "WRITE":
                return doris()
                        ? Collections.singletonList("LOAD_PRIV")
                        : Arrays.asList("INSERT", "UPDATE", "DELETE");
            case "INSERT":
            case "UPDATE":
            case "DELETE":
                if (!doris()) return Collections.singletonList(action);
                break;
            case "CREATE":
                if (schema)
                    return postgres()
                            ? Collections.singletonList("CREATE")
                            : doris()
                                    ? Collections.singletonList("CREATE_PRIV")
                                    : Arrays.asList("CREATE", "CREATE VIEW");
                break;
            case "ALTER":
                if (!postgres() && (!view || doris()))
                    return Collections.singletonList(doris() ? "ALTER_PRIV" : "ALTER");
                break;
            case "DROP":
                if (!postgres()) return Collections.singletonList(doris() ? "DROP_PRIV" : "DROP");
                break;
            case "SHOW_VIEW":
                if (!postgres() && (schema || view))
                    return Collections.singletonList(doris() ? "SHOW_VIEW_PRIV" : "SHOW VIEW");
                break;
            default:
                break;
        }
        throw new IllegalArgumentException(
                "UNSUPPORTED_ACTION: "
                        + action
                        + " 不适用于 "
                        + engine
                        + "/"
                        + resourceType
                        + (postgres() && Arrays.asList("ALTER", "DROP").contains(action)
                                ? "；PostgreSQL由对象所有权控制，不自动转移所有权"
                                : ""));
    }

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
            row.put(
                    "note",
                    doris() && "WRITE".equals(label[0])
                            ? "Load_priv整体授权，包含导入、插入、删除等，不能独立拆分"
                            : postgres() && "CREATE".equals(label[0])
                                    ? "Schema CREATE可创建多种对象；ALTER/DROP仍由所有权决定"
                                    : "");
            actions.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceTypes", RESOURCES);
        result.put("actions", actions);
        result.put("schemaLabel", postgres() ? "Schema" : "数据库");
        result.put(
                "note",
                postgres()
                        ? "库级查询/写入仅覆盖预览时的现有表和视图；不包含未来对象。ALTER/DROP由所有权控制。"
                        : doris()
                                ? "库级权限覆盖当前及未来对象；库级DROP可能允许删除整个库。"
                                : "库级权限覆盖当前及未来对象；DROP可能删库。MySQL原生角色在partial_revokes关闭且库名含_／%时有兼容限制，请使用表／视图授权；预览会明确校验。");
        return result;
    }

    public boolean roleExists(Connection c, String name) throws SQLException {
        if (postgres())
            return !rows(c, "SELECT rolname FROM pg_roles WHERE rolname=?", name).isEmpty();
        if (!doris())
            return !rows(c, "SELECT User FROM mysql.user WHERE User=? AND Host='%'", name)
                    .isEmpty();
        return dorisRole(c, name) != null;
    }

    public Set<Atom> readRole(Connection c, String name) throws SQLException {
        Set<Atom> result = new LinkedHashSet<>();
        if (postgres()) {
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT n.nspname AS db,c.relname AS obj,CASE WHEN c.relkind='S' THEN"
                                + " 'SEQUENCE' ELSE 'TABLE' END AS kind,a.privilege_type AS priv"
                                + " FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace"
                                + " CROSS JOIN LATERAL aclexplode(c.relacl) a JOIN pg_roles r ON"
                                + " r.oid=a.grantee WHERE r.rolname=?",
                            name))
                result.add(
                        new Atom(
                                string(r, "kind"),
                                string(r, "db"),
                                string(r, "obj"),
                                string(r, "priv")));
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT n.nspname AS db,a.privilege_type AS priv FROM pg_namespace n"
                                + " CROSS JOIN LATERAL aclexplode(n.nspacl) a JOIN pg_roles r ON"
                                + " r.oid=a.grantee WHERE r.rolname=?",
                            name))
                result.add(new Atom("SCHEMA", string(r, "db"), null, string(r, "priv")));
        } else if (!doris()) {
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
        } else {
            Map<String, Object> info = dorisRole(c, name);
            if (info == null) return result;
            for (String field :
                    Arrays.asList(
                            "globalprivs", "catalogprivs", "resourceprivs", "workloadgroupprivs"))
                if (!emptyDoris(string(info, field)))
                    throw new IllegalArgumentException("托管角色存在库表之外的权限，请先核对");
            for (String field : Arrays.asList("databaseprivs", "tableprivs")) {
                String text = string(info, field);
                if (emptyDoris(text)) continue;
                for (String entry : text.split(";\\s*")) {
                    int separator = entry.lastIndexOf(':');
                    if (separator < 0) throw new SQLException("无法识别Doris权限回读格式");
                    String[] path = entry.substring(0, separator).trim().split("\\.");
                    if (path.length != 3 || !"internal".equals(path[0]))
                        throw new SQLException("仅支持Doris internal目录的可核验库表权限");
                    for (String priv : entry.substring(separator + 1).split(","))
                        result.add(
                                new Atom(
                                        "*".equals(path[2]) ? "SCHEMA" : "TABLE",
                                        path[1],
                                        "*".equals(path[2]) ? null : path[2],
                                        priv.trim().toUpperCase(Locale.ROOT)));
                }
            }
        }
        return result;
    }

    private String decodeDatabasePattern(String pattern) throws SQLException {
        if (mysqlLiteralDatabaseNames) return pattern;
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

    private Map<String, Object> dorisRole(Connection c, String name) throws SQLException {
        for (Map<String, Object> r : rows(c, "SHOW ROLES"))
            if (name.equals(string(r, "name"))) return r;
        return null;
    }

    private static boolean emptyDoris(String value) {
        return value.isEmpty() || "NULL".equalsIgnoreCase(value) || "N/A".equalsIgnoreCase(value);
    }

    public Identity identity(Connection c, String requestedName) throws SQLException {
        String value =
                String.valueOf(
                        rows(
                                        c,
                                        postgres()
                                                ? "SELECT current_user AS identity"
                                                : "SELECT CURRENT_USER() AS identity")
                                .get(0)
                                .get("identity"));
        if (postgres()) {
            if (!requestedName.equals(value)) throw new IllegalArgumentException("数据库实际身份与填写账号不一致");
            return new Identity(value, null);
        }
        int at = value.lastIndexOf('@');
        if (at < 1) throw new SQLException("数据库未返回可核验的user@host身份");
        String user = unquote(value.substring(0, at)), host = unquote(value.substring(at + 1));
        if (!requestedName.equals(user))
            throw new IllegalArgumentException("数据库实际身份与填写账号不一致，不使用代理账号");
        literal(user);
        literal(host);
        return new Identity(user, host);
    }

    private String unquote(String value) {
        return value.length() > 1 && value.startsWith("'") && value.endsWith("'")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    public Set<String> memberships(Connection c, Identity identity) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        if (postgres())
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT r.rolname AS name FROM pg_auth_members m JOIN pg_roles r ON"
                                + " r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE"
                                + " u.rolname=?",
                            identity.getUser())) result.add(string(r, "name"));
        else if (!doris())
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT FROM_USER AS name FROM mysql.role_edges WHERE TO_USER=? AND"
                                + " TO_HOST=?",
                            identity.getUser(),
                            identity.getHost())) result.add(string(r, "name"));
        else
            for (Map<String, Object> r : rows(c, "SHOW GRANTS FOR " + account(identity)))
                for (String part : string(r, "roles").split(","))
                    if (!part.trim().isEmpty()) result.add(unquote(part.trim()));
        return result;
    }

    public boolean identityExists(Connection c, Identity identity) throws SQLException {
        if (postgres())
            return !rows(c, "SELECT rolname FROM pg_roles WHERE rolname=?", identity.getUser())
                    .isEmpty();
        if (!doris())
            return !rows(
                            c,
                            "SELECT User FROM mysql.user WHERE User=? AND Host=?",
                            identity.getUser(),
                            identity.getHost())
                    .isEmpty();
        for (Map<String, Object> row : rows(c, "SHOW ALL GRANTS"))
            if (account(identity).equals(string(row, "useridentity"))) return true;
        return false;
    }

    /** Preserve external defaults; do not activate every externally granted role. */
    public String defaultRoleSql(
            Connection c, Identity identity, Set<String> managedBefore, Set<String> managedAfter)
            throws SQLException {
        if (postgres() || doris()) return null;
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

    public void verifyActiveRoles(Connection c, Set<String> desired) throws SQLException {
        if (postgres()) {
            for (String name : desired) {
                Object active =
                        rows(c, "SELECT pg_has_role(current_user,?,'USAGE') AS active", name)
                                .get(0)
                                .get("active");
                if (!Boolean.TRUE.equals(active)) throw new SQLException("成员角色未在新连接中继承生效");
            }
        } else if (!doris()) {
            Set<String> enabled = new HashSet<>();
            for (Map<String, Object> row :
                    rows(c, "SELECT ROLE_NAME AS name FROM information_schema.ENABLED_ROLES"))
                enabled.add(string(row, "name"));
            if (!enabled.containsAll(desired)) throw new SQLException("成员角色未在新连接中默认激活");
        }
    }

    public List<String> inspect(Connection c, Identity identity) throws SQLException {
        List<String> result = new ArrayList<>();
        if (postgres()) {
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT"
                                + " r.rolname,r.rolsuper,r.rolcreaterole,r.rolcreatedb,r.rolbypassrls"
                                + " FROM pg_roles r WHERE r.rolname=?",
                            identity.getUser())) result.add(r.toString());
            for (Map<String, Object> r :
                    rows(
                            c,
                            "SELECT table_schema,table_name,privilege_type FROM"
                                + " information_schema.role_table_grants WHERE grantee=? ORDER BY"
                                + " table_schema,table_name,privilege_type",
                            identity.getUser())) result.add(r.toString());
            result.add("原生角色：" + String.join(", ", memberships(c, identity)));
        } else {
            // Inspect the already verified member session, never another account through a manager.
            String sql = "SHOW GRANTS";
            if (!doris()) {
                sql = "SHOW GRANTS FOR CURRENT_USER";
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
            }
            for (Map<String, Object> r : rows(c, sql)) {
                r.remove("password");
                result.add(r.toString());
            }
        }
        return result;
    }

    public List<Atom> sequences(Connection c, String database, String table) throws SQLException {
        if (!postgres()) return Collections.emptyList();
        List<Atom> result = new ArrayList<>();
        String sql =
                "SELECT DISTINCT ns.nspname AS db,s.relname AS obj FROM pg_class s JOIN"
                    + " pg_namespace ns ON ns.oid=s.relnamespace JOIN pg_depend d ON"
                    + " d.refobjid=s.oid JOIN pg_attrdef a ON a.oid=d.objid AND"
                    + " d.classid='pg_attrdef'::regclass JOIN pg_class t ON t.oid=a.adrelid JOIN"
                    + " pg_namespace n ON n.oid=t.relnamespace WHERE s.relkind='S' AND n.nspname=?"
                    + " AND t.relname=?";
        for (Map<String, Object> r : rows(c, sql, database, table))
            result.add(new Atom("SEQUENCE", string(r, "db"), string(r, "obj"), "USAGE"));
        return result;
    }
}
