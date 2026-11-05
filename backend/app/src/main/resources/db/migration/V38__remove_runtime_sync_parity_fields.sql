DROP INDEX `idx_sync_record_parity_status` ON `workflow_runtime_sync_record`;

ALTER TABLE `workflow_runtime_sync_record`
    DROP COLUMN `parity_status`,
    DROP COLUMN `parity_detail_json`;
