package com.onedata.portal.service.datasource.adapters.postgresql;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.onedata.portal.service.permission.NativePermissionAdapter;
import com.onedata.portal.service.permission.PermissionModels.Atom;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** PostgreSQL 16+ native roles: NOLOGIN group roles inherited by member login roles. */
public class PostgresqlPermissionAdapter extends NativePermissionAdapter {

    @Override
    protected String engine() {
        return "POSTGRESQL";
    }

    @Override
    public boolean postgres() {
        return true;
    }

    @Override
    public void configure(Connection c) throws SQLException {
        if (c.getMetaData().getDatabaseMajorVersion() < 16)
            throw new IllegalArgumentException("UNSUPPORTED_ACTION: 当前成员继承适配要求PostgreSQL16及以上");
    }

    @Override
    protected String quote() {
        return "\"";
    }

    @Override
    public String role(String role) {
        return identifier(role);
    }

    @Override
    public String account(Identity identity) {
        return identifier(identity.getUser());
    }

    @Override
    public String createLogin(Identity identity, String password) {
        return "CREATE ROLE "
                + identifier(identity.getUser())
                + " LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION"
                + " NOBYPASSRLS PASSWORD "
                + literal(password);
    }

    @Override
    public String dropLogin(Identity identity) {
        return "DROP ROLE " + identifier(identity.getUser());
    }

    @Override
    public String createRole(String name) {
        return super.createRole(name) + " NOLOGIN";
    }

    @Override
    public String membership(String name, Identity user, boolean grant) {
        return super.membership(name, user, grant) + (grant ? " WITH INHERIT TRUE" : "");
    }

    @Override
    public String grantSql(String name, Atom atom, boolean grant) {
        String kind = atom.getKind();
        String target =
                "SCHEMA".equals(kind)
                        ? "SCHEMA " + identifier(atom.getDatabase())
                        : ("SEQUENCE".equals(kind) ? "SEQUENCE " : "TABLE ")
                                + identifier(atom.getDatabase())
                                + "."
                                + identifier(atom.getObject());
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
                return schema ? Collections.singletonList("CREATE") : null;
            default:
                return null;
        }
    }

    @Override
    protected String unsupportedHint(String action) {
        return Arrays.asList("ALTER", "DROP").contains(action)
                ? "；PostgreSQL由对象所有权控制，不自动转移所有权"
                : "";
    }

    @Override
    protected String actionNote(String action) {
        return "CREATE".equals(action) ? "Schema CREATE可创建多种对象；ALTER/DROP仍由所有权决定" : "";
    }

    @Override
    protected String schemaLabel() {
        return "Schema";
    }

    @Override
    protected String scopeNote() {
        return "库级查询/写入仅覆盖预览时的现有表和视图；不包含未来对象。ALTER/DROP由所有权控制。";
    }

    @Override
    public boolean roleExists(Connection c, String name) throws SQLException {
        return !rows(c, "SELECT rolname FROM pg_roles WHERE rolname=?", name).isEmpty();
    }

    @Override
    public Set<Atom> readRole(Connection c, String name) throws SQLException {
        Set<Atom> result = new LinkedHashSet<>();
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
        return result;
    }

    @Override
    public Identity identity(Connection c, String requestedName) throws SQLException {
        String value =
                String.valueOf(rows(c, "SELECT current_user AS identity").get(0).get("identity"));
        if (!requestedName.equals(value)) throw new IllegalArgumentException("数据库实际身份与填写账号不一致");
        return new Identity(value, null);
    }

    @Override
    public Set<String> memberships(Connection c, Identity identity) throws SQLException {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT r.rolname AS name FROM pg_auth_members m JOIN pg_roles r ON"
                                + " r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE"
                                + " u.rolname=?",
                        identity.getUser())) result.add(string(r, "name"));
        return result;
    }

    @Override
    public boolean identityExists(Connection c, Identity identity) throws SQLException {
        return !rows(c, "SELECT rolname FROM pg_roles WHERE rolname=?", identity.getUser())
                .isEmpty();
    }

    @Override
    public void verifyActiveRoles(Connection c, Set<String> desired) throws SQLException {
        for (String name : desired) {
            Object active =
                    rows(c, "SELECT pg_has_role(current_user,?,'USAGE') AS active", name)
                            .get(0)
                            .get("active");
            if (!Boolean.TRUE.equals(active)) throw new SQLException("成员角色未在新连接中继承生效");
        }
    }

    @Override
    public List<String> inspect(Connection c, Identity identity) throws SQLException {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT r.rolname,r.rolsuper,r.rolcreaterole,r.rolcreatedb,r.rolbypassrls"
                            + " FROM pg_roles r WHERE r.rolname=?",
                        identity.getUser())) result.add(r.toString());
        // Member session: include grants inherited through enabled roles, like MySQL USING.
        for (Map<String, Object> r :
                rows(
                        c,
                        "SELECT grantee,table_schema,table_name,privilege_type FROM"
                                + " information_schema.table_privileges WHERE grantee IN"
                                + " (SELECT role_name FROM information_schema.enabled_roles)"
                                + " ORDER BY table_schema,table_name,privilege_type,grantee"))
            result.add(r.toString());
        result.add("原生角色：" + String.join(", ", memberships(c, identity)));
        return result;
    }

    @Override
    public List<Atom> sequences(Connection c, String database, String table) throws SQLException {
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
