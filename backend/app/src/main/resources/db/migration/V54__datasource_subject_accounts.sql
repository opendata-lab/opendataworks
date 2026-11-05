DROP TABLE user_database_permissions;
DROP TABLE doris_database_users;
DROP TABLE platform_users;

CREATE TABLE datasource_subject_account (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id VARCHAR(191) NOT NULL,
    username VARCHAR(255) NOT NULL,
    password TEXT NULL,
    UNIQUE KEY uk_datasource_subject (source_id, subject_type, subject_id),
    CONSTRAINT fk_subject_account_source FOREIGN KEY (source_id) REFERENCES doris_cluster(id) ON DELETE CASCADE
);

ALTER TABLE sys_user ADD UNIQUE KEY uk_sys_user_external (external_id);
ALTER TABLE data_query_history
    ADD COLUMN agent_id VARCHAR(191) NULL,
    ADD COLUMN check_account VARCHAR(255) NULL,
    ADD COLUMN execution_account VARCHAR(255) NULL;
