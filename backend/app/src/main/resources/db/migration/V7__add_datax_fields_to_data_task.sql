-- Add DataX-specific fields to data_task table
-- Migration for DataX task type support

-- Add target datasource name for DataX tasks
ALTER TABLE data_task
ADD COLUMN target_datasource_name VARCHAR(255) COMMENT 'Target datasource name for DataX tasks';

-- Add source table name for DataX tasks
ALTER TABLE data_task
ADD COLUMN source_table VARCHAR(255) COMMENT 'Source table name for DataX tasks';

-- Add target table name for DataX tasks
ALTER TABLE data_task
ADD COLUMN target_table VARCHAR(255) COMMENT 'Target table name for DataX tasks';

-- Add column mapping configuration for DataX tasks (optional JSON)
ALTER TABLE data_task
ADD COLUMN column_mapping TEXT COMMENT 'Column mapping configuration for DataX tasks (optional JSON format)';
