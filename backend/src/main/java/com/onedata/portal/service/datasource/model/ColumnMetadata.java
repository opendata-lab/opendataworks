package com.onedata.portal.service.datasource.model;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ColumnMetadata {

    private String columnName;
    private String dataType;
    private Integer isNullable;
    private String defaultValue;
    private String columnComment;
    private Integer ordinalPosition;
    private String columnKey;
    private Integer isPrimary;

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("columnName", columnName);
        row.put("dataType", dataType);
        row.put("isNullable", isNullable);
        row.put("defaultValue", defaultValue);
        row.put("columnComment", columnComment);
        row.put("ordinalPosition", ordinalPosition);
        row.put("columnKey", columnKey);
        row.put("isPrimary", isPrimary);
        return row;
    }
}
