ALTER TABLE `workflow_version`
    ADD COLUMN `snapshot_schema_version` INT DEFAULT 1 COMMENT '快照结构版本',
    ADD COLUMN `rollback_from_version_id` BIGINT DEFAULT NULL COMMENT '回退来源版本ID';

CREATE INDEX `idx_workflow_version_workflow_created`
    ON `workflow_version` (`workflow_id`, `created_at`);

ALTER TABLE `workflow_runtime_sync_record`
    ADD COLUMN `version_id` BIGINT DEFAULT NULL COMMENT '关联生成的版本ID';

CREATE INDEX `idx_sync_record_version`
    ON `workflow_runtime_sync_record` (`version_id`);
