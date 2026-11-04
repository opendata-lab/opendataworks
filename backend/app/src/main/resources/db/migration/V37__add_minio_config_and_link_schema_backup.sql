CREATE TABLE IF NOT EXISTS `minio_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `config_name` VARCHAR(100) NOT NULL COMMENT '环境名称',
    `endpoint` VARCHAR(255) NOT NULL COMMENT 'MinIO Endpoint',
    `region` VARCHAR(64) DEFAULT 'us-east-1' COMMENT 'Region',
    `access_key` VARCHAR(200) NOT NULL COMMENT 'AccessKey',
    `secret_key` VARCHAR(200) NOT NULL COMMENT 'SecretKey',
    `use_path_style` TINYINT DEFAULT 1 COMMENT '是否启用Path Style(1-是,0-否)',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '备注说明',
    `is_default` TINYINT DEFAULT 0 COMMENT '是否默认环境',
    `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态(active/inactive)',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_minio_config_name` (`config_name`),
    KEY `idx_minio_default` (`is_default`),
    KEY `idx_minio_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MinIO环境配置表';

SET @has_minio_config_id := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schema_backup_config'
      AND COLUMN_NAME = 'minio_config_id'
);

SET @sql_add_minio_config_id := IF(
    @has_minio_config_id = 0,
    'ALTER TABLE `schema_backup_config` ADD COLUMN `minio_config_id` BIGINT DEFAULT NULL COMMENT ''关联的MinIO环境ID'' AFTER `repository_name`',
    'SELECT 1'
);

PREPARE stmt_add_minio_config_id FROM @sql_add_minio_config_id;
EXECUTE stmt_add_minio_config_id;
DEALLOCATE PREPARE stmt_add_minio_config_id;

SET @has_idx_schema_backup_minio_config_id := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'schema_backup_config'
      AND INDEX_NAME = 'idx_schema_backup_minio_config_id'
);

SET @sql_add_idx_schema_backup_minio_config_id := IF(
    @has_idx_schema_backup_minio_config_id = 0,
    'ALTER TABLE `schema_backup_config` ADD INDEX `idx_schema_backup_minio_config_id` (`minio_config_id`)',
    'SELECT 1'
);

PREPARE stmt_add_idx_schema_backup_minio_config_id FROM @sql_add_idx_schema_backup_minio_config_id;
EXECUTE stmt_add_idx_schema_backup_minio_config_id;
DEALLOCATE PREPARE stmt_add_idx_schema_backup_minio_config_id;
