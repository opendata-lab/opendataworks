package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 运行态任务边
 */
@Data
public class RuntimeTaskEdge {

    private Long upstreamTaskCode;

    private Long downstreamTaskCode;

    public RuntimeTaskEdge() {
    }

    public RuntimeTaskEdge(Long upstreamTaskCode, Long downstreamTaskCode) {
        this.upstreamTaskCode = upstreamTaskCode;
        this.downstreamTaskCode = downstreamTaskCode;
    }
}
