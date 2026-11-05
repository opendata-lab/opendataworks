package com.onedata.portal.service.audit;

import lombok.Data;


/**
 * 一条审计 SQL 对数据表的访问角色。
 */
@Data
public class AuditTableReference {

    private final String databaseName;
    private final String tableName;
    private boolean read;
    private boolean write;
}
