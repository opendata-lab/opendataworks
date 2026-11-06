package com.onedata.portal.service.datasource.adapters.mysql;

import static com.onedata.portal.service.datasource.jdbc.DatasourceJdbc.*;

import com.onedata.portal.service.permission.NativePermissionAdapter;
import com.onedata.portal.service.permission.PermissionModels.Identity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Permission SQL shared by engines on the MySQL protocol: user@host accounts and logins. */
public abstract class MysqlProtocolPermissionAdapter extends NativePermissionAdapter {

    @Override
    protected String quote() {
        return "`";
    }

    @Override
    public String account(Identity identity) {
        return literal(identity.getUser()) + "@" + literal(identity.getHost());
    }

    @Override
    public String createLogin(Identity identity, String password) {
        return "CREATE USER " + account(identity) + " IDENTIFIED BY " + literal(password);
    }

    @Override
    public String dropLogin(Identity identity) {
        return "DROP USER " + account(identity);
    }

    @Override
    public Identity identity(Connection c, String requestedName) throws SQLException {
        String value =
                reported(
                        String.valueOf(
                                rows(c, "SELECT CURRENT_USER() AS identity")
                                        .get(0)
                                        .get("identity")));
        int at = value.lastIndexOf('@');
        if (at < 1) throw new SQLException("数据库未返回可核验的user@host身份");
        String user = unquote(value.substring(0, at)), host = unquote(value.substring(at + 1));
        if (!requestedName.equals(user))
            throw new IllegalArgumentException("数据库实际身份与填写账号不一致，不使用代理账号");
        literal(user);
        literal(host);
        return new Identity(user, host);
    }

    /** Normalizes a name the server reports back before it is compared with platform names. */
    protected String reported(String value) {
        return value;
    }

    protected static String unquote(String value) {
        return value.length() > 1 && value.startsWith("'") && value.endsWith("'")
                ? value.substring(1, value.length() - 1)
                : value;
    }

    /** SHOW GRANTS rows as text, without password hashes. */
    protected List<String> grantText(Connection c, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> r : rows(c, sql)) {
            r.remove("password");
            result.add(reported(r.toString()));
        }
        return result;
    }
}
