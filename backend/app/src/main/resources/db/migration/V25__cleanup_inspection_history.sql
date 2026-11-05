-- Cleanup inspection history (one-off)
--
-- Why:
--   Previous inspection runs may have produced too much historical data.
--   This migration clears all inspection history:
--     - `inspection_issue`
--     - `inspection_record`
--
-- NOTE:
--   If you need a retention policy in the future, create a new Flyway migration.

-- Truncate is faster but blocked by FK; temporarily disable FK checks in this session.
SET @old_fk_checks := @@FOREIGN_KEY_CHECKS;
SET FOREIGN_KEY_CHECKS = 0;

TRUNCATE TABLE inspection_issue;
TRUNCATE TABLE inspection_record;

SET FOREIGN_KEY_CHECKS = @old_fk_checks;
