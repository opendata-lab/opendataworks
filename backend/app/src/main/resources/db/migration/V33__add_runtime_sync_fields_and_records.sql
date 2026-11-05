-- Workflow runtime reverse-sync metadata
ALTER TABLE `data_workflow`
    ADD COLUMN `sync_source` VARCHAR(20) DEFAULT NULL COMMENT '同步来源: manual/runtime',
    ADD COLUMN `runtime_sync_hash` VARCHAR(64) DEFAULT NULL COMMENT '最近运行态同步快照哈希',
    ADD COLUMN `runtime_sync_status` VARCHAR(20) DEFAULT NULL COMMENT '最近运行态同步状态: success/failed',
    ADD COLUMN `runtime_sync_message` VARCHAR(1000) DEFAULT NULL COMMENT '最近运行态同步信息',
    ADD COLUMN `runtime_sync_at` DATETIME DEFAULT NULL COMMENT '最近运行态同步时间';

CREATE TABLE IF NOT EXISTS `workflow_runtime_sync_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `workflow_id` BIGINT DEFAULT NULL COMMENT '本地工作流ID',
    `project_code` BIGINT DEFAULT NULL COMMENT 'Dolphin项目编码',
    `workflow_code` BIGINT DEFAULT NULL COMMENT 'Dolphin工作流编码',
    `snapshot_hash` VARCHAR(64) DEFAULT NULL COMMENT '快照哈希',
    `snapshot_json` LONGTEXT DEFAULT NULL COMMENT '运行态规范化快照JSON',
    `diff_json` LONGTEXT DEFAULT NULL COMMENT '差异结果JSON',
    `status` VARCHAR(20) DEFAULT NULL COMMENT '状态: success/failed',
    `error_code` VARCHAR(64) DEFAULT NULL COMMENT '失败码',
    `error_message` VARCHAR(2000) DEFAULT NULL COMMENT '失败原因',
    `operator` VARCHAR(100) DEFAULT NULL COMMENT '操作人',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_sync_record_workflow_created` (`workflow_id`, `created_at`),
    KEY `idx_sync_record_project_workflow_created` (`project_code`, `workflow_code`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行态反向同步记录';

CREATE INDEX `idx_data_task_dolphin_code_engine_deleted`
    ON `data_task` (`dolphin_task_code`, `engine`, `deleted`);
