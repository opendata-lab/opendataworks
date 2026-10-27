package com.onedata.portal.service.datasource.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Value;

/**
 * 复用类型描述并附加运行时驱动状态，不包含凭据。
 */
@Value
public class DatasourceTypeDefinition {

    @JsonUnwrapped
    DatasourceDescriptor descriptor;

    String driverStatus;
}
