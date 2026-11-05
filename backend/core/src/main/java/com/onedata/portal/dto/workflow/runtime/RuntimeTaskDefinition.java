package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * Dolphin 运行态任务定义（规范化）
 */
@Data
public class RuntimeTaskDefinition {

    private Long taskCode;

    private Integer taskVersion;

    private String taskName;

    private String description;

    private String nodeType;

    private String sql;

    private Long datasourceId;

    private String datasourceName;

    private String datasourceType;

    private Integer timeoutSeconds;

    private Integer retryTimes;

    private Integer retryInterval;

    private String taskPriority;

    private String taskGroupName;

    private Integer taskGroupId;

    private String flag;

    /**
     * SQL 推断出的输入表 ID 列表
     */
    private List<Long> inputTableIds = new ArrayList<>();

    /**
     * SQL 推断出的输出表 ID 列表
     */
    private List<Long> outputTableIds = new ArrayList<>();
}
