SET NAMES utf8mb4;

ALTER TABLE `data_table`
    ADD COLUMN `cluster_id` BIGINT DEFAULT NULL COMMENT '数据源ID' AFTER `id`;

ALTER TABLE `data_table` DROP INDEX `uk_db_table_name`;
ALTER TABLE `data_table` ADD UNIQUE KEY `uk_cluster_db_table` (`cluster_id`, `db_name`, `table_name`);
ALTER TABLE `data_table` ADD INDEX `idx_cluster_db` (`cluster_id`, `db_name`);
