SET NAMES utf8mb4;

-- 确保业务域与数据域基础数据存在且编码正确
INSERT INTO business_domain (domain_code, domain_name, description) VALUES
('tech', '技术域', '技术相关的业务域,包括研发、运维等')
ON DUPLICATE KEY UPDATE
  domain_name = VALUES(domain_name),
  description = VALUES(description);

INSERT INTO data_domain (domain_code, domain_name, business_domain, description) VALUES
('dev', '研发域', 'tech', '研发相关数据,包括代码、项目、需求等'),
('ops', '运维域', 'tech', '运维相关数据,包括监控、告警、性能等'),
('public', '公共域', 'tech', '公共数据,如字典、配置等')
ON DUPLICATE KEY UPDATE
  domain_name = VALUES(domain_name),
  business_domain = VALUES(business_domain),
  description = VALUES(description);

-- 确保 Doris 集群配置中文名称标准
UPDATE doris_cluster
SET cluster_name = '本地开发集群',
    username = 'root',
    password = '',
    is_default = 1,
    status = 'active'
WHERE fe_host = 'localhost' AND fe_port = 9030;

INSERT INTO doris_cluster (cluster_name, fe_host, fe_port, username, password, is_default, status)
SELECT '本地开发集群', 'localhost', 9030, 'root', '', 1, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM doris_cluster WHERE fe_host = 'localhost' AND fe_port = 9030
);

-- 清理已有的示例字段定义，保持脚本可重复执行
DELETE FROM data_field
WHERE table_id IN (
    SELECT id FROM data_table
    WHERE table_name IN ('ods_user', 'ods_order', 'dwd_user', 'dwd_order', 'dws_user_daily')
);

-- 为现有示例表插入字段定义
INSERT INTO data_field (
    table_id,
    field_name,
    field_type,
    field_comment,
    is_nullable,
    is_partition,
    is_primary,
    default_value,
    field_order
)
SELECT dt.id, 'user_id', 'BIGINT', '用户ID', 0, 0, 1, NULL, 1
FROM data_table dt WHERE dt.table_name = 'ods_user'
UNION ALL
SELECT dt.id, 'user_name', 'VARCHAR(50)', '用户名', 1, 0, 0, NULL, 2
FROM data_table dt WHERE dt.table_name = 'ods_user'
UNION ALL
SELECT dt.id, 'phone', 'VARCHAR(20)', '手机号', 1, 0, 0, NULL, 3
FROM data_table dt WHERE dt.table_name = 'ods_user'
UNION ALL
SELECT dt.id, 'created_time', 'DATETIME', '创建时间', 0, 1, 0, NULL, 4
FROM data_table dt WHERE dt.table_name = 'ods_user'
UNION ALL
SELECT dt.id, 'order_id', 'BIGINT', '订单ID', 0, 0, 1, NULL, 1
FROM data_table dt WHERE dt.table_name = 'ods_order'
UNION ALL
SELECT dt.id, 'user_id', 'BIGINT', '用户ID', 0, 0, 0, NULL, 2
FROM data_table dt WHERE dt.table_name = 'ods_order'
UNION ALL
SELECT dt.id, 'order_amount', 'DECIMAL(18,2)', '订单金额', 0, 0, 0, '0.00', 3
FROM data_table dt WHERE dt.table_name = 'ods_order'
UNION ALL
SELECT dt.id, 'order_time', 'DATETIME', '下单时间', 0, 1, 0, NULL, 4
FROM data_table dt WHERE dt.table_name = 'ods_order'
UNION ALL
SELECT dt.id, 'user_id', 'BIGINT', '用户ID', 0, 0, 1, NULL, 1
FROM data_table dt WHERE dt.table_name = 'dwd_user'
UNION ALL
SELECT dt.id, 'user_name', 'VARCHAR(50)', '用户名', 1, 0, 0, NULL, 2
FROM data_table dt WHERE dt.table_name = 'dwd_user'
UNION ALL
SELECT dt.id, 'phone', 'VARCHAR(20)', '手机号', 1, 0, 0, NULL, 3
FROM data_table dt WHERE dt.table_name = 'dwd_user'
UNION ALL
SELECT dt.id, 'is_vip', 'TINYINT', '是否会员', 0, 0, 0, '0', 4
FROM data_table dt WHERE dt.table_name = 'dwd_user'
UNION ALL
SELECT dt.id, 'order_id', 'BIGINT', '订单ID', 0, 0, 1, NULL, 1
FROM data_table dt WHERE dt.table_name = 'dwd_order'
UNION ALL
SELECT dt.id, 'user_id', 'BIGINT', '用户ID', 0, 0, 0, NULL, 2
FROM data_table dt WHERE dt.table_name = 'dwd_order'
UNION ALL
SELECT dt.id, 'order_amount', 'DECIMAL(18,2)', '订单金额', 0, 0, 0, '0.00', 3
FROM data_table dt WHERE dt.table_name = 'dwd_order'
UNION ALL
SELECT dt.id, 'order_dt', 'DATE', '订单日期', 0, 1, 0, NULL, 4
FROM data_table dt WHERE dt.table_name = 'dwd_order'
UNION ALL
SELECT dt.id, 'stat_date', 'DATE', '统计日期', 0, 1, 1, NULL, 1
FROM data_table dt WHERE dt.table_name = 'dws_user_daily'
UNION ALL
SELECT dt.id, 'active_users', 'BIGINT', '活跃用户数', 0, 0, 0, '0', 2
FROM data_table dt WHERE dt.table_name = 'dws_user_daily'
UNION ALL
SELECT dt.id, 'new_users', 'BIGINT', '新增用户数', 0, 0, 0, '0', 3
FROM data_table dt WHERE dt.table_name = 'dws_user_daily'
UNION ALL
SELECT dt.id, 'gmv', 'DECIMAL(18,2)', 'GMV(元)', 0, 0, 0, '0.00', 4
FROM data_table dt WHERE dt.table_name = 'dws_user_daily';

