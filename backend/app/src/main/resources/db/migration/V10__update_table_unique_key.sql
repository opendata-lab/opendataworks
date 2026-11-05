-- Update unique constraint for data_table to support same table name in different databases
SET NAMES utf8mb4;

-- Drop the existing unique index on table_name
ALTER TABLE `data_table` DROP INDEX `uk_table_name`;

-- Add a new composite unique index on (db_name, table_name)
-- Note: db_name should be not null for this to work effectively as intended, 
-- but existing schema allows null. If null, uniqueness logic might be tricky in MySQL (allows multiple NULLs).
-- Assuming db_name is populated for all tables that need this distinction.
ALTER TABLE `data_table` ADD UNIQUE KEY `uk_db_table_name` (`db_name`, `table_name`);
