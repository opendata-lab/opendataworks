ALTER TABLE `data_task`
    ADD COLUMN `dolphin_flag` VARCHAR(8) NOT NULL DEFAULT 'YES' COMMENT 'Dolphin任务执行标记(YES=正常执行,NO=禁止执行)'
    AFTER `dolphin_task_version`;
