-- 表元数据版本快照：仅当白名单元数据（表属性 + 字段列表）哈希变化时追加一行
CREATE TABLE IF NOT EXISTS `data_table_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `table_id` BIGINT NOT NULL COMMENT '关联 data_table.id',
    `version_no` INT NOT NULL COMMENT '表内自增版本号，从 1 开始',
    `snapshot_hash` CHAR(64) NOT NULL COMMENT '规范化快照 SHA-256',
    `metadata_snapshot` MEDIUMTEXT COMMENT '规范化元数据快照 JSON',
    `change_summary` VARCHAR(500) DEFAULT NULL COMMENT '相对上一版本的变更摘要',
    `trigger_source` VARCHAR(50) DEFAULT NULL COMMENT 'table_create/manual_edit/metadata_sync/inspection_fix',
    `created_by` VARCHAR(100) DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_version` (`table_id`, `version_no`),
    KEY `idx_table_id_created` (`table_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表元数据版本快照';
