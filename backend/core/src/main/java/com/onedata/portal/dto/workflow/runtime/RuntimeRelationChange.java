package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 任务依赖边差异项
 */
@Data
public class RuntimeRelationChange {

    private Long preTaskCode;

    private String preTaskName;

    private Long postTaskCode;

    private String postTaskName;

    /**
     * preTaskCode=0 入口边
     */
    private Boolean entryEdge = false;

    /**
     * Backward-compatible helper for tests that used string-based diff entries.
     */
    public boolean contains(String text) {
        if (text == null) {
            return false;
        }
        String edge = String.format("%s->%s",
                preTaskCode == null ? "" : preTaskCode,
                postTaskCode == null ? "" : postTaskCode);
        if (edge.contains(text)) {
            return true;
        }
        if (preTaskName != null && preTaskName.contains(text)) {
            return true;
        }
        return postTaskName != null && postTaskName.contains(text);
    }
}
