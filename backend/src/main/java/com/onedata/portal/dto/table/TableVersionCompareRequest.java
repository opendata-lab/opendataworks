package com.onedata.portal.dto.table;

import lombok.Data;

/**
 * 表元数据版本对比请求
 */
@Data
public class TableVersionCompareRequest {

    private Long leftVersionId;

    private Long rightVersionId;
}
