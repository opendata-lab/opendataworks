-- Add task group configuration fields for DolphinScheduler integration

ALTER TABLE `data_workflow`
    ADD COLUMN `task_group_name` VARCHAR(100) DEFAULT NULL COMMENT '默认任务组名称(用于 DolphinScheduler 任务组)';

ALTER TABLE `data_task`
    ADD COLUMN `task_group_name` VARCHAR(100) DEFAULT NULL COMMENT '任务组名称(空表示继承工作流默认任务组)';

