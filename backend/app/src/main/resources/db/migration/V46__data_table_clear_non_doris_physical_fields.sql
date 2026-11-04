ALTER TABLE `data_table`
    MODIFY COLUMN `replica_num` INT DEFAULT NULL COMMENT '副本数';

UPDATE `data_table` dt
LEFT JOIN `doris_cluster` dc ON dt.`cluster_id` = dc.`id`
SET dt.`table_model` = NULL,
    dt.`bucket_num` = NULL,
    dt.`replica_num` = NULL,
    dt.`partition_column` = NULL,
    dt.`distribution_column` = NULL,
    dt.`key_columns` = NULL,
    dt.`doris_ddl` = NULL
WHERE dt.`cluster_id` IS NULL
   OR UPPER(COALESCE(dc.`source_type`, '')) <> 'DORIS';
