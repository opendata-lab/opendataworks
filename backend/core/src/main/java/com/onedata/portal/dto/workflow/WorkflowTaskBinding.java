package com.onedata.portal.dto.workflow;

import java.util.Map;
import lombok.Data;



/**
 * 任务绑定信息
 */
@Data
public class WorkflowTaskBinding {

    private Long taskId;

    private Boolean entry;

    private Boolean exit;

    private Map<String, Object> nodeAttrs;
}
