package com.onedata.portal.service.audit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 从 Doris 审计记录规范化后的访问事件。
 */
@Data
public class DorisAuditAccessEvent {

    private String eventKey;
    private String cursorKey;
    private LocalDateTime eventTime;
    private String userName;
    private Long queryTimeMs;
    private List<AuditTableReference> tableReferences = new ArrayList<>();
}
