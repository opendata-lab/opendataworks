-- OAuth 身份与平台账号分离（与 DataAgent da_user_identity 对齐，见 docs/design/2026-11-16-oauth-login-design.md OA-08）：
-- 用户名改为普通用户名（IdP preferred_username，缺省为 sub），(registration_id, subject) 记在本表。
CREATE TABLE `sys_user_identity` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `registration_id` VARCHAR(32)  NOT NULL COMMENT 'OAuth client registration id',
  `subject`         VARCHAR(255) NOT NULL COMMENT 'OIDC sub / OAuth2 name',
  `user_id`         BIGINT       NOT NULL COMMENT 'sys_user.id',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_identity` (`registration_id`, `subject`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OAuth 外部身份绑定';

-- 已有 OAuth 用户（username = <registration id>:<sub>）补登身份；用户名在下次 SSO 登录时改为普通用户名。
INSERT INTO `sys_user_identity` (`registration_id`, `subject`, `user_id`)
SELECT `auth_source`, SUBSTRING(`username`, LENGTH(`auth_source`) + 2), `id`
FROM `sys_user`
WHERE `auth_source` NOT IN ('local', 'service')
  AND `username` LIKE CONCAT(`auth_source`, ':%');
