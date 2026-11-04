SET NAMES utf8mb4;

-- 清理历史版本遗留的示例数据（V2__sample_data.sql）
-- 说明：
-- 1) 仅清理明确的 sample 任务/日志/血缘/关联关系，避免误删业务数据
-- 2) 清理默认插入的本地开发 Doris 集群（localhost:9030, 本地开发集群）

-- 1) 清理示例任务相关数据
DELETE FROM task_execution_log
WHERE execution_id LIKE 'sample-%'
   OR task_id IN (
        SELECT id FROM data_task
        WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
    );

DELETE FROM data_lineage
WHERE task_id IN (
    SELECT id FROM data_task
    WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
);

DELETE FROM table_task_relation
WHERE task_id IN (
    SELECT id FROM data_task
    WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
);

DELETE FROM workflow_task_relation
WHERE task_id IN (
    SELECT id FROM data_task
    WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
);

DELETE FROM data_task
WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync');

-- 2) 清理示例 Doris 集群（避免生产环境误指向 localhost 并被当作默认数据源）
DELETE FROM user_database_permissions
WHERE cluster_id IN (
    SELECT id FROM doris_cluster
    WHERE fe_host = 'localhost' AND fe_port = 9030 AND cluster_name = '本地开发集群'
);

DELETE FROM doris_database_users
WHERE cluster_id IN (
    SELECT id FROM doris_cluster
    WHERE fe_host = 'localhost' AND fe_port = 9030 AND cluster_name = '本地开发集群'
);

DELETE FROM doris_cluster
WHERE fe_host = 'localhost' AND fe_port = 9030 AND cluster_name = '本地开发集群';

