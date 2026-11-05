package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * Dolphin 运行态工作流定义（规范化）
 */
@Data
public class RuntimeWorkflowDefinition {

    private Long projectCode;

    private Long workflowCode;

    private String workflowName;

    private String description;

    private String releaseState;

    /**
     * Dolphin 的 globalParams 原始 JSON 字符串
     */
    private String globalParams;

    private RuntimeWorkflowSchedule schedule;

    private List<RuntimeTaskDefinition> tasks = new ArrayList<>();

    private List<RuntimeTaskEdge> explicitEdges = new ArrayList<>();

    /**
     * 原始定义 JSON，便于审计与回溯
     */
    private String rawDefinitionJson;
}
