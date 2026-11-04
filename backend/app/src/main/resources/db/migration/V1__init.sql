-- Core Schema (from 10-core-schema.sql)
SET NAMES utf8mb4;

-- 业务域配置表
CREATE TABLE IF NOT EXISTS `business_domain` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `domain_code` VARCHAR(50) NOT NULL COMMENT '业务域代码',
    `domain_name` VARCHAR(100) NOT NULL COMMENT '业务域名称',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_code` (`domain_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务域配置表';

-- 数据域配置表
CREATE TABLE IF NOT EXISTS `data_domain` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `domain_code` VARCHAR(50) NOT NULL COMMENT '数据域代码',
    `domain_name` VARCHAR(100) NOT NULL COMMENT '数据域名称',
    `business_domain` VARCHAR(50) DEFAULT NULL COMMENT '所属业务域',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '描述',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_domain_code` (`domain_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据域配置表';

-- Doris集群配置表
CREATE TABLE IF NOT EXISTS `doris_cluster` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `cluster_name` VARCHAR(100) NOT NULL COMMENT '集群名称',
    `fe_host` VARCHAR(100) NOT NULL COMMENT 'FE Host',
    `fe_port` INT NOT NULL DEFAULT 9030 COMMENT 'FE MySQL端口',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password` VARCHAR(200) NOT NULL COMMENT '密码',
    `is_default` TINYINT DEFAULT 0 COMMENT '是否默认集群',
    `status` VARCHAR(20) DEFAULT 'active' COMMENT '状态',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cluster_host_port` (`fe_host`, `fe_port`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doris集群配置表';

-- 数据表管理表
CREATE TABLE IF NOT EXISTS `data_table` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_name` VARCHAR(100) NOT NULL COMMENT '表名',
    `table_comment` VARCHAR(500) DEFAULT NULL COMMENT '表描述',
    `layer` ENUM('ODS', 'DWD', 'DIM', 'DWS', 'ADS') NOT NULL COMMENT '数据层级',
    `business_domain` VARCHAR(50) DEFAULT NULL COMMENT '业务域',
    `data_domain` VARCHAR(50) DEFAULT NULL COMMENT '数据域',
    `custom_identifier` VARCHAR(100) DEFAULT NULL COMMENT '自定义表标识',
    `statistics_cycle` VARCHAR(20) DEFAULT NULL COMMENT '统计周期(如: 10m, 1h, 1d)',
    `update_type` VARCHAR(10) DEFAULT NULL COMMENT '更新类型(di/df/hi/hf/ri)',
    `table_model` VARCHAR(20) DEFAULT NULL COMMENT 'Doris表模型(DUPLICATE/AGGREGATE/UNIQUE)',
    `bucket_num` INT DEFAULT NULL COMMENT '分桶数',
    `replica_num` INT DEFAULT 1 COMMENT '副本数',
    `partition_field` VARCHAR(100) DEFAULT NULL COMMENT '分区字段',
    `distribution_column` VARCHAR(100) DEFAULT NULL COMMENT '分桶字段',
    `key_columns` VARCHAR(500) DEFAULT NULL COMMENT '主键列(逗号分隔)',
    `doris_ddl` TEXT DEFAULT NULL COMMENT '生成的Doris DDL',
    `is_synced` TINYINT DEFAULT 0 COMMENT '是否已同步到Doris(0-未同步,1-已同步)',
    `sync_time` DATETIME DEFAULT NULL COMMENT 'Doris同步时间',
    `db_name` VARCHAR(100) DEFAULT NULL COMMENT '数据库名',
    `owner` VARCHAR(50) DEFAULT NULL COMMENT '负责人',
    `status` ENUM('active', 'inactive', 'deprecated') DEFAULT 'active' COMMENT '状态',
    `lifecycle_days` INT DEFAULT NULL COMMENT '生命周期(天)',
    `storage_size` BIGINT DEFAULT 0 COMMENT '存储大小(字节)',
    `row_count` BIGINT DEFAULT 0 COMMENT '行数',
    `last_updated` DATETIME DEFAULT NULL COMMENT '最后更新时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_name` (`table_name`),
    KEY `idx_layer` (`layer`),
    KEY `idx_owner` (`owner`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表元信息';

-- 字段定义表
CREATE TABLE IF NOT EXISTS `data_field` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_id` BIGINT NOT NULL COMMENT '所属表ID',
    `field_name` VARCHAR(100) NOT NULL COMMENT '字段名',
    `field_type` VARCHAR(50) NOT NULL COMMENT '字段类型',
    `field_comment` VARCHAR(500) DEFAULT NULL COMMENT '字段描述',
    `is_nullable` TINYINT DEFAULT 1 COMMENT '是否可为空',
    `is_partition` TINYINT DEFAULT 0 COMMENT '是否分区字段',
    `is_primary` TINYINT DEFAULT 0 COMMENT '是否主键',
    `default_value` VARCHAR(200) DEFAULT NULL COMMENT '默认值',
    `field_order` INT DEFAULT 0 COMMENT '字段排序',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_table_id` (`table_id`),
    UNIQUE KEY `uk_table_field` (`table_id`, `field_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字段定义';

-- 任务定义表
CREATE TABLE IF NOT EXISTS `data_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_name` VARCHAR(100) NOT NULL COMMENT '任务名称',
    `task_code` VARCHAR(100) NOT NULL COMMENT '任务编码',
    `task_type` ENUM('batch', 'stream') DEFAULT 'batch' COMMENT '任务类型',
    `engine` ENUM('dolphin', 'dinky') DEFAULT 'dolphin' COMMENT '执行引擎',
    `task_sql` TEXT NOT NULL COMMENT '任务SQL',
    `task_desc` VARCHAR(1000) DEFAULT NULL COMMENT '任务描述',
    `schedule_cron` VARCHAR(100) DEFAULT NULL COMMENT '调度CRON表达式',
    `priority` INT DEFAULT 5 COMMENT '优先级(1-10)',
    `timeout_seconds` INT DEFAULT 3600 COMMENT '超时时间(秒)',
    `retry_times` INT DEFAULT 0 COMMENT '重试次数',
    `retry_interval` INT DEFAULT 60 COMMENT '重试间隔(秒)',
    `owner` VARCHAR(50) DEFAULT NULL COMMENT '负责人',
    `status` ENUM('draft', 'published', 'running', 'paused', 'failed') DEFAULT 'draft' COMMENT '状态',
    `dolphin_process_code` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler流程编码',
    `dolphin_schedule_id` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler调度ID',
    `dolphin_task_code` BIGINT DEFAULT NULL COMMENT 'DolphinScheduler任务编码',
    `dolphin_task_version` INT DEFAULT 1 COMMENT 'DolphinScheduler任务版本',
    `dolphin_node_type` VARCHAR(20) DEFAULT NULL COMMENT 'Dolphin节点类型(SHELL/SQL/PYTHON/SPARK/FLINK)',
    `datasource_name` VARCHAR(100) DEFAULT NULL COMMENT 'SQL节点数据源名称',
    `datasource_type` VARCHAR(20) DEFAULT NULL COMMENT '数据源类型(MYSQL/DORIS等)',
    `dinky_job_id` BIGINT DEFAULT NULL COMMENT 'Dinky作业ID',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_code` (`task_code`),
    KEY `idx_task_type` (`task_type`),
    KEY `idx_engine` (`engine`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务定义';

-- 血缘关系表
CREATE TABLE IF NOT EXISTS `data_lineage` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `upstream_table_id` BIGINT DEFAULT NULL COMMENT '上游表ID',
    `downstream_table_id` BIGINT DEFAULT NULL COMMENT '下游表ID',
    `lineage_type` ENUM('input', 'output') NOT NULL COMMENT '血缘类型',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_upstream` (`upstream_table_id`),
    KEY `idx_downstream` (`downstream_table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='血缘关系';

-- 任务执行日志表
CREATE TABLE IF NOT EXISTS `task_execution_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `execution_id` VARCHAR(100) DEFAULT NULL COMMENT '外部执行ID',
    `status` ENUM('pending', 'running', 'success', 'failed', 'killed') DEFAULT 'pending' COMMENT '执行状态',
    `start_time` DATETIME DEFAULT NULL COMMENT '开始时间',
    `end_time` DATETIME DEFAULT NULL COMMENT '结束时间',
    `duration_seconds` INT DEFAULT NULL COMMENT '执行时长(秒)',
    `rows_output` BIGINT DEFAULT 0 COMMENT '输出行数',
    `error_message` TEXT DEFAULT NULL COMMENT '错误信息',
    `log_url` VARCHAR(500) DEFAULT NULL COMMENT '日志链接',
    `trigger_type` ENUM('manual', 'schedule', 'api') DEFAULT 'schedule' COMMENT '触发方式',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_status` (`status`),
    KEY `idx_start_time` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务执行日志';

-- SQL 查询历史表
CREATE TABLE IF NOT EXISTS `data_query_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `cluster_id` BIGINT DEFAULT NULL COMMENT 'Doris 集群ID',
    `cluster_name` VARCHAR(100) DEFAULT NULL COMMENT '集群名称',
    `database_name` VARCHAR(100) DEFAULT NULL COMMENT '数据库名称',
    `sql_text` TEXT NOT NULL COMMENT '执行 SQL',
    `preview_row_count` INT DEFAULT 0 COMMENT '返回的预览行数',
    `duration_ms` BIGINT DEFAULT 0 COMMENT '执行耗时（毫秒）',
    `has_more` TINYINT DEFAULT 0 COMMENT '是否存在更多数据',
    `result_preview` MEDIUMTEXT DEFAULT NULL COMMENT '结果预览(JSON)',
    `executed_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    PRIMARY KEY (`id`),
    KEY `idx_cluster_id` (`cluster_id`),
    KEY `idx_executed_at` (`executed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SQL 查询历史记录';


-- 表与任务关联关系表
CREATE TABLE IF NOT EXISTS `table_task_relation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_id` BIGINT NOT NULL COMMENT '表ID',
    `task_id` BIGINT NOT NULL COMMENT '任务ID',
    `relation_type` VARCHAR(20) NOT NULL COMMENT '关联类型(read-读取/write-写入)',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_table_task` (`table_id`, `task_id`, `relation_type`),
    KEY `idx_table_id` (`table_id`),
    KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表与任务关联关系表';

-- 表统计历史记录表
CREATE TABLE IF NOT EXISTS `table_statistics_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `table_id` BIGINT NOT NULL COMMENT '关联的表ID',
    `cluster_id` BIGINT DEFAULT NULL COMMENT 'Doris集群ID',
    `database_name` VARCHAR(100) NOT NULL COMMENT '数据库名',
    `table_name` VARCHAR(100) NOT NULL COMMENT '表名',
    `row_count` BIGINT DEFAULT 0 COMMENT '数据行数',
    `data_size` BIGINT DEFAULT 0 COMMENT '数据大小(字节)',
    `partition_count` INT DEFAULT 0 COMMENT '分区数量',
    `replication_num` INT DEFAULT NULL COMMENT '副本数量',
    `bucket_num` INT DEFAULT NULL COMMENT '分桶数量',
    `table_last_update_time` DATETIME DEFAULT NULL COMMENT '表最后更新时间(来自Doris)',
    `statistics_time` DATETIME NOT NULL COMMENT '统计时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_table_id` (`table_id`),
    KEY `idx_statistics_time` (`statistics_time`),
    KEY `idx_table_stats` (`table_id`, `statistics_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表统计历史记录';


-- Inspection Schema (from 20-inspection-schema.sql)

-- 巡检记录表
CREATE TABLE IF NOT EXISTS inspection_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    inspection_type VARCHAR(50) NOT NULL COMMENT '巡检类型: table_naming, replica_count, tablet_count, task_failure, best_practice',
    inspection_time DATETIME NOT NULL COMMENT '巡检时间',
    trigger_type VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '触发类型: manual, schedule',
    total_items INT DEFAULT 0 COMMENT '检查项总数',
    issue_count INT DEFAULT 0 COMMENT '问题数量',
    status VARCHAR(20) DEFAULT 'running' COMMENT '状态: running, completed, failed',
    duration_seconds INT COMMENT '执行时长(秒)',
    created_by VARCHAR(50) COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_inspection_time (inspection_time),
    INDEX idx_inspection_type (inspection_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检记录表';

-- 巡检问题表
CREATE TABLE IF NOT EXISTS inspection_issue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    record_id BIGINT NOT NULL COMMENT '巡检记录ID',
    issue_type VARCHAR(50) NOT NULL COMMENT '问题类型',
    severity VARCHAR(20) NOT NULL COMMENT '严重程度: critical, high, medium, low',
    resource_type VARCHAR(50) NOT NULL COMMENT '资源类型: table, task',
    resource_id BIGINT COMMENT '资源ID',
    resource_name VARCHAR(200) COMMENT '资源名称',
    issue_description TEXT COMMENT '问题描述',
    current_value VARCHAR(500) COMMENT '当前值',
    expected_value VARCHAR(500) COMMENT '期望值',
    suggestion TEXT COMMENT '建议',
    status VARCHAR(20) DEFAULT 'open' COMMENT '状态: open, acknowledged, resolved, ignored',
    resolved_by VARCHAR(50) COMMENT '解决人',
    resolved_time DATETIME COMMENT '解决时间',
    resolution_note TEXT COMMENT '解决说明',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_record_id (record_id),
    INDEX idx_issue_type (issue_type),
    INDEX idx_severity (severity),
    INDEX idx_status (status),
    INDEX idx_resource (resource_type, resource_id),
    FOREIGN KEY (record_id) REFERENCES inspection_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检问题表';

-- 巡检规则配置表
CREATE TABLE IF NOT EXISTS inspection_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    rule_code VARCHAR(50) NOT NULL UNIQUE COMMENT '规则代码',
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(50) NOT NULL COMMENT '规则类型',
    severity VARCHAR(20) NOT NULL COMMENT '严重程度',
    description TEXT COMMENT '规则描述',
    rule_config JSON COMMENT '规则配置(JSON格式)',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_by VARCHAR(50) COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_rule_type (rule_type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='巡检规则配置表';

-- 插入默认巡检规则
INSERT INTO inspection_rule (rule_code, rule_name, rule_type, severity, description, rule_config, enabled) VALUES
('TABLE_NAMING_CONVENTION', '表命名规范检查', 'table_naming', 'medium', '检查表名是否符合命名规范',
 '{"pattern": "^(ods|dwd|dim|dws|ads)_[a-z][a-z0-9_]*$", "errorMessage": "表名应遵循 {layer}_xxx_xxx 格式"}', 1),
('REPLICA_COUNT_CHECK', '副本数检查', 'replica_count', 'high', '检查表的副本数是否符合最佳实践',
 '{"minReplicas": 1, "maxReplicas": 3, "recommendedReplicas": 3}', 1),
('TABLET_COUNT_CHECK', 'Tablet数量检查', 'tablet_count', 'high', '检查表的tablet数量是否过多',
 '{"maxTablets": 200, "warningTablets": 100}', 1),
('TABLE_OWNER_CHECK', '表负责人检查', 'table_owner', 'medium', '检查表是否配置了负责人',
 '{}', 1),
('TABLE_COMMENT_CHECK', '表注释检查', 'table_comment', 'low', '检查表是否有注释说明',
 '{}', 1),
('TASK_FAILURE_CHECK', '任务失败检查', 'task_failure', 'critical', '检查最近失败的任务',
 '{"checkDays": 1, "maxFailures": 3}', 1),
('TASK_SCHEDULE_CHECK', '任务调度检查', 'task_schedule', 'medium', '检查已发布但长期未执行的任务',
 '{"checkDays": 7}', 1),
('TABLE_LAYER_CHECK', '数据层级检查', 'table_layer', 'medium', '检查表的数据层级是否正确配置',
 '{"validLayers": ["ODS", "DWD", "DIM", "DWS", "ADS"]}', 1)
ON DUPLICATE KEY UPDATE rule_name = VALUES(rule_name), rule_type = VALUES(rule_type),
    severity = VALUES(severity), description = VALUES(description), rule_config = VALUES(rule_config);

-- Workflow Schema (New Entities)

-- 平台侧工作流定义
CREATE TABLE IF NOT EXISTS `data_workflow` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `workflow_code` BIGINT DEFAULT NULL COMMENT '工作流编码',
    `project_code` BIGINT DEFAULT NULL COMMENT '项目编码',
    `workflow_name` VARCHAR(100) DEFAULT NULL COMMENT '工作流名称',
    `status` VARCHAR(20) DEFAULT NULL COMMENT '状态',
    `publish_status` VARCHAR(20) DEFAULT NULL COMMENT '发布状态',
    `current_version_id` BIGINT DEFAULT NULL COMMENT '当前版本ID',
    `last_published_version_id` BIGINT DEFAULT NULL COMMENT '最后发布版本ID',
    `definition_json` TEXT DEFAULT NULL COMMENT '定义JSON',
    `entry_task_ids` TEXT DEFAULT NULL COMMENT '入口任务ID列表',
    `exit_task_ids` TEXT DEFAULT NULL COMMENT '出口任务ID列表',
    `description` TEXT DEFAULT NULL COMMENT '描述',
    `created_by` VARCHAR(50) DEFAULT NULL COMMENT '创建人',
    `updated_by` VARCHAR(50) DEFAULT NULL COMMENT '更新人',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台侧工作流定义';

-- DolphinScheduler工作流配置
CREATE TABLE IF NOT EXISTS `dolphin_workflow_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `workflow_code` BIGINT DEFAULT NULL COMMENT '工作流编码',
    `workflow_name` VARCHAR(100) DEFAULT NULL COMMENT '工作流名称',
    `project_code` BIGINT DEFAULT NULL COMMENT '项目编码',
    `description` TEXT DEFAULT NULL COMMENT '描述',
    `is_default` TINYINT(1) DEFAULT 0 COMMENT '是否默认工作流',
    `release_state` VARCHAR(20) DEFAULT NULL COMMENT '发布状态',
    `task_count` INT DEFAULT 0 COMMENT '任务数量',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DolphinScheduler工作流配置';

-- 最近执行历史缓存
CREATE TABLE IF NOT EXISTS `workflow_instance_cache` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT DEFAULT NULL,
    `instance_id` BIGINT DEFAULT NULL,
    `state` VARCHAR(50) DEFAULT NULL,
    `start_time` DATETIME DEFAULT NULL,
    `end_time` DATETIME DEFAULT NULL,
    `trigger_type` VARCHAR(50) DEFAULT NULL,
    `duration_ms` BIGINT DEFAULT NULL,
    `extra` TEXT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='最近执行历史缓存';

-- 工作流发布记录
CREATE TABLE IF NOT EXISTS `workflow_publish_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT DEFAULT NULL,
    `version_id` BIGINT DEFAULT NULL,
    `target_engine` VARCHAR(50) DEFAULT NULL,
    `operation` VARCHAR(50) DEFAULT NULL,
    `status` VARCHAR(50) DEFAULT NULL,
    `engine_workflow_code` BIGINT DEFAULT NULL,
    `log` TEXT DEFAULT NULL,
    `operator` VARCHAR(50) DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流发布记录';

-- 任务与工作流关联
CREATE TABLE IF NOT EXISTS `workflow_task_relation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT DEFAULT NULL,
    `task_id` BIGINT DEFAULT NULL,
    `node_attrs` TEXT DEFAULT NULL,
    `is_entry` TINYINT(1) DEFAULT NULL,
    `is_exit` TINYINT(1) DEFAULT NULL,
    `version_id` BIGINT DEFAULT NULL,
    `upstream_task_count` INT DEFAULT NULL,
    `downstream_task_count` INT DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='任务与工作流关联';

-- 工作流版本快照
CREATE TABLE IF NOT EXISTS `workflow_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workflow_id` BIGINT DEFAULT NULL,
    `version_no` INT DEFAULT NULL,
    `structure_snapshot` TEXT DEFAULT NULL,
    `change_summary` TEXT DEFAULT NULL,
    `trigger_source` VARCHAR(50) DEFAULT NULL,
    `created_by` VARCHAR(50) DEFAULT NULL,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流版本快照';
