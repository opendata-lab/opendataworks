package com.onedata.portal.service;

import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.dto.TableNameGenerateRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;



/**
 * 表名生成服务
 */
@Slf4j
@Service
public class TableNameGeneratorService {

    private static final Set<String> VALID_LAYERS = new HashSet<>(Arrays.asList("ods", "dwd", "dim", "dws", "ads"));
    private static final Pattern SEGMENT_PATTERN = Pattern.compile("^[a-z0-9_]+$");

    /**
     * 根据请求生成规范化表名
     */
    public String generate(TableNameGenerateRequest request) {
        return buildComponents(request).getTableName();
    }

    /**
     * 构造表名组件
     */
    public TableNameComponents buildComponents(TableNameGenerateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("表名生成请求不能为空");
        }

        String layer = normalize(request.getLayer());
        validateLayer(layer);
        String businessDomain = normalize(request.getBusinessDomain());
        String dataDomain = normalize(request.getDataDomain());
        String customIdentifier = normalize(request.getCustomIdentifier());
        String statisticsCycle = normalizeNullable(request.getStatisticsCycle());
        String updateType = normalize(request.getUpdateType());

        validateSegment(businessDomain, "businessDomain");
        validateSegment(dataDomain, "dataDomain");
        validateSegment(customIdentifier, "customIdentifier");
        validateSegment(updateType, "updateType");
        if (StringUtils.hasText(statisticsCycle)) {
            validateSegment(statisticsCycle, "statisticsCycle");
        }

        StringBuilder builder = new StringBuilder();
        builder.append(layer);
        builder.append("_").append(businessDomain);
        builder.append("_").append(dataDomain);
        builder.append("_").append(customIdentifier);
        if (StringUtils.hasText(statisticsCycle)) {
            builder.append("_").append(statisticsCycle);
        }
        builder.append("_").append(updateType);

        String tableName = builder.toString();
        log.debug("Generated table name: {}", tableName);

        TableNameComponents components = new TableNameComponents();
        components.setLayer(layer.toUpperCase());
        components.setBusinessDomain(businessDomain);
        components.setDataDomain(dataDomain);
        components.setCustomIdentifier(customIdentifier);
        components.setStatisticsCycle(statisticsCycle);
        components.setUpdateType(updateType);
        components.setTableName(tableName);
        return components;
    }

    /**
     * 从表创建请求构造表名生成请求
     */
    public TableNameGenerateRequest fromCreateRequest(TableCreateRequest request) {
        TableNameGenerateRequest generateRequest = new TableNameGenerateRequest();
        generateRequest.setLayer(request.getLayer());
        generateRequest.setBusinessDomain(request.getBusinessDomain());
        generateRequest.setDataDomain(request.getDataDomain());
        generateRequest.setCustomIdentifier(request.getCustomIdentifier());
        generateRequest.setStatisticsCycle(request.getStatisticsCycle());
        generateRequest.setUpdateType(request.getUpdateType());
        return generateRequest;
    }

    private void validateLayer(String layer) {
        if (!StringUtils.hasText(layer)) {
            throw new RuntimeException("数据分层不能为空");
        }
        if (!VALID_LAYERS.contains(layer)) {
            throw new RuntimeException("不支持的数据分层: " + layer);
        }
    }

    private void validateSegment(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new RuntimeException(fieldName + " 不能为空");
        }
        if (!SEGMENT_PATTERN.matcher(value).matches()) {
            throw new RuntimeException(fieldName + " 仅支持小写字母、数字和下划线");
        }
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase().replace("-", "_");
    }

    private String normalizeNullable(String value) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    /**
     * 表名组件结果
     */
    public static class TableNameComponents {
        private String layer;
        private String businessDomain;
        private String dataDomain;
        private String customIdentifier;
        private String statisticsCycle;
        private String updateType;
        private String tableName;

        public String getLayer() {
            return layer;
        }

        public void setLayer(String layer) {
            this.layer = layer;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public void setBusinessDomain(String businessDomain) {
            this.businessDomain = businessDomain;
        }

        public String getDataDomain() {
            return dataDomain;
        }

        public void setDataDomain(String dataDomain) {
            this.dataDomain = dataDomain;
        }

        public String getCustomIdentifier() {
            return customIdentifier;
        }

        public void setCustomIdentifier(String customIdentifier) {
            this.customIdentifier = customIdentifier;
        }

        public String getStatisticsCycle() {
            return statisticsCycle;
        }

        public void setStatisticsCycle(String statisticsCycle) {
            this.statisticsCycle = statisticsCycle;
        }

        public String getUpdateType() {
            return updateType;
        }

        public void setUpdateType(String updateType) {
            this.updateType = updateType;
        }

        public String getTableName() {
            return tableName;
        }

        public void setTableName(String tableName) {
            this.tableName = tableName;
        }
    }
}
