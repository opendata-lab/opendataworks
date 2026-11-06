package com.onedata.portal.service.datasource.adapters.doris;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.onedata.portal.service.datasource.adapters.mysql.MysqlProtocolPermissionAdapter;
import com.onedata.portal.service.permission.PermissionModels.Atom;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Doris native roles in the {@code internal} catalog.
 *
 * <p>Verified on Doris 2.0.15: CREATE/DROP ROLE take the name as an identifier while GRANT ... TO
 * ROLE and role-to-user grants take a string literal; SHOW ROLES / SHOW GRANTS / CURRENT_USER()
 * report names as {@code default_cluster:<name>} and list privileges space-separated. 2.1+ prints
 * plain names, which {@link #withoutCluster} leaves unchanged.
 */
public class DorisPermissionAdapter extends MysqlProtocolPermissionAdapter {

    @Override
    protected String engine() {
        return "DORIS";
    }

    @Override
    public boolean doris() {
        return true;
    }

    @Override
    public String role(String role) {
        return literal(role);
    }

    @Override
    public String createRole(String name) {
        return "CREATE ROLE " + identifier(name);
    }

    @Override
    public String dropRole(String name) {
        return "DROP ROLE " + identifier(name);
    }

    @Override
    public String grantSql(String name, Atom atom, boolean grant) {
        boolean schema = "SCHEMA".equals(atom.getKind());
        String target =
                "`internal`."
                        + identifier(atom.getDatabase())
                        + (schema ? ".*" : "." + identifier(atom.getObject()));
        return grantStatement(atom.getPrivilege(), target, grant, "ROLE " + role(name));
    }

    @Override
    protected List<String> nativeActions(String action, boolean schema, boolean view) {
        switch (action) {
            case "QUERY":
                return Collections.singletonList("SELECT_PRIV");
            case "WRITE":
                return Collections.singletonList("LOAD_PRIV");
            case "CREATE":
                return schema ? Collections.singletonList("CREATE_PRIV") : null;
            case "ALTER":
                return Collections.singletonList("ALTER_PRIV");
            case "DROP":
                return Collections.singletonList("DROP_PRIV");
            case "SHOW_VIEW":
                return schema || view ? Collections.singletonList("SHOW_VIEW_PRIV") : null;
            default:
                return null;
        }
    }

    @Override
    protected String actionNote(String action) {
        return "WRITE".equals(action) ? "Load_priv整体授权，包含导入、插入、删除等，不能独立拆分" : "";
    }

    @Override
    protected String scopeNote() {
        return "库级权限覆盖当前及未来对象；库级DROP可能允许删除整个库。";
    }

    @Override
    public boolean roleExists(Connection c, String name) throws SQLException {
        return showRole(c, name) != null;
    }

    @Override
    public Set<Atom> readRole(Connection c, String name) throws SQLException {
        Set<Atom> result = new LinkedHashSet<>();
        Map<String, Object> info = showRole(c, name);
        if (info == null) return result;
        for (String field :
                Arrays.asList("globalprivs", "catalogprivs", "resourceprivs", "workloadgroupprivs"))
            if (!empty(string(info, field)))
                throw new IllegalArgumentException("托管角色存在库表之外的权限，请先核对");
        for (String field : Arrays.asList("databaseprivs", "tableprivs")) {
            String text = string(info, field);
            if (!empty(text)) result.addAll(atoms(text));
        }
        return result;
    }

    /**
     * Parses a SHOW ROLES DatabasePrivs/TablePrivs value, e.g. 2.0's {@code
     * internal.default_cluster:db.*: Select_priv Load_priv ; internal.default_cluster:db.t:
     * Select_priv}.
     */
    static List<Atom> atoms(String text) throws SQLException {
        List<Atom> result = new ArrayList<>();
        for (String entry : withoutCluster(text).split(";\\s*")) {
            int separator = entry.lastIndexOf(':');
            if (separator < 0) throw new SQLException("无法识别Doris权限回读格式");
            String[] path = entry.substring(0, separator).trim().split("\\.");
            if (path.length != 3 || !"internal".equals(path[0]))
                throw new SQLException("仅支持Doris internal目录的可核验库表权限");
            boolean schema = "*".equals(path[2]);
            for (String priv : entry.substring(separator + 1).trim().split("[,\\s]+"))
                result.add(
                        new Atom(
                                schema ? "SCHEMA" : "TABLE",
                                path[1],
                                schema ? null : path[2],
                                priv.toUpperCase(Locale.ROOT)));
        }
        return result;
    }

    private Map<String, Object> showRole(Connection c, String name) throws SQLException {
        for (Map<String, Object> r : rows(c, "SHOW ROLES"))
            if (name.equals(withoutCluster(string(r, "name")))) return r;
        return null;
    }

    private static boolean empty(String value) {
        return value.isEmpty() || "NULL".equalsIgnoreCase(value) || "N/A".equalsIgnoreCase(value);
    }

    /** Names cannot contain ':', so removing the 2.0 cluster prefix cannot alter a real name. */
    static String withoutCluster(String value) {
        return value == null ? null : value.replace("default_cluster:", "");
    }

    @Override
    protected String reported(String value) {
        return withoutCluster(value);
    }

    @Override
    public Set<String> memberships(Connection c, Identity identity) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> r : rows(c, "SHOW GRANTS FOR " + account(identity)))
            for (String part : withoutCluster(string(r, "roles")).split(","))
                if (!part.trim().isEmpty()) result.add(unquote(part.trim()));
        return result;
    }

    @Override
    public boolean identityExists(Connection c, Identity identity) throws SQLException {
        for (Map<String, Object> row : rows(c, "SHOW ALL GRANTS"))
            if (account(identity).equals(withoutCluster(string(row, "useridentity")))) return true;
        return false;
    }

    @Override
    public List<String> inspect(Connection c, Identity identity) throws SQLException {
        // Inspect the already verified member session, never another account through a manager.
        return grantText(c, "SHOW GRANTS");
    }
}
