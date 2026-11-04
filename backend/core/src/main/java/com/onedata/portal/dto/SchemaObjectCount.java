package com.onedata.portal.dto;

import lombok.Data;


/**
 * Schema 级对象计数。
 */
@Data
public class SchemaObjectCount {

    private String schemaName;

    private int tableCount;

    private int viewCount;

    private int totalCount;
}
