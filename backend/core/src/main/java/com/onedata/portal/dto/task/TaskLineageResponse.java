package com.onedata.portal.dto.task;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;



/**
 * 任务血缘关系响应（输入表与输出表）。
 *
 * <p>此前定义为 {@code DataTaskController} 的内部类，导致 Service 反向依赖 Controller。
 * 提到 DTO 包后，Controller 与 Service 都从这里引用。JSON 结构不变。
 */
@Data
@AllArgsConstructor
public class TaskLineageResponse {

    private List<Long> inputTableIds;

    private List<Long> outputTableIds;
}
