-- Update default timeout_seconds to 60 minutes
-- Previous default was 1 minute, now changed to 60 minutes for longer running tasks

-- Update existing tasks that have the old default value (1 minute)
UPDATE `data_task` SET `timeout_seconds` = 60 WHERE `timeout_seconds` = 1;

-- Change the column default to 60 minutes
ALTER TABLE `data_task` MODIFY COLUMN `timeout_seconds` INT DEFAULT 60 COMMENT '超时时间(分钟)';
