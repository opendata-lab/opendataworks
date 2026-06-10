-- 平台本地用户表（登录/密码/角色权威，见 docs/design/2026-05-30-configurable-oauth-login-design.md 5.1）
CREATE TABLE `sys_user` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username`        VARCHAR(128) NOT NULL COMMENT '登录用户名',
  `password_hash`   VARCHAR(255) NULL     COMMENT 'bcrypt 口令哈希;OAuth-only 用户可空',
  `nickname`        VARCHAR(128) NULL     COMMENT '显示名',
  `email`           VARCHAR(128) NULL     COMMENT '邮箱',
  `role`            VARCHAR(32)  NOT NULL DEFAULT 'user' COMMENT '角色 admin/user',
  `enabled`         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
  `auth_source`     VARCHAR(32)  NOT NULL DEFAULT 'local' COMMENT 'local/oauth',
  `external_id`     VARCHAR(255) NULL     COMMENT 'OAuth sub,本地账号为空',
  `failed_attempts` INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
  `locked_until`    DATETIME     NULL     COMMENT '锁定截止时间',
  `last_login_at`   DATETIME     NULL     COMMENT '最近登录时间',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  KEY `idx_external_id` (`external_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='平台本地用户表';

-- 初始管理员 admin，默认口令 admin123（bcrypt），部署后请通过「修改密码」立即更换
INSERT INTO `sys_user` (`username`, `password_hash`, `nickname`, `role`, `enabled`, `auth_source`)
VALUES ('admin', '$2a$10$UH1EXFfUxjTSk75341ddU./2/w63hc0GkmC31RlKgUmiU3eh3iK0i', '管理员', 'admin', 1, 'local');
