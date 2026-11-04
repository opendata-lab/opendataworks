-- Unify partition column naming:
--   legacy: partition_field
--   target: partition_column

SET @table_schema = DATABASE();

SET @has_old := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @table_schema
      AND TABLE_NAME = 'data_table'
      AND COLUMN_NAME = 'partition_field'
);

SET @has_new := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @table_schema
      AND TABLE_NAME = 'data_table'
      AND COLUMN_NAME = 'partition_column'
);

SET @copy_sql := IF(
    @has_old > 0 AND @has_new > 0,
    'UPDATE `data_table` SET `partition_column` = COALESCE(`partition_column`, `partition_field`)',
    'SELECT 1'
);
PREPARE stmt FROM @copy_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @rename_sql := IF(
    @has_old > 0 AND @has_new = 0,
    'ALTER TABLE `data_table` CHANGE COLUMN `partition_field` `partition_column` VARCHAR(100) DEFAULT NULL COMMENT ''分区字段''',
    'SELECT 1'
);
PREPARE stmt FROM @rename_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @drop_sql := IF(
    @has_old > 0 AND @has_new > 0,
    'ALTER TABLE `data_table` DROP COLUMN `partition_field`',
    'SELECT 1'
);
PREPARE stmt FROM @drop_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
