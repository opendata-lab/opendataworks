package com.onedata.portal.dto;

import lombok.Data;

import java.util.List;

/**
 * Table metadata completion payload. Every section is optional and applied
 * independently: table comment, controlled attributes (layer / business domain / data domain),
 * per-field comments, and the freshness contract. The backend validates controlled values
 * server-side and reports per-section applied / skipped / failed outcomes.
 *
 * <p>Provide {@code tableId} (preferred) or {@code database + table} to locate the target table.
 */
@Data
public class MetadataCompleteRequest {

    private Long tableId;

    private String database;

    private String table;

    private String tableComment;

    private Attributes attributes;

    private List<FieldComment> fields;

    private Freshness freshness;

    @Data
    public static class Attributes {
        private String layer;
        private String businessDomain;
        private String dataDomain;
    }

    @Data
    public static class FieldComment {
        private Long fieldId;
        private String fieldName;
        private String comment;
    }

    /** Mirrors the platform {@code TableFreshnessRequest}; mode defaults to {@code column}. */
    @Data
    public static class Freshness {
        private String mode;
        private String loadedAtField;
        private String loadedAtQuery;
        private String filterExpr;
        private Integer warnAfterCount;
        private String warnAfterPeriod;
        private Integer errorAfterCount;
        private String errorAfterPeriod;
        private Boolean enabled;
    }
}
