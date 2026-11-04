UPDATE `data_table`
SET
    `deprecated_at` = COALESCE(`deprecated_at`, `updated_at`, NOW()),
    `purge_at` = COALESCE(`purge_at`, DATE_ADD(COALESCE(`updated_at`, NOW()), INTERVAL 30 DAY)),
    `origin_table_name` = CASE
        WHEN (`origin_table_name` IS NULL OR `origin_table_name` = '')
             AND `table_name` REGEXP '_deprecated_[0-9]{14}$'
            THEN SUBSTRING_INDEX(`table_name`, '_deprecated_', 1)
        ELSE `origin_table_name`
    END
WHERE `status` = 'deprecated';
