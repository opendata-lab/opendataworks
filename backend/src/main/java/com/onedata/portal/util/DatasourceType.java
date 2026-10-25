package com.onedata.portal.util;

import java.util.Locale;

/**
 * Normalized datasource engine type.
 */
public enum DatasourceType {
    DORIS,
    MYSQL,
    POSTGRESQL,
    OCEANBASE,
    UNKNOWN;

    public static DatasourceType from(String sourceType) {
        if (sourceType == null || sourceType.trim().isEmpty()) {
            return UNKNOWN;
        }
        String normalized = sourceType.trim().toUpperCase(Locale.ROOT);
        for (DatasourceType value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
