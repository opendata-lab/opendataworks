package com.onedata.portal.util;

import java.util.regex.Pattern;

/**
 * Validates the pieces that get spliced into a JDBC URL.
 *
 * Drivers parse the URL after it is assembled, so a host such as
 * {@code db/other?user=x&sslmode=disable} or a database such as
 * {@code ods?allowLoadLocalInfile=true} would silently override the configured
 * connection. Anything that is not a plain host or identifier is rejected.
 */
public final class JdbcUrlParts {
    private JdbcUrlParts() {
    }

    // An absolute FQDN may end with a dot.
    private static final Pattern HOSTNAME = Pattern.compile(
            "^[A-Za-z0-9_]([A-Za-z0-9_-]{0,62})(\\.[A-Za-z0-9_]([A-Za-z0-9_-]{0,62}))*\\.?$");
    // IPv6, optionally bracketed. A zone id is only accepted unbracketed and undecoded
    // (fe80::1%en0): JDBC drivers do not percent-decode the host, and rewriting the value here
    // would decode again on every save and connect.
    private static final Pattern IPV6 = Pattern.compile(
            "^(\\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*(%[A-Za-z0-9_.-]+)?)$");
    private static final Pattern URL_META = Pattern.compile("[/?&#;=@\\s%\\\\]");

    public static String requireHost(String host) {
        String value = host == null ? "" : host.trim();
        if (value.length() > 253 || !(HOSTNAME.matcher(value).matches() || IPV6.matcher(value).matches())) {
            throw new IllegalArgumentException("数据源地址格式不正确，只允许主机名、IPv4 或 IPv6 地址"
                    + "（带网卡标识的 IPv6 请写成 fe80::1%en0，不加括号）: " + host);
        }
        return value;
    }

    /** For URL templates that splice the database in verbatim (MySQL / Doris). */
    public static String requireDatabase(String database) {
        if (database == null || database.isEmpty()) {
            return database;
        }
        if (URL_META.matcher(database).find()) {
            throw new IllegalArgumentException("数据库名包含非法字符: " + database);
        }
        return database;
    }
}
