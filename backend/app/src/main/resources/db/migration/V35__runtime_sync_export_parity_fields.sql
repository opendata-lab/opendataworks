ALTER TABLE `workflow_runtime_sync_record`
    ADD COLUMN `ingest_mode` VARCHAR(32) DEFAULT NULL COMMENT '运行态定义采集模式: legacy/export_shadow/export_only',
    ADD COLUMN `parity_status` VARCHAR(32) DEFAULT NULL COMMENT '导出与旧路径一致性: not_checked/consistent/inconsistent',
    ADD COLUMN `parity_detail_json` LONGTEXT DEFAULT NULL COMMENT '一致性详情JSON',
    ADD COLUMN `raw_definition_json` LONGTEXT DEFAULT NULL COMMENT '原始导出定义JSON';

CREATE INDEX `idx_sync_record_parity_status`
    ON `workflow_runtime_sync_record` (`parity_status`);
