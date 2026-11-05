-- Extend doris_cluster to support generic datasource configuration

ALTER TABLE `doris_cluster`
    ADD COLUMN `source_type` VARCHAR(20) NOT NULL DEFAULT 'DORIS' COMMENT '数据源类型(DORIS/MYSQL)' AFTER `cluster_name`,
    ADD COLUMN `auto_sync` TINYINT DEFAULT 0 COMMENT '是否开启元数据自动同步(0-否,1-是)' AFTER `status`,
    ADD COLUMN `sync_cron` VARCHAR(100) DEFAULT NULL COMMENT '元数据同步 Cron 表达式' AFTER `auto_sync`,
    ADD COLUMN `last_sync_time` DATETIME DEFAULT NULL COMMENT '最近一次自动同步时间' AFTER `sync_cron`;

