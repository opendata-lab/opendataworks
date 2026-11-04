package com.onedata.portal.dto;

import java.util.List;
import lombok.Data;



/**
 * 表创建请求
 */
@Data
public class TableCreateRequest {

    private String layer;

    private String businessDomain;

    private String dataDomain;

    private String customIdentifier;

    private String statisticsCycle;

    private String updateType;

    private String dbName;

    private String tableComment;

    private String owner;

    private String tableModel;

    private Integer bucketNum;

    private Integer replicaNum;

    private String partitionColumn;

    private List<String> distributionColumns;

    private List<String> keyColumns;

    private Long dorisClusterId;

    private Boolean syncToDoris;

    private String dorisDdl;

    private List<TableColumnRequest> columns;
}
