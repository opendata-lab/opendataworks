package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * Agent-facing task create/update payload. {@code task} mirrors the platform
 * DataTask fields (taskName, dolphinNodeType, taskSql, datasourceName, ...) and
 * is mapped to the entity by the backend implementation; lineage ids keep the
 * impact graph correct.
 */
@Data
public class AgentTaskUpsertRequest {

    @NotNull(message = "task 不能为空")
    private Map<String, Object> task;

    private List<Long> inputTableIds;

    private List<Long> outputTableIds;
}
