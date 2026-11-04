-- PostgreSQL connects to one database and enumerates its schemas.
ALTER TABLE `doris_cluster`
    ADD COLUMN `database_name` VARCHAR(255) DEFAULT NULL COMMENT 'PostgreSQL连接数据库' AFTER `source_type`;
