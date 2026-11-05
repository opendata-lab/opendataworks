ALTER TABLE `data_table`
    ADD COLUMN `table_type` VARCHAR(32) DEFAULT NULL COMMENT '表类型(BASE TABLE/VIEW/MATERIALIZED VIEW)' AFTER `table_name`;

UPDATE `data_table`
SET `table_type` = 'VIEW'
WHERE `table_type` IS NULL
  AND `doris_ddl` IS NOT NULL
  AND UPPER(`doris_ddl`) LIKE 'CREATE%VIEW%';

UPDATE `data_table`
SET `table_type` = 'BASE TABLE'
WHERE `table_type` IS NULL;

CREATE INDEX `idx_cluster_db_table_type` ON `data_table` (`cluster_id`, `db_name`, `table_type`);
