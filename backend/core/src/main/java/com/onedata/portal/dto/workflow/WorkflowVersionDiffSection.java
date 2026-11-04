package com.onedata.portal.dto.workflow;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 版本差异分组
 */
@Data
public class WorkflowVersionDiffSection {

    private List<String> workflowFields = new ArrayList<>();

    private List<String> tasks = new ArrayList<>();

    private List<String> edges = new ArrayList<>();

    private List<String> schedules = new ArrayList<>();
}
