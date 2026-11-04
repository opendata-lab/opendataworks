-- 权限管理系统数据表
-- 创建时间: 2025-12-12

-- 1. 平台用户表
CREATE TABLE IF NOT EXISTS `platform_users` (
    `id` VARCHAR(64) PRIMARY KEY COMMENT '用户ID',
    `oauth_user_id` VARCHAR(128) UNIQUE NOT NULL COMMENT 'OAuth用户ID',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `email` VARCHAR(128) COMMENT '邮箱',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台用户表';

-- 2. Doris数据库用户配置表（每个数据库的标准用户）
CREATE TABLE IF NOT EXISTS `doris_database_users` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris集群ID',
    `database_name` VARCHAR(64) NOT NULL COMMENT '数据库名',
    `readonly_username` VARCHAR(64) NOT NULL COMMENT '只读用户名',
    `readonly_password` VARCHAR(128) NOT NULL COMMENT '只读用户密码',
    `readwrite_username` VARCHAR(64) NOT NULL COMMENT '读写用户名',
    `readwrite_password` VARCHAR(128) NOT NULL COMMENT '读写用户密码',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cluster_database` (`cluster_id`, `database_name`),
    FOREIGN KEY (`cluster_id`) REFERENCES `doris_cluster`(`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doris数据库用户配置表';

-- 3. 用户数据库权限表（核心权限控制）
CREATE TABLE IF NOT EXISTS `user_database_permissions` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id` VARCHAR(64) NOT NULL COMMENT '用户ID',
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris集群ID',
    `database_name` VARCHAR(64) NOT NULL COMMENT '数据库名',
    `permission_level` ENUM('readonly', 'readwrite') NOT NULL COMMENT '权限级别',
    `granted_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
    `granted_by` VARCHAR(64) COMMENT '授权人',
    `expires_at` DATETIME NULL COMMENT '权限过期时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_database` (`user_id`, `cluster_id`, `database_name`),
    FOREIGN KEY (`user_id`) REFERENCES `platform_users`(`id`),
    FOREIGN KEY (`cluster_id`) REFERENCES `doris_cluster`(`id`),
    FOREIGN KEY (`granted_by`) REFERENCES `platform_users`(`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_cluster_database` (`cluster_id`, `database_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户数据库权限表';
