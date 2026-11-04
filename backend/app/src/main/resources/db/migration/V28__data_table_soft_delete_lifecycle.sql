ALTER TABLE `data_table`
    ADD COLUMN `origin_table_name` VARCHAR(255) DEFAULT NULL COMMENT '软删除前原始表名' AFTER `table_name`,
    ADD COLUMN `deprecated_at` DATETIME DEFAULT NULL COMMENT '软删除时间' AFTER `status`,
    ADD COLUMN `purge_at` DATETIME DEFAULT NULL COMMENT '预计物理删除时间' AFTER `deprecated_at`;

ALTER TABLE `data_table`
    ADD KEY `idx_data_table_purge` (`purge_at`),
    ADD KEY `idx_data_table_deprecated_status` (`status`, `purge_at`);
