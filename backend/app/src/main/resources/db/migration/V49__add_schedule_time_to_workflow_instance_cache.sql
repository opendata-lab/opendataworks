-- 工作流实例缓存补充「调度日期」，并为按开始时间倒序的查询建立索引。
-- schedule_time 来自 DolphinScheduler 运行实例的 scheduleTime，
-- 在补数（COMPLEMENT_DATA）实例上标识补的是哪一个调度周期。
ALTER TABLE `workflow_instance_cache`
    ADD COLUMN `schedule_time` DATETIME DEFAULT NULL COMMENT '调度日期' AFTER `end_time`;

-- 执行监控降级路径与 listRecent 都按 start_time 倒序取最近若干条，
-- 该表此前只有主键索引。
ALTER TABLE `workflow_instance_cache`
    ADD INDEX `idx_start_time` (`start_time`);
