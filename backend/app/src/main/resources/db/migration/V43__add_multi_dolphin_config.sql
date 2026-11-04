ALTER TABLE `dolphin_config`
    ADD COLUMN `config_name` VARCHAR(100) DEFAULT NULL COMMENT 'Dolphin 环境名称' AFTER `id`,
    ADD COLUMN `description` TEXT DEFAULT NULL COMMENT '环境说明' AFTER `execution_type`,
    ADD COLUMN `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否默认环境' AFTER `is_active`,
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除标记' AFTER `updated_at`;

UPDATE `dolphin_config`
SET `config_name` = COALESCE(NULLIF(`config_name`, ''), CONCAT('Dolphin-', `id`)),
    `deleted` = 0
WHERE `config_name` IS NULL OR `config_name` = '' OR `deleted` IS NULL;

SET @default_dolphin_config_id := (
    SELECT `id`
    FROM (
        SELECT `id`
        FROM `dolphin_config`
        WHERE `deleted` = 0 AND `is_active` = 1
        ORDER BY `id` DESC
        LIMIT 1
    ) active_config
);

UPDATE `dolphin_config`
SET `is_default` = CASE WHEN `id` = @default_dolphin_config_id THEN 1 ELSE 0 END
WHERE `deleted` = 0;

ALTER TABLE `data_workflow`
    ADD COLUMN `dolphin_config_id` BIGINT DEFAULT NULL COMMENT '绑定的 Dolphin 环境ID' AFTER `id`;

ALTER TABLE `workflow_publish_record`
    ADD COLUMN `dolphin_config_id` BIGINT DEFAULT NULL COMMENT '发布目标 Dolphin 环境ID' AFTER `target_engine`;

UPDATE `data_workflow`
SET `dolphin_config_id` = @default_dolphin_config_id
WHERE `dolphin_config_id` IS NULL AND @default_dolphin_config_id IS NOT NULL;

UPDATE `workflow_publish_record`
SET `dolphin_config_id` = @default_dolphin_config_id
WHERE `dolphin_config_id` IS NULL
  AND (`target_engine` IS NULL OR LOWER(`target_engine`) = 'dolphin')
  AND @default_dolphin_config_id IS NOT NULL;

CREATE INDEX `idx_data_workflow_dolphin_config`
    ON `data_workflow` (`dolphin_config_id`, `workflow_code`);

CREATE INDEX `idx_publish_record_dolphin_config`
    ON `workflow_publish_record` (`dolphin_config_id`, `created_at`);
