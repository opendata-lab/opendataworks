package com.onedata.portal.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 表关联任务响应
 */
@Data
public class TableRelatedTasksResponse {

    private List<TableTaskInfo> writeTasks = new ArrayList<>();
    private List<TableTaskInfo> readTasks = new ArrayList<>();
}
