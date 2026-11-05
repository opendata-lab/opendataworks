-- Extend field_comment length to support longer comments
SET NAMES utf8mb4;

ALTER TABLE `data_field` MODIFY COLUMN `field_comment` TEXT DEFAULT NULL COMMENT '字段描述';
