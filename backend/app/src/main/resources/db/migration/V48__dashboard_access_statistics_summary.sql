CREATE TABLE IF NOT EXISTS `doris_audit_access_checkpoint` (
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris 集群ID',
    `audit_source` VARCHAR(255) DEFAULT NULL COMMENT '审计表全限定名',
    `watermark_time` DATETIME(3) DEFAULT NULL COMMENT '已处理到的审计时间',
    `watermark_event_key` VARCHAR(128) DEFAULT NULL COMMENT '同一时间点内的事件游标',
    `coverage_start` DATETIME(3) DEFAULT NULL COMMENT '可信历史覆盖起点',
    `sync_status` VARCHAR(20) NOT NULL DEFAULT 'BACKFILLING' COMMENT 'BACKFILLING/READY/DEGRADED/UNAVAILABLE',
    `last_synced_at` DATETIME(3) DEFAULT NULL COMMENT '最近成功同步时间',
    `last_error` VARCHAR(1000) DEFAULT NULL COMMENT '最近同步错误',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`cluster_id`),
    KEY `idx_audit_checkpoint_status` (`sync_status`, `last_synced_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doris审计访问统计同步游标';

CREATE TABLE IF NOT EXISTS `doris_audit_processed_event` (
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris 集群ID',
    `event_key` VARCHAR(128) NOT NULL COMMENT 'QueryId或稳定事件摘要',
    `event_time` DATETIME(3) NOT NULL COMMENT '审计事件时间',
    `processed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`cluster_id`, `event_key`),
    KEY `idx_audit_processed_event_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Doris审计访问事件短期去重';

CREATE TABLE IF NOT EXISTS `table_access_daily` (
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris 集群ID',
    `access_date` DATE NOT NULL COMMENT '访问日期',
    `db_name` VARCHAR(128) NOT NULL COMMENT '数据库名（小写规范化）',
    `table_name` VARCHAR(128) NOT NULL COMMENT '表名（小写规范化）',
    `total_access_count` BIGINT NOT NULL DEFAULT 0 COMMENT '总访问次数',
    `read_access_count` BIGINT NOT NULL DEFAULT 0 COMMENT '读取次数',
    `write_access_count` BIGINT NOT NULL DEFAULT 0 COMMENT '写入次数',
    `duration_sum_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '执行耗时合计',
    `duration_sample_count` BIGINT NOT NULL DEFAULT 0 COMMENT '有效耗时样本数',
    `first_access_time` DATETIME(3) DEFAULT NULL COMMENT '当日首次访问时间',
    `last_access_time` DATETIME(3) DEFAULT NULL COMMENT '当日最后访问时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`cluster_id`, `access_date`, `db_name`, `table_name`),
    KEY `idx_table_access_lookup` (`cluster_id`, `db_name`, `table_name`, `access_date`),
    KEY `idx_table_access_retention` (`access_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表每日访问汇总';

CREATE TABLE IF NOT EXISTS `table_access_user_daily` (
    `cluster_id` BIGINT NOT NULL COMMENT 'Doris 集群ID',
    `access_date` DATE NOT NULL COMMENT '访问日期',
    `db_name` VARCHAR(128) NOT NULL COMMENT '数据库名（小写规范化）',
    `table_name` VARCHAR(128) NOT NULL COMMENT '表名（小写规范化）',
    `user_name` VARCHAR(128) NOT NULL COMMENT '访问用户',
    `access_count` BIGINT NOT NULL DEFAULT 0 COMMENT '访问次数',
    `last_access_time` DATETIME(3) DEFAULT NULL COMMENT '当日最后访问时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`cluster_id`, `access_date`, `db_name`, `table_name`, `user_name`),
    KEY `idx_table_access_user_lookup` (`cluster_id`, `db_name`, `table_name`, `access_date`),
    KEY `idx_table_access_user_retention` (`access_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据表用户每日访问汇总';
