-- Add datasource/schema fields to inspection issues for grouping & querying
ALTER TABLE inspection_issue
    ADD COLUMN cluster_id BIGINT NULL COMMENT '数据源ID(集群)' AFTER record_id,
    ADD COLUMN db_name VARCHAR(128) NULL COMMENT 'Schema/数据库名' AFTER cluster_id;

-- Indexes to support querying by record + datasource + schema
CREATE INDEX idx_issue_cluster_db ON inspection_issue (cluster_id, db_name);
CREATE INDEX idx_issue_record_cluster_db ON inspection_issue (record_id, cluster_id, db_name);

-- Backfill existing issues for table resources
UPDATE inspection_issue i
JOIN data_table t ON i.resource_type = 'table' AND i.resource_id = t.id
SET i.cluster_id = t.cluster_id,
    i.db_name = t.db_name
WHERE i.cluster_id IS NULL OR i.db_name IS NULL;

