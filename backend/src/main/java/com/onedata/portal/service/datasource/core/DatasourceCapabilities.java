package com.onedata.portal.service.datasource.core;

import com.onedata.portal.service.datasource.model.DatasourceDescriptor.Capability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 能力名称、含义与默认拒绝原因的唯一目录。 */
public final class DatasourceCapabilities {

    private static final Map<String, String> CATALOG;

    static {
        Map<String, String> catalog = new LinkedHashMap<>();
        catalog.put("catalog", "目录读取");
        catalog.put("query", "数据查询");
        catalog.put("preview", "数据预览");
        catalog.put("statistics", "表统计");
        catalog.put("freshnessColumn", "列值新鲜度");
        catalog.put("freshnessCustomSql", "自定义 SQL 新鲜度");
        catalog.put("ddlExport", "DDL 导出");
        catalog.put("structureEdit", "结构编辑");
        catalog.put("partitions", "分区管理");
        catalog.put("distribution", "分布管理");
        catalog.put("replication", "副本管理");
        catalog.put("backup", "备份");
        catalog.put("engineProperties", "引擎属性");
        catalog.put("freshnessMetadata", "元数据新鲜度");
        catalog.put("existingPrimaryKeyEdit", "已有字段主键编辑");
        catalog.put("newPrimaryKeyEdit", "新增字段主键编辑");
        catalog.put("tabletInspection", "Tablet 巡检");
        catalog.put("auditAccess", "Doris 审计访问统计");
        CATALOG = Collections.unmodifiableMap(catalog);
    }

    private DatasourceCapabilities() {
    }

    public static void requireKnown(String name) {
        if (!CATALOG.containsKey(name)) {
            throw new IllegalStateException("UNKNOWN_DATASOURCE_CAPABILITY: " + name);
        }
    }

    public static Map<String, Capability> defaults() {
        Map<String, Capability> result = new LinkedHashMap<>();
        CATALOG.forEach((name, meaning) -> result.put(name, new Capability(false, "此数据源不支持" + meaning)));
        return result;
    }
}
