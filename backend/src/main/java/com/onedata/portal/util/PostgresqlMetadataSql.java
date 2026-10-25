package com.onedata.portal.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** PostgreSQL catalog queries exposing the existing datasource metadata column contract. */
public final class PostgresqlMetadataSql {
    private PostgresqlMetadataSql() {
    }

    private static final String VISIBLE_SCHEMA = "n.nspname <> 'information_schema' "
            + "AND n.nspname !~ '^pg_' AND has_schema_privilege(n.oid, 'USAGE')";

    public static final String SCHEMAS = "SELECT n.nspname FROM pg_catalog.pg_namespace n WHERE "
            + VISIBLE_SCHEMA + " ORDER BY n.nspname";

    // Unknown row counts stay NULL; PostgreSQL does not expose table create/update timestamps.
    // Partitions are listed only through their parent, so a partitioned parent reports the
    // sum of its leaf partitions (pg_partition_tree, PostgreSQL 12+) instead of its own empty relation.
    private static final String LEAF_PARTITIONS = "FROM pg_partition_tree(c.oid) pt "
            + "JOIN pg_catalog.pg_class lc ON lc.oid = pt.relid WHERE pt.isleaf";

    public static final String TABLES = "SELECT n.nspname AS TABLE_SCHEMA, c.relname AS TABLE_NAME, "
            + "CASE WHEN c.relkind IN ('v', 'm') THEN 'VIEW' ELSE 'BASE TABLE' END AS TABLE_TYPE, "
            + "obj_description(c.oid, 'pg_class') AS TABLE_COMMENT, "
            + "NULL::timestamp AS CREATE_TIME, NULL::timestamp AS UPDATE_TIME, "
            + "CASE WHEN c.relkind = 'p' THEN (SELECT CASE WHEN bool_or(lc.reltuples < 0) THEN NULL "
            + "ELSE COALESCE(SUM(lc.reltuples), 0)::bigint END " + LEAF_PARTITIONS + ") "
            + "WHEN c.reltuples < 0 OR c.relkind IN ('v', 'f') THEN NULL "
            + "ELSE c.reltuples::bigint END AS TABLE_ROWS, "
            + "CASE WHEN c.relkind IN ('r', 'm') THEN pg_total_relation_size(c.oid) "
            + "WHEN c.relkind = 'p' THEN (SELECT COALESCE(SUM(pg_total_relation_size(lc.oid)), 0)::bigint "
            + LEAF_PARTITIONS + ") "
            + "ELSE 0::bigint END AS DATA_LENGTH, 'PostgreSQL' AS ENGINE "
            + "FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f') AND NOT c.relispartition AND " + VISIBLE_SCHEMA
            + " AND (has_table_privilege(c.oid, 'SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER') "
            + "OR has_any_column_privilege(c.oid, 'SELECT, INSERT, UPDATE, REFERENCES'))";

    public static String schemaObjects(boolean hasKeyword) {
        return TABLES + (hasKeyword
                ? " AND (strpos(lower(c.relname), ?) > 0 "
                    + "OR strpos(lower(coalesce(obj_description(c.oid, 'pg_class'), '')), ?) > 0)"
                : "") + " ORDER BY n.nspname, c.relname";
    }

    public static final String COLUMNS = "SELECT a.attname AS COLUMN_NAME, "
            + "format_type(a.atttypid, a.atttypmod) AS COLUMN_TYPE, t.typname AS DATA_TYPE, "
            + "CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS IS_NULLABLE, "
            + "pg_get_expr(d.adbin, d.adrelid) AS COLUMN_DEFAULT, "
            + "col_description(c.oid, a.attnum) AS COLUMN_COMMENT, a.attnum AS ORDINAL_POSITION, "
            // conkey lists only key columns; pg_index.indkey would also include INCLUDE columns.
            + "CASE WHEN EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k "
            + "WHERE k.conrelid = c.oid AND k.contype = 'p' AND a.attnum = ANY(k.conkey)) "
            + "THEN 'PRI' ELSE '' END AS COLUMN_KEY "
            + "FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
            + "JOIN pg_catalog.pg_type t ON t.oid = a.atttypid "
            + "LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum "
            + "WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped "
            + "AND has_schema_privilege(n.oid, 'USAGE') "
            + "AND has_column_privilege(c.oid, a.attnum, 'SELECT, INSERT, UPDATE, REFERENCES') "
            + "ORDER BY a.attnum";

    /** One datasource connects one database; schemas inside it are the platform's database level. */
    public static String jdbcUrl(String host, int port, String database) {
        String validHost = JdbcUrlParts.requireHost(host);
        String normalizedHost = validHost.contains(":") && !validHost.startsWith("[") ? "[" + validHost + "]" : validHost;
        try {
            String encodedDatabase = URLEncoder.encode(database, StandardCharsets.UTF_8.name()).replace("+", "%20");
            return String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=5&socketTimeout=300&readOnlyMode=always",
                    normalizedHost, port, encodedDatabase);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
