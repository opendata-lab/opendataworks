package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.DatasourceTypeDefinition;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Doris 集群配置实体
 */
@Data
@TableName(value = "doris_cluster", autoResultMap = true)
public class DorisCluster {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String clusterName;

    /**
     * 数据源类型：DORIS / MYSQL / POSTGRESQL
     */
    private String sourceType;

    @TableField(exist = false)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private DatasourceTypeDefinition typeDefinition;

    /** PostgreSQL 连接数据库；其内的 schema 使用现有 dbName 表示。 */
    private String databaseName;

    @TableField(typeHandler = JacksonTypeHandler.class)
    @JsonIgnore
    private Map<String, Object> connectionOptions;

    @JsonProperty("connectionOptions")
    public void setConnectionOptions(Map<String, Object> options) {
        this.connectionOptions = options;
    }

    @JsonGetter("connectionOptions")
    public Map<String, Object> visibleConnectionOptions() {
        Map<String, Object> visible = new LinkedHashMap<>();
        if (connectionOptions != null && typeDefinition != null) {
            for (DatasourceDescriptor.ConnectionField field
                    : typeDefinition.getDescriptor().getConnectionFields()) {
                if (!"password".equals(field.getControl()) && connectionOptions.containsKey(field.getKey())) {
                    visible.put(field.getKey(), connectionOptions.get(field.getKey()));
                }
            }
        }
        return visible;
    }

    private String feHost;

    private Integer fePort;

    private String username;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Integer isDefault;

    private String status;

    /**
     * 是否开启元数据自动同步（0/1）
     */
    private Integer autoSync;

    /**
     * 元数据同步 Cron 表达式
     */
    private String syncCron;

    /**
     * 最近一次自动同步时间
     */
    private LocalDateTime lastSyncTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
