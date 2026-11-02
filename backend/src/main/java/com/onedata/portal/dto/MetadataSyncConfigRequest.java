package com.onedata.portal.dto;

import lombok.Data;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

/** 元数据自动采集配置，不接受连接或凭据字段。 */
@Data
public class MetadataSyncConfigRequest {
    @NotNull
    @Min(0)
    @Max(1)
    private Integer autoSync;
    private String syncCron;
}
