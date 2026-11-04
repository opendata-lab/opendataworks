ALTER TABLE `data_workflow`
    MODIFY COLUMN `definition_json` LONGTEXT DEFAULT NULL COMMENT '定义JSON';

ALTER TABLE `workflow_version`
    MODIFY COLUMN `structure_snapshot` LONGTEXT DEFAULT NULL COMMENT '工作流结构快照JSON',
    MODIFY COLUMN `change_summary` LONGTEXT DEFAULT NULL COMMENT '版本变更摘要';
