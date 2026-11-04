package com.onedata.portal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;


/** 元数据自动采集配置，不接受连接或凭据字段。 */
@Data
public class MetadataSyncConfigRequest {
    @NotNull
    @Min(0)
    @Max(1)
    private Integer autoSync;
    private String syncCron;
}
