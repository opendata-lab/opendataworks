ALTER TABLE `metadata_sync_history`
    ADD COLUMN `change_details` MEDIUMTEXT DEFAULT NULL COMMENT '变更明细(JSON)' AFTER `error_details`;
