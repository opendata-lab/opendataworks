-- 添加考虑逻辑删除的任务名称唯一约束
-- 此索引确保 task_name 在 deleted=0 时唯一，但允许 deleted=1 的重复名称

-- 检查并删除旧的唯一索引（兼容MySQL 5.7）
SET @exist = (SELECT COUNT(*) FROM information_schema.statistics
              WHERE table_schema = DATABASE()
              AND table_name = 'data_task'
              AND index_name = 'uk_task_name');
SET @sql = IF(@exist > 0,
              'ALTER TABLE `data_task` DROP INDEX `uk_task_name`',
              'SELECT "Index does not exist"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `data_task`
ADD UNIQUE KEY `uk_task_name` (`task_name`, `deleted`);

-- 注意：如果已存在重复的任务名称，此操作会失败
-- 需要先清理重复数据后再执行此脚本
-- 在执行前，请确保没有两个未删除的任务具有相同的名称
-- 可以使用以下查询检查重复项：
-- SELECT task_name, COUNT(*) FROM data_task WHERE deleted = 0 GROUP BY task_name HAVING COUNT(*) > 1;