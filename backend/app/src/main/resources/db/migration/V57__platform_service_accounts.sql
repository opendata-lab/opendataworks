ALTER TABLE sys_user
    ADD COLUMN account_type VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN description VARCHAR(500) NULL;

CREATE TABLE service_account_token (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_prefix VARCHAR(24) NOT NULL,
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    last_used_at DATETIME NULL,
    created_by VARCHAR(191) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_service_token_hash (token_hash),
    KEY idx_service_token_user (user_id, id),
    CONSTRAINT fk_service_token_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
);

-- Convert actual existing bindings, never import an external assistant catalog.
-- Reserved-name collisions deliberately fail instead of claiming an existing user.
CREATE TEMPORARY TABLE legacy_service_subjects AS
SELECT subject_id AS old_id,
       CONCAT('svc_legacy_', LEFT(SHA2(subject_id, 256), 20)) AS account_name
FROM (
    SELECT subject_id FROM datasource_subject_account WHERE subject_type='AGENT'
    UNION
    SELECT subject_id FROM datasource_access_member WHERE subject_type='AGENT'
) subjects;

INSERT INTO sys_user(username, nickname, role, enabled, auth_source, account_type, description)
SELECT account_name, LEFT(old_id, 100), 'user', 1, 'service', 'SERVICE',
       '由既有智能体数据源绑定迁移；请在账号管理中生成服务Token'
FROM legacy_service_subjects;

UPDATE datasource_subject_account a
JOIN legacy_service_subjects l ON a.subject_type='AGENT' AND a.subject_id=l.old_id
JOIN sys_user u ON u.username=l.account_name AND u.account_type='SERVICE'
SET a.subject_type='SERVICE', a.subject_id=CAST(u.id AS CHAR);

UPDATE datasource_access_member m
JOIN legacy_service_subjects l ON m.subject_type='AGENT' AND m.subject_id=l.old_id
JOIN sys_user u ON u.username=l.account_name AND u.account_type='SERVICE'
SET m.subject_type='SERVICE', m.subject_id=CAST(u.id AS CHAR);

DROP TEMPORARY TABLE legacy_service_subjects;
