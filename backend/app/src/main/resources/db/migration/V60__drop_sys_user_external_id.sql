ALTER TABLE sys_user
    DROP INDEX uk_sys_user_external,
    DROP INDEX idx_external_id,
    DROP COLUMN external_id;
