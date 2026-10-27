-- OpenDataWorks 数据库初始化脚本
-- 此脚本会在 MySQL 容器首次启动时自动执行
-- 用于确保数据库和用户的正确创建，即使环境变量已创建，也会确保字符集和权限正确

-- 设置字符集
SET NAMES utf8mb4;

-- 如果数据库不存在则创建（虽然环境变量会创建，但这里确保字符集正确）
CREATE DATABASE IF NOT EXISTS `opendataworks` 
  DEFAULT CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE `opendataworks`;

-- 如果用户不存在则创建（虽然环境变量会创建，但这里确保权限正确）
-- 注意：CREATE USER IF NOT EXISTS 在 MySQL 8.0+ 中可用
CREATE USER IF NOT EXISTS 'opendataworks'@'%' IDENTIFIED BY 'opendataworks123';

-- 授予权限
GRANT ALL PRIVILEGES ON `opendataworks`.* TO 'opendataworks'@'%';

-- 刷新权限
FLUSH PRIVILEGES;
