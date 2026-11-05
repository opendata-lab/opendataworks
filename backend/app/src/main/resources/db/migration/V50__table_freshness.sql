-- 数据新鲜度（Freshness）检查：表级契约 + 检查结果留痕。
-- 语义对齐 dbt source freshness：由用户显式指定时间字段与时效阈值，
-- 未配置契约的表不参与检查、不产出新鲜度结论。

-- 表级新鲜度契约
CREATE TABLE IF NOT EXISTS `table_freshness_config` (
    `id`                 BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    `table_id`           BIGINT       NOT NULL COMMENT '数据表ID',
    `mode`               VARCHAR(16)  NOT NULL COMMENT '取值模式: column | custom_sql | partition | metadata',
    `loaded_at_field`    VARCHAR(128) DEFAULT NULL COMMENT 'column 模式的加载时间列名',
    `loaded_at_query`    VARCHAR(2048) DEFAULT NULL COMMENT 'custom_sql 模式的自定义查询，与 loaded_at_field 互斥',
    `partition_format`   VARCHAR(32)  DEFAULT NULL COMMENT 'partition 模式的分区值日期格式，如 yyyyMMdd',
    `filter_expr`        VARCHAR(512) DEFAULT NULL COMMENT '可选 WHERE 谓词过滤',
    `warn_after_count`   INT          DEFAULT NULL COMMENT 'warn 阈值数量',
    `warn_after_period`  VARCHAR(16)  DEFAULT NULL COMMENT 'warn 阈值单位: minute | hour | day',
    `error_after_count`  INT          DEFAULT NULL COMMENT 'error 阈值数量',
    `error_after_period` VARCHAR(16)  DEFAULT NULL COMMENT 'error 阈值单位: minute | hour | day',
    `enabled`            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用，0 表示显式关闭该表新鲜度检查',
    `created_by`         VARCHAR(50)  DEFAULT NULL COMMENT '创建人',
    `updated_by`         VARCHAR(50)  DEFAULT NULL COMMENT '更新人',
    `created_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_table` (`table_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表级数据新鲜度契约';

-- 新鲜度检查结果（pass 也留痕）
CREATE TABLE IF NOT EXISTS `table_freshness_result` (
    `id`                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
    `table_id`            BIGINT       NOT NULL COMMENT '数据表ID',
    `cluster_id`          BIGINT       DEFAULT NULL COMMENT '数据源(集群)ID',
    `db_name`             VARCHAR(128) DEFAULT NULL COMMENT 'Schema/数据库名',
    `table_name`          VARCHAR(128) DEFAULT NULL COMMENT '表名',
    `mode`                VARCHAR(16)  NOT NULL COMMENT '本次检查使用的取值模式',
    `status`              VARCHAR(16)  NOT NULL COMMENT 'pass | warn | error | runtime_error',
    `reason`              VARCHAR(32)  DEFAULT NULL COMMENT '细分原因，如 never_loaded',
    `max_loaded_at`       DATETIME     DEFAULT NULL COMMENT '数据最后加载时间',
    `snapshotted_at`      DATETIME     DEFAULT NULL COMMENT '检查快照时间',
    `age_seconds`         BIGINT       DEFAULT NULL COMMENT '数据年龄(秒)',
    `warn_after_seconds`  BIGINT       DEFAULT NULL COMMENT 'warn 阈值(秒)',
    `error_after_seconds` BIGINT       DEFAULT NULL COMMENT 'error 阈值(秒)',
    `error_message`        VARCHAR(512) DEFAULT NULL COMMENT 'runtime_error 错误信息',
    `trigger_type`         VARCHAR(16)  DEFAULT NULL COMMENT 'manual | schedule | inspection | workflow',
    `workflow_instance_id` BIGINT       DEFAULT NULL COMMENT '触发本次检查的工作流实例ID（Dolphin 实例），用于按「每次运行」聚合并反查执行',
    `checked_by`           VARCHAR(50)  DEFAULT NULL COMMENT '触发人',
    `created_at`           DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY `idx_table_time` (`table_id`, `created_at`),
    KEY `idx_status` (`status`),
    KEY `idx_workflow_instance` (`workflow_instance_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据新鲜度检查结果';

-- data_table 冗余最新态，供列表/巡检按状态过滤
ALTER TABLE `data_table`
    ADD COLUMN `freshness_status` VARCHAR(16) DEFAULT NULL COMMENT '最新新鲜度状态';
ALTER TABLE `data_table`
    ADD COLUMN `freshness_checked_at` DATETIME DEFAULT NULL COMMENT '最近新鲜度检查时间';

-- 新鲜度是独立于巡检的事件驱动子系统：阈值由表级契约声明，检查在工作流完成后触发，
-- 结果落 table_freshness_result / data_table.freshness_status，不产生 inspection_issue、不种子巡检规则。
