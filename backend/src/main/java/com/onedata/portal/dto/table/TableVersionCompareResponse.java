package com.onedata.portal.dto.table;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 表元数据版本对比结果
 */
@Data
public class TableVersionCompareResponse {

    private Long tableId;

    private Long leftVersionId;

    private Integer leftVersionNo;

    private Long rightVersionId;

    private Integer rightVersionNo;

    private Boolean changed;

    /**
     * 表级属性变更（如 tableComment、bucketNum）
     */
    private List<AttributeChange> tableAttributeChanges = new ArrayList<>();

    private List<String> columnsAdded = new ArrayList<>();

    private List<String> columnsRemoved = new ArrayList<>();

    private List<ColumnChange> columnsModified = new ArrayList<>();

    private Summary summary = new Summary();

    /**
     * 基于快照 JSON 的 unified diff，供前端原始视图渲染
     */
    private String rawDiff;

    @Data
    public static class AttributeChange {
        private String name;
        private String oldValue;
        private String newValue;
    }

    @Data
    public static class ColumnChange {
        private String fieldName;
        private List<AttributeChange> changes = new ArrayList<>();
    }

    @Data
    public static class Summary {
        private int attributeChangedCount;
        private int columnsAddedCount;
        private int columnsRemovedCount;
        private int columnsModifiedCount;
    }
}
