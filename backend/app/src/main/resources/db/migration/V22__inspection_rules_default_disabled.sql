-- Set default enabled state to disabled for inspection rules
ALTER TABLE inspection_rule
    MODIFY COLUMN enabled TINYINT(1) DEFAULT 0 COMMENT '是否启用';

-- Disable built-in rules by default
UPDATE inspection_rule
SET enabled = 0
WHERE rule_code IN (
    'TABLE_NAMING_CONVENTION',
    'REPLICA_COUNT_CHECK',
    'TABLET_COUNT_CHECK',
    'TABLE_OWNER_CHECK',
    'TABLE_COMMENT_CHECK',
    'TASK_FAILURE_CHECK',
    'TASK_SCHEDULE_CHECK',
    'TABLE_LAYER_CHECK',
    'TABLET_SIZE_CHECK'
);

-- Update replica rule: require at least 3 replicas
UPDATE inspection_rule
SET rule_name = '扫描副本数检查',
    severity = 'high',
    description = '检查表的扫描副本数是否满足最佳实践(>=3)',
    rule_config = '{"minReplicas": 3, "recommendedReplicas": 3}'
WHERE rule_code = 'REPLICA_COUNT_CHECK';

-- Add tablet size rule (estimated by data size / partitions / buckets)
INSERT INTO inspection_rule (rule_code, rule_name, rule_type, severity, description, rule_config, enabled)
VALUES
('TABLET_SIZE_CHECK', 'Tablet大小检查', 'tablet_size', 'high', '检查平均Tablet大小是否合理(估算: dataSize/(partitionCount*bucketNum))',
 '{"minTabletSizeMb": 1024, "maxTabletSizeMb": 10240, "targetTabletSizeMb": 4096, "minTableSizeGbForSmallCheck": 20}', 0)
ON DUPLICATE KEY UPDATE
    rule_name = VALUES(rule_name),
    rule_type = VALUES(rule_type),
    severity = VALUES(severity),
    description = VALUES(description),
    rule_config = VALUES(rule_config);

