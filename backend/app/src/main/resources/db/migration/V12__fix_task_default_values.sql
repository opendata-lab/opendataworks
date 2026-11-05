-- Fix default values for dolphin task parameters
-- Units: timeout_seconds is in minutes for DolphinScheduler, retry_interval is in minutes

-- Update timeout_seconds default from 3600 (seconds) to 1 (minute)
-- Note: Keep column name as timeout_seconds but the value represents minutes for Dolphin
ALTER TABLE `data_task` ALTER COLUMN `timeout_seconds` SET DEFAULT 1;

-- Update retry_times default from 0 to 1
ALTER TABLE `data_task` ALTER COLUMN `retry_times` SET DEFAULT 1;

-- Update retry_interval default from 60 (seconds) to 1 (minute)
-- Note: The value represents minutes for Dolphin
ALTER TABLE `data_task` ALTER COLUMN `retry_interval` SET DEFAULT 1;

-- Update column comments to reflect correct unit (minutes)
ALTER TABLE `data_task` MODIFY COLUMN `timeout_seconds` INT DEFAULT 1 COMMENT '超时时间(分钟)';
ALTER TABLE `data_task` MODIFY COLUMN `retry_interval` INT DEFAULT 1 COMMENT '重试间隔(分钟)';
ALTER TABLE `data_task` MODIFY COLUMN `retry_times` INT DEFAULT 1 COMMENT '重试次数';
