-- ============================================================
-- Compatibility migration: authoritative Claude task UTC epoch
-- Date: 2026-08-03
--
-- Forward-only, additive and safe to run repeatedly. Existing rows remain
-- NULL by design: created_at is a DATETIME without an offset, so converting
-- it to an epoch would invent a writer time zone. This migration performs no
-- UPDATE, backfill, replay or other historical-data mutation.
-- ============================================================

SET @claude_created_at_epoch_column_count = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'claude_tasks'
       AND column_name = 'created_at_epoch_ms'
);

SET @claude_created_at_epoch_ddl = IF(
    @claude_created_at_epoch_column_count = 0,
    'ALTER TABLE claude_tasks ADD COLUMN created_at_epoch_ms BIGINT NULL AFTER created_at',
    'DO 0'
);

PREPARE claude_created_at_epoch_stmt FROM @claude_created_at_epoch_ddl;
EXECUTE claude_created_at_epoch_stmt;
DEALLOCATE PREPARE claude_created_at_epoch_stmt;
