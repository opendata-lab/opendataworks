ALTER TABLE sys_user ADD COLUMN session_version INT NOT NULL DEFAULT 0;

CREATE TABLE platform_user_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    operator_id VARCHAR(191) NOT NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE datasource_access_role (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    native_name VARCHAR(100) NOT NULL,
    template VARCHAR(16) NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    native_grants LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_access_role_name (source_id, name),
    UNIQUE KEY uk_access_role_native (native_name),
    CONSTRAINT fk_access_role_source FOREIGN KEY (source_id) REFERENCES doris_cluster(id)
);
CREATE TABLE datasource_access_member (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id VARCHAR(191) NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'NEEDS_ACCOUNT',
    UNIQUE KEY uk_access_member_subject (source_id, subject_type, subject_id),
    CONSTRAINT fk_access_member_source FOREIGN KEY (source_id) REFERENCES doris_cluster(id)
);
INSERT INTO datasource_access_member(source_id, subject_type, subject_id, status)
SELECT source_id, subject_type, subject_id, 'ACTIVE' FROM datasource_subject_account;

CREATE TABLE datasource_access_member_role (
    member_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (member_id, role_id),
    CONSTRAINT fk_access_binding_member FOREIGN KEY (member_id) REFERENCES datasource_access_member(id),
    CONSTRAINT fk_access_binding_role FOREIGN KEY (role_id) REFERENCES datasource_access_role(id)
);
CREATE TABLE datasource_access_grant (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NULL,
    actions TEXT NOT NULL,
    CONSTRAINT fk_access_grant_role FOREIGN KEY (role_id) REFERENCES datasource_access_role(id)
);
CREATE TABLE datasource_permission_operation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    role_id BIGINT NULL,
    subject_type VARCHAR(16) NULL,
    subject_id VARCHAR(191) NULL,
    operator_id VARCHAR(191) NOT NULL,
    revision INT NULL,
    state VARCHAR(24) NOT NULL,
    message VARCHAR(1000) NULL,
    payload LONGTEXT NULL,
    steps LONGTEXT NULL,
    expires_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_permission_operation_source (source_id, id)
);
CREATE TABLE datasource_permission_manager (
    source_id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    password TEXT NOT NULL,
    CONSTRAINT fk_permission_manager_source FOREIGN KEY (source_id) REFERENCES doris_cluster(id)
);
