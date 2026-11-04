package com.onedata.portal.dto;



/**
 * 表物理位置（数据库名 + 实际表名）。
 *
 * <p>用于在控制器与服务之间统一传递「去前缀后的库表标识」，替代此前在多处重复的
 * dbName / tableName 解析块。
 */
public class TableLocation {

    private final String database;
    private final String tableName;

    public TableLocation(String database, String tableName) {
        this.database = database;
        this.tableName = tableName;
    }

    public String getDatabase() {
        return database;
    }

    public String getTableName() {
        return tableName;
    }
}
