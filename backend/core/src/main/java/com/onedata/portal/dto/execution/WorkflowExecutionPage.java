package com.onedata.portal.dto.execution;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;



/**
 * A page of workflow instances and statistics calculated from the same
 * filtered snapshot.
 */
@Data
@Builder
public class WorkflowExecutionPage {

    private long total;
    private int pageNum;
    private int pageSize;
    private List<WorkflowInstanceExecution> records;
    private Map<String, Object> statistics;
}
