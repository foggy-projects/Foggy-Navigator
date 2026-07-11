-- ============================================================
-- Compatibility migration: authoritative Codex task UTC epoch
-- Date: 2026-07-10
--
-- Run this after an environment has already applied an earlier copy of
-- 2026-07-10-codex-runtime-affinity.sql that did not contain this column.
-- It is safe to run repeatedly. Existing rows intentionally remain NULL:
-- their DATETIME values do not contain an offset and cannot be converted
-- to an instant without guessing the writer's JVM time zone.
-- ============================================================

SET @codex_created_at_epoch_column_count = (
    SELECT COUNT(*)
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'codex_tasks'
       AND column_name = 'created_at_epoch_ms'
);

SET @codex_created_at_epoch_ddl = IF(
    @codex_created_at_epoch_column_count = 0,
    'ALTER TABLE codex_tasks ADD COLUMN created_at_epoch_ms BIGINT NULL',
    'DO 0'
);

PREPARE codex_created_at_epoch_stmt FROM @codex_created_at_epoch_ddl;
EXECUTE codex_created_at_epoch_stmt;
DEALLOCATE PREPARE codex_created_at_epoch_stmt;
