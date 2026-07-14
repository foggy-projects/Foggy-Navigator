-- Rollback for 2026-07-14-business-task-terminal-state.sql.
-- Stop Business Agent task creation and Worker Gateway traffic first.
-- Existing ACTIVE task tokens must be revoked before removing the terminal
-- authorization guard, otherwise older code may reopen a terminal capability.

UPDATE business_task_scoped_token
   SET status = 'REVOKED',
       revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(6)),
       revoked_by = COALESCE(revoked_by, 'terminal-guard-rollback'),
       revoke_reason = COALESCE(revoke_reason, 'terminal authorization guard rollback')
 WHERE status = 'ACTIVE';

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_terminal_drop_column //
CREATE PROCEDURE gov142_terminal_drop_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64)
)
BEGIN
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND COLUMN_NAME = target_column;
    IF column_count > 0 THEN
        SET @gov142_terminal_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` DROP COLUMN `', REPLACE(target_column, '`', '``'), '`'
        );
        PREPARE gov142_terminal_statement FROM @gov142_terminal_sql;
        EXECUTE gov142_terminal_statement;
        DEALLOCATE PREPARE gov142_terminal_statement;
    END IF;
END //

DROP PROCEDURE IF EXISTS gov142_terminal_drop_index //
CREATE PROCEDURE gov142_terminal_drop_index(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64)
)
BEGIN
    DECLARE index_count INT DEFAULT 0;
    SELECT COUNT(*) INTO index_count
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND INDEX_NAME = target_index;
    IF index_count > 0 THEN
        SET @gov142_terminal_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` DROP INDEX `', REPLACE(target_index, '`', '``'), '`'
        );
        PREPARE gov142_terminal_statement FROM @gov142_terminal_sql;
        EXECUTE gov142_terminal_statement;
        DEALLOCATE PREPARE gov142_terminal_statement;
    END IF;
END //

DELIMITER ;

CALL gov142_terminal_drop_index(
    'business_task_scoped_token', 'idx_biz_token_tenant_worker_task');

DROP PROCEDURE IF EXISTS gov142_terminal_drop_index;
DROP PROCEDURE IF EXISTS gov142_terminal_drop_column;

DROP TABLE IF EXISTS business_task_terminal_state;
