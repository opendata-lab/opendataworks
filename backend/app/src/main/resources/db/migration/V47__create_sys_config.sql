-- 平台级通用键值配置：供无需独立建表的全局设置使用（首个使用方为智能助手设置）
CREATE TABLE IF NOT EXISTS `sys_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `config_key` VARCHAR(128) NOT NULL COMMENT '配置键，如 metadata.agent_id',
    `config_value` TEXT COMMENT '配置值',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '配置说明',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台级通用键值配置';
