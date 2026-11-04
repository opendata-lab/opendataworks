-- 为查询历史表添加执行用户字段
ALTER TABLE data_query_history ADD COLUMN executed_by VARCHAR(64) COMMENT '执行用户ID';

-- 为executed_by字段添加索引以提高查询性能
CREATE INDEX idx_executed_by ON data_query_history(executed_by);
