-- Add schedule auto-online flag (enable schedule automatically when workflow goes ONLINE)

ALTER TABLE `data_workflow`
    ADD COLUMN `schedule_auto_online` TINYINT(1) DEFAULT 0 COMMENT '工作流上线后自动上线调度(0/1)';

