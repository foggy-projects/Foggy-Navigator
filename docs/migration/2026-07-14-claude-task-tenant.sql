-- ============================================================
-- GOV-002: persist tenant ownership on Claude task rows
-- Date: 2026-07-14
--
-- Apply before starting a launcher that uses ddl-auto=validate.
-- The column remains nullable for legacy rows whose tenant cannot be proven;
-- runtime code refuses a definitive terminal event for such a row.
-- Safe to rerun on MySQL 8.0/8.4.
-- ============================================================

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_ensure_claude_task_tenant //
CREATE PROCEDURE gov142_ensure_claude_task_tenant()
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE column_count INT DEFAULT 0;

    SELECT COUNT(*) INTO table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks';
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks'
       AND COLUMN_NAME = 'tenant_id';

    IF table_count > 0 AND column_count = 0 THEN
        ALTER TABLE claude_tasks
            ADD COLUMN tenant_id VARCHAR(64) NULL;
    END IF;
END //

DROP PROCEDURE IF EXISTS gov142_ensure_claude_task_tenant_index //
CREATE PROCEDURE gov142_ensure_claude_task_tenant_index()
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks';
    SELECT COUNT(*) INTO index_count
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks'
       AND INDEX_NAME = 'idx_ct_tenant_id';

    IF table_count > 0 AND index_count = 0 THEN
        ALTER TABLE claude_tasks
            ADD INDEX idx_ct_tenant_id (tenant_id);
    END IF;
END //

DROP PROCEDURE IF EXISTS gov142_backfill_claude_task_tenant //
CREATE PROCEDURE gov142_backfill_claude_task_tenant()
BEGIN
    DECLARE task_table_count INT DEFAULT 0;
    DECLARE session_table_count INT DEFAULT 0;
    DECLARE worker_table_count INT DEFAULT 0;

    SELECT COUNT(*) INTO task_table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks';
    SELECT COUNT(*) INTO session_table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'sessions';
    SELECT COUNT(*) INTO worker_table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_workers';

    IF task_table_count > 0 AND session_table_count > 0 THEN
        UPDATE claude_tasks ct
        JOIN sessions s ON s.id = ct.session_id
           SET ct.tenant_id = NULLIF(TRIM(s.tenant_id), '')
         WHERE (ct.tenant_id IS NULL OR TRIM(ct.tenant_id) = '')
           AND s.tenant_id IS NOT NULL
           AND TRIM(s.tenant_id) <> '';
    END IF;

    IF task_table_count > 0 AND worker_table_count > 0 THEN
        UPDATE claude_tasks ct
        JOIN claude_workers cw ON cw.worker_id = ct.worker_id
           SET ct.tenant_id = NULLIF(TRIM(cw.tenant_id), '')
         WHERE (ct.tenant_id IS NULL OR TRIM(ct.tenant_id) = '')
           AND cw.tenant_id IS NOT NULL
           AND TRIM(cw.tenant_id) <> '';
    END IF;
END //

DELIMITER ;

CALL gov142_ensure_claude_task_tenant();
CALL gov142_backfill_claude_task_tenant();
CALL gov142_ensure_claude_task_tenant_index();

DROP PROCEDURE IF EXISTS gov142_ensure_claude_task_tenant_index;
DROP PROCEDURE IF EXISTS gov142_backfill_claude_task_tenant;
DROP PROCEDURE IF EXISTS gov142_ensure_claude_task_tenant;

-- Operational follow-up:
-- 1. Count rows where tenant_id remains NULL/blank and either delete those
--    development-only rows or repair them from an authoritative owner record.
-- 2. Keep external routing disabled until the unresolved count is zero.
-- 3. Start one launcher with ddl-auto=validate after applying the migration.
