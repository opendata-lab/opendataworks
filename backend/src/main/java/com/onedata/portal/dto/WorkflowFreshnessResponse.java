package com.onedata.portal.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作流详情页「数据新鲜度」页签数据：该工作流写出表的最新状态汇总、每次运行的问题表数、逐表最新结果。
 */
@Data
public class WorkflowFreshnessResponse {

    private Long workflowId;

    /** 当前快照：写出表按最新状态的计数。 */
    private Summary summary;

    /** 每次运行（按触发实例聚合）的问题表数，最近在前。 */
    private List<Run> runs;

    /** 逐表最新结果。 */
    private List<TableStatus> tables;

    @Data
    public static class Summary {
        /** 关联表总数。 */
        private int total;
        /** 写出表数（含 write 与 both）。 */
        private int writeCount;
        /** 读取表数（含 read 与 both）。 */
        private int readCount;
        private int pass;
        private int warn;
        private int error;
        private int runtimeError;
        /** 未配置契约（或从未检查）的表数。 */
        private int unconfigured;
    }

    @Data
    public static class Run {
        /** 触发本次检查的工作流实例ID，可反查执行历史。 */
        private Long workflowInstanceId;
        private LocalDateTime checkedAt;
        /** 本次检查覆盖的表数。 */
        private int total;
        /** 本次检查中非 pass（warn/error/runtime_error）的表数。 */
        private int problem;
    }

    @Data
    public static class TableStatus {
        private Long tableId;
        private String dbName;
        private String tableName;
        /** 关联类型: write | read | both */
        private String relationType;
        private boolean configured;
        /** pass | warn | error | runtime_error；未检查为 null。 */
        private String status;
        private LocalDateTime maxLoadedAt;
        private Long ageSeconds;
        private LocalDateTime checkedAt;
    }
}