-- 更新示例表的业务域、数据域及 Doris 相关配置
UPDATE data_table
SET business_domain = 'tech',
    data_domain = CASE table_name
        WHEN 'ods_user' THEN 'public'
        WHEN 'ods_order' THEN 'public'
        WHEN 'dwd_user' THEN 'dev'
        WHEN 'dwd_order' THEN 'ops'
        WHEN 'dws_user_daily' THEN 'ops'
        ELSE NULL
    END,
    custom_identifier = CONCAT(table_name, '_tbl'),
    statistics_cycle = CASE layer
        WHEN 'ODS' THEN '5m'
        WHEN 'DWD' THEN '30m'
        WHEN 'DWS' THEN '1d'
        ELSE NULL
    END,
    update_type = CASE layer
        WHEN 'ODS' THEN 'di'
        WHEN 'DWD' THEN 'df'
        WHEN 'DWS' THEN 'hi'
        ELSE NULL
    END,
    table_model = CASE layer
        WHEN 'ODS' THEN 'DUPLICATE'
        WHEN 'DWD' THEN 'UNIQUE'
        WHEN 'DWS' THEN 'AGGREGATE'
        ELSE NULL
    END,
    bucket_num = 10,
    replica_num = 1,
    partition_field = CASE layer
        WHEN 'ODS' THEN 'created_time'
        WHEN 'DWD' THEN 'order_dt'
        WHEN 'DWS' THEN 'stat_date'
        ELSE NULL
    END,
    distribution_column = 'user_id',
    key_columns = CASE layer
        WHEN 'ODS' THEN 'user_id'
        WHEN 'DWD' THEN 'user_id'
        WHEN 'DWS' THEN 'stat_date'
        ELSE NULL
    END,
    doris_ddl = CONCAT('CREATE TABLE IF NOT EXISTS ', table_name, ' (...)')
WHERE table_name IN ('ods_user', 'ods_order', 'dwd_user', 'dwd_order', 'dws_user_daily');

-- 清理并插入示例任务定义
DELETE FROM task_execution_log WHERE execution_id LIKE 'sample-%';
DELETE FROM data_lineage
WHERE task_id IN (
    SELECT id FROM data_task
    WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
);
DELETE FROM data_task
WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync');

INSERT INTO data_task (
    task_name,
    task_code,
    task_type,
    engine,
    task_sql,
    task_desc,
    schedule_cron,
    priority,
    timeout_seconds,
    retry_times,
    retry_interval,
    owner,
    status
)
VALUES
('用户日汇总离线任务',
 'sample_batch_user_daily',
 'batch',
 'dolphin',
 'INSERT INTO dws_user_daily (stat_date, active_users, new_users, gmv)\nSELECT DATE(order_time) AS stat_date,\n       COUNT(DISTINCT user_id) AS active_users,\n       SUM(CASE WHEN order_amount > 0 THEN 1 ELSE 0 END) AS new_users,\n       SUM(order_amount) AS gmv\nFROM dwd_order\nWHERE order_time >= DATE_SUB(NOW(), INTERVAL 1 DAY)\nGROUP BY DATE(order_time);',
 '每日汇总订单指标并写入 DWS 层表。',
 '0 0 2 * * ?',
 5,
 7200,
 1,
 300,
 'admin',
 'published'),
('用户实时标签同步任务',
 'sample_stream_user_sync',
 'stream',
 'dinky',
 'INSERT INTO dwd_user (user_id, user_name, phone, is_vip)\nSELECT user_id, user_name, phone, is_vip\nFROM ods_user\nWHERE created_time >= DATE_SUB(NOW(), INTERVAL 5 MINUTE);',
 '实时同步 ODS 用户数据至 DWD 层。',
 '0 */5 * * * ?',
 7,
 1800,
 3,
 120,
 'stream_owner',
 'running');

-- 插入示例血缘数据
INSERT INTO data_lineage (
    task_id,
    upstream_table_id,
    downstream_table_id,
    lineage_type
)
SELECT t.id,
       src.id,
       dst.id,
       'input'
