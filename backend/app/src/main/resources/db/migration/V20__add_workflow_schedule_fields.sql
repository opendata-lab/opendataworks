-- Add schedule configuration fields for workflow scheduling (DolphinScheduler)

ALTER TABLE `data_workflow`
    ADD COLUMN `dolphin_schedule_id` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler 调度ID',
    ADD COLUMN `schedule_state` VARCHAR(20) DEFAULT NULL COMMENT '调度状态(ONLINE/OFFLINE)',
    ADD COLUMN `schedule_cron` VARCHAR(255) DEFAULT NULL COMMENT '调度CRON表达式(Quartz)',
    ADD COLUMN `schedule_timezone` VARCHAR(100) DEFAULT NULL COMMENT '调度时区ID(timezoneId)',
    ADD COLUMN `schedule_start_time` DATETIME DEFAULT NULL COMMENT '调度开始时间',
    ADD COLUMN `schedule_end_time` DATETIME DEFAULT NULL COMMENT '调度结束时间',
    ADD COLUMN `schedule_failure_strategy` VARCHAR(20) DEFAULT NULL COMMENT '失败策略(CONTINUE/END)',
    ADD COLUMN `schedule_warning_type` VARCHAR(30) DEFAULT NULL COMMENT '告警类型(NONE/SUCCESS/FAILURE/SUCCESS_FAILURE)',
    ADD COLUMN `schedule_warning_group_id` BIGINT DEFAULT NULL COMMENT '告警组ID';

