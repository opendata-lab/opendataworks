package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 运行态差异摘要
 */
@Data
public class RuntimeDiffSummary {

    private String baselineHash;

    private String currentHash;

    private Boolean changed = false;

    private List<RuntimeDiffFieldChange> workflowFieldChanges = new ArrayList<>();

    private List<RuntimeTaskChange> taskAdded = new ArrayList<>();

    private List<RuntimeTaskChange> taskRemoved = new ArrayList<>();

    private List<RuntimeTaskChange> taskModified = new ArrayList<>();

    private List<RuntimeRelationChange> edgeAdded = new ArrayList<>();

    private List<RuntimeRelationChange> edgeRemoved = new ArrayList<>();

    private List<RuntimeDiffFieldChange> scheduleChanges = new ArrayList<>();
}
