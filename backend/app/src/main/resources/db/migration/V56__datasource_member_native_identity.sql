-- Preserve the identity verified at binding so revocation does not need an unexpired member password.
ALTER TABLE datasource_access_member ADD COLUMN native_identity TEXT NULL;
