-- Update existing task data with old default values to new defaults
-- This fixes tasks that were created with old defaults (timeout=3600, retry_times=0, retry_interval=60)

-- Update timeout_seconds from 3600 (old default in seconds) to 1 (new default in minutes)
UPDATE `data_task` SET `timeout_seconds` = 1 WHERE `timeout_seconds` = 3600;

-- Update retry_times from 0 (old default) to 1 (new default)
UPDATE `data_task` SET `retry_times` = 1 WHERE `retry_times` = 0;

-- Update retry_interval from 60 (old default in seconds) to 1 (new default in minutes)
UPDATE `data_task` SET `retry_interval` = 1 WHERE `retry_interval` = 60;
