ALTER TABLE `data_table`
    CHANGE COLUMN `last_updated` `doris_update_time` DATETIME DEFAULT NULL COMMENT 'Doris最后更新时间';
