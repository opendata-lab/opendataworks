CREATE TABLE `dolphin_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `url` varchar(255) NOT NULL COMMENT 'DolphinScheduler API地址',
  `token` varchar(255) NOT NULL COMMENT '访问令牌',
  `project_name` varchar(100) NOT NULL COMMENT '项目名称',
  `project_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `tenant_code` varchar(50) DEFAULT 'default' COMMENT '租户编码',
  `worker_group` varchar(50) DEFAULT 'default' COMMENT 'Worker分组',
  `execution_type` varchar(20) DEFAULT 'PARALLEL' COMMENT '执行类型',
  `is_active` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DolphinScheduler配置表';

-- Insert default configuration
INSERT INTO `dolphin_config` (`url`, `token`, `project_name`, `project_code`, `tenant_code`)
VALUES ('http://localhost:12345/dolphinscheduler', '383de56bcc1e2e30b871872cf76d4feb', 'opendataworks', '0', 'default');