FROM data_task t
JOIN data_table dst ON dst.table_name = 'dws_user_daily'
JOIN data_table src ON src.table_name = 'dwd_order'
WHERE t.task_code = 'sample_batch_user_daily'
UNION ALL
SELECT t.id,
       src.id,
       dst.id,
       'input'
FROM data_task t
JOIN data_table dst ON dst.table_name = 'dws_user_daily'
JOIN data_table src ON src.table_name = 'dwd_user'
WHERE t.task_code = 'sample_batch_user_daily'
UNION ALL
SELECT t.id,
       NULL,
       dst.id,
       'output'
FROM data_task t
JOIN data_table dst ON dst.table_name = 'dws_user_daily'
WHERE t.task_code = 'sample_batch_user_daily'
UNION ALL
SELECT t.id,
       src.id,
       dst.id,
       'input'
FROM data_task t
JOIN data_table src ON src.table_name = 'ods_user'
JOIN data_table dst ON dst.table_name = 'dwd_user'
WHERE t.task_code = 'sample_stream_user_sync'
UNION ALL
SELECT t.id,
       NULL,
       dst.id,
       'output'
FROM data_task t
JOIN data_table dst ON dst.table_name = 'dwd_user'
WHERE t.task_code = 'sample_stream_user_sync';

-- 插入示例任务执行日志
INSERT INTO task_execution_log (
    task_id,
    execution_id,
    status,
    start_time,
    end_time,
    duration_seconds,
    rows_output,
    error_message,
    log_url,
    trigger_type
)
SELECT t.id,
       CONCAT('sample-', t.task_code, '-run-1'),
       'success',
       DATE_ADD(DATE_SUB(NOW(), INTERVAL 2 DAY), INTERVAL 1 HOUR),
       DATE_ADD(DATE_ADD(DATE_SUB(NOW(), INTERVAL 2 DAY), INTERVAL 1 HOUR), INTERVAL 3 MINUTE),
       180,
       4850,
       NULL,
       CONCAT('http://dolphinscheduler.example/logs/', t.task_code, '/run-1'),
       'schedule'
FROM data_task t
WHERE t.task_code = 'sample_batch_user_daily'
UNION ALL
SELECT t.id,
       CONCAT('sample-', t.task_code, '-run-2'),
       'failed',
       DATE_ADD(DATE_SUB(NOW(), INTERVAL 1 DAY), INTERVAL 2 HOUR),
       DATE_ADD(DATE_ADD(DATE_SUB(NOW(), INTERVAL 1 DAY), INTERVAL 2 HOUR), INTERVAL 5 MINUTE),
       300,
       0,
       '离线任务执行超时，已自动重试但仍失败。',
       CONCAT('http://dolphinscheduler.example/logs/', t.task_code, '/run-2'),
       'schedule'
FROM data_task t
WHERE t.task_code = 'sample_batch_user_daily'
UNION ALL
SELECT t.id,
       CONCAT('sample-', t.task_code, '-run-1'),
       'running',
       NOW() - INTERVAL 10 MINUTE,
       NULL,
       NULL,
       320,
       NULL,
       CONCAT('http://dinky.example/logs/', t.task_code, '/run-1'),
       'api'
FROM data_task t
WHERE t.task_code = 'sample_stream_user_sync'
UNION ALL
SELECT t.id,
       CONCAT('sample-', t.task_code, '-run-0'),
       'success',
       DATE_SUB(NOW(), INTERVAL 1 DAY),
       DATE_ADD(DATE_SUB(NOW(), INTERVAL 1 DAY), INTERVAL 4 MINUTE),
       240,
       2800,
       NULL,
       CONCAT('http://dinky.example/logs/', t.task_code, '/run-0'),
       'manual'
FROM data_task t
WHERE t.task_code = 'sample_stream_user_sync';

-- 维护任务与表之间的读写关系
DELETE FROM table_task_relation
WHERE task_id IN (
    SELECT id FROM data_task WHERE task_code IN ('sample_batch_user_daily', 'sample_stream_user_sync')
)
AND table_id IN (
    SELECT id FROM data_table WHERE table_name IN ('ods_user', 'dwd_user', 'dwd_order', 'dws_user_daily')
);

INSERT INTO table_task_relation (table_id, task_id, relation_type)
SELECT src.id, t.id, 'read'
FROM data_table src
JOIN data_task t ON t.task_code = 'sample_batch_user_daily'
WHERE src.table_name IN ('dwd_order', 'dwd_user')
UNION ALL
SELECT dst.id, t.id, 'write'
FROM data_table dst
JOIN data_task t ON t.task_code = 'sample_batch_user_daily'
WHERE dst.table_name = 'dws_user_daily'
UNION ALL
SELECT src.id, t.id, 'read'
FROM data_table src
JOIN data_task t ON t.task_code = 'sample_stream_user_sync'
WHERE src.table_name = 'ods_user'
UNION ALL
SELECT dst.id, t.id, 'write'
FROM data_table dst
JOIN data_task t ON t.task_code = 'sample_stream_user_sync'
WHERE dst.table_name = 'dwd_user';
