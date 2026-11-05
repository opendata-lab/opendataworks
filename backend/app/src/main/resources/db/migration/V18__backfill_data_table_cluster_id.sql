SET NAMES utf8mb4;

-- Prefer default cluster when present
UPDATE `data_table` dt
JOIN (
    SELECT `id`
    FROM `doris_cluster`
    WHERE `is_default` = 1 AND `deleted` = 0 AND (`status` = 'active' OR `status` IS NULL)
    ORDER BY `id`
    LIMIT 1
) dc ON 1 = 1
SET dt.`cluster_id` = dc.`id`
WHERE dt.`cluster_id` IS NULL;

-- Fallback to first active cluster if no default
UPDATE `data_table` dt
JOIN (
    SELECT `id`
    FROM `doris_cluster`
    WHERE `deleted` = 0 AND (`status` = 'active' OR `status` IS NULL)
    ORDER BY `id`
    LIMIT 1
) dc ON 1 = 1
SET dt.`cluster_id` = dc.`id`
WHERE dt.`cluster_id` IS NULL;
