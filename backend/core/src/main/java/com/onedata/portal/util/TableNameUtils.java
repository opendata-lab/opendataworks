package com.onedata.portal.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;



/**
 * Table name related helpers.
 */
public final class TableNameUtils {

    private static final Set<String> VALID_LAYER_PREFIXES = new HashSet<>(
            Arrays.asList("ods", "dwd", "dim", "dws", "ads"));

    private TableNameUtils() {
    }

    /**
     * Infer table layer from name prefix, e.g. ods_xxx -> ODS.
     */
    public static String inferLayerFromTableName(String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        String normalized = tableName.trim().toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('_');
        if (separatorIndex <= 0) {
            return null;
        }
        String prefix = normalized.substring(0, separatorIndex);
        if (!VALID_LAYER_PREFIXES.contains(prefix)) {
            return null;
        }
        return prefix.toUpperCase(Locale.ROOT);
    }
}
