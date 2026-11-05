-- Add additional DolphinScheduler schedule execution fields

ALTER TABLE `data_workflow`
    ADD COLUMN `schedule_process_instance_priority` VARCHAR(20) DEFAULT NULL COMMENT '调度实例优先级(HIGHEST/HIGH/MEDIUM/LOW/LOWEST)',
    ADD COLUMN `schedule_worker_group` VARCHAR(100) DEFAULT NULL COMMENT '调度 workerGroup',
    ADD COLUMN `schedule_tenant_code` VARCHAR(100) DEFAULT NULL COMMENT '调度 tenantCode',
    ADD COLUMN `schedule_environment_code` BIGINT DEFAULT NULL COMMENT '调度 environmentCode(-1=default)';

