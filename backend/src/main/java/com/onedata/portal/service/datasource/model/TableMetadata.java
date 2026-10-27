package com.onedata.portal.service.datasource.model;

import lombok.Data;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class TableMetadata {

    private String engine;
    private String schemaName;
    private String tableName;
    private String tableType;
    private String tableComment;
    private Timestamp createTime;
    private Timestamp updateTime;
    private Long tableRows;
    private Long dataLength;

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("schemaName", schemaName);
        row.put("tableName", tableName);
        row.put("tableType", tableType);
        row.put("tableComment", tableComment);
        row.put("createTime", createTime);
        row.put("updateTime", updateTime);
        row.put("tableRows", tableRows);
        row.put("dataLength", dataLength);
        return row;
    }
}
