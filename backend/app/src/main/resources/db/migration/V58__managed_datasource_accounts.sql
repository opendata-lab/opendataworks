-- Existing DBA mappings remain externally managed and retain their password representation.
ALTER TABLE datasource_subject_account
    ADD COLUMN managed TINYINT NOT NULL DEFAULT 0 COMMENT 'Platform-created login; password is AES-GCM encrypted';
