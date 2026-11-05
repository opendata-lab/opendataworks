SET @has_data_workflow_deleted := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_workflow'
      AND COLUMN_NAME = 'deleted'
);

SET @sql_add_data_workflow_deleted := IF(
    @has_data_workflow_deleted = 0,
    'ALTER TABLE `data_workflow` ADD COLUMN `deleted` TINYINT DEFAULT 0 COMMENT ''逻辑删除标记'' AFTER `updated_at`',
    'SELECT 1'
);

PREPARE stmt_add_data_workflow_deleted FROM @sql_add_data_workflow_deleted;
EXECUTE stmt_add_data_workflow_deleted;
DEALLOCATE PREPARE stmt_add_data_workflow_deleted;

SET @has_idx_data_workflow_deleted_updated := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'data_workflow'
      AND INDEX_NAME = 'idx_data_workflow_deleted_updated'
);

SET @sql_add_idx_data_workflow_deleted_updated := IF(
    @has_idx_data_workflow_deleted_updated = 0,
    'ALTER TABLE `data_workflow` ADD INDEX `idx_data_workflow_deleted_updated` (`deleted`, `updated_at`)',
    'SELECT 1'
);

PREPARE stmt_add_idx_data_workflow_deleted_updated FROM @sql_add_idx_data_workflow_deleted_updated;
EXECUTE stmt_add_idx_data_workflow_deleted_updated;
DEALLOCATE PREPARE stmt_add_idx_data_workflow_deleted_updated;

SET @has_workflow_task_relation_deleted := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workflow_task_relation'
      AND COLUMN_NAME = 'deleted'
);

SET @sql_add_workflow_task_relation_deleted := IF(
    @has_workflow_task_relation_deleted = 0,
    'ALTER TABLE `workflow_task_relation` ADD COLUMN `deleted` TINYINT DEFAULT 0 COMMENT ''逻辑删除标记'' AFTER `updated_at`',
    'SELECT 1'
);

PREPARE stmt_add_workflow_task_relation_deleted FROM @sql_add_workflow_task_relation_deleted;
EXECUTE stmt_add_workflow_task_relation_deleted;
DEALLOCATE PREPARE stmt_add_workflow_task_relation_deleted;

SET @has_idx_wtr_workflow_active := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workflow_task_relation'
      AND INDEX_NAME = 'idx_wtr_workflow_active'
);

SET @sql_add_idx_wtr_workflow_active := IF(
    @has_idx_wtr_workflow_active = 0,
    'ALTER TABLE `workflow_task_relation` ADD INDEX `idx_wtr_workflow_active` (`workflow_id`, `deleted`)',
    'SELECT 1'
);

PREPARE stmt_add_idx_wtr_workflow_active FROM @sql_add_idx_wtr_workflow_active;
EXECUTE stmt_add_idx_wtr_workflow_active;
DEALLOCATE PREPARE stmt_add_idx_wtr_workflow_active;

SET @has_idx_wtr_task_active := (
    SELECT COUNT(1)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'workflow_task_relation'
      AND INDEX_NAME = 'idx_wtr_task_active'
);

SET @sql_add_idx_wtr_task_active := IF(
    @has_idx_wtr_task_active = 0,
    'ALTER TABLE `workflow_task_relation` ADD INDEX `idx_wtr_task_active` (`task_id`, `deleted`)',
    'SELECT 1'
);

PREPARE stmt_add_idx_wtr_task_active FROM @sql_add_idx_wtr_task_active;
EXECUTE stmt_add_idx_wtr_task_active;
DEALLOCATE PREPARE stmt_add_idx_wtr_task_active;
