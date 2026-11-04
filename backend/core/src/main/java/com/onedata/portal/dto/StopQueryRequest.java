package com.onedata.portal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;



/**
 * 终止正在执行的查询请求
 */
@Data
public class StopQueryRequest {

    /**
     * 客户端查询ID（建议传入 DataStudio 的 tabId）
     */
    @NotBlank(message = "clientQueryId 不能为空")
    private String clientQueryId;
}

