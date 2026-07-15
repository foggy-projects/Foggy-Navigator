-- Rollback for 2026-07-14-claude-task-tenant.sql.
--
-- Keep external routing disabled and stop launchers before rollback. Dropping
-- tenant_id restores the older join-dependent model and is not compatible
-- with the GOV-002 terminal fail-closed implementation.

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_drop_claude_task_tenant //
CREATE PROCEDURE gov142_drop_claude_task_tenant()
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE column_count INT DEFAULT 0;
    DECLARE index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks';
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks'
       AND COLUMN_NAME = 'tenant_id';
    SELECT COUNT(*) INTO index_count
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'claude_tasks'
       AND INDEX_NAME = 'idx_ct_tenant_id';

    IF table_count > 0 AND index_count > 0 THEN
        ALTER TABLE claude_tasks DROP INDEX idx_ct_tenant_id;
    END IF;
    IF table_count > 0 AND column_count > 0 THEN
        ALTER TABLE claude_tasks DROP COLUMN tenant_id;
    END IF;
END //

DELIMITER ;

CALL gov142_drop_claude_task_tenant();
DROP PROCEDURE IF EXISTS gov142_drop_claude_task_tenant;

-- Reapply the forward migration before starting this application version.
