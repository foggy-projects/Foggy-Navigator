-- ============================================================
-- GOV-002: versioned Business Task capability tokens
-- Date: 2026-07-14
--
-- Apply before starting a launcher that uses ddl-auto=validate.
-- Existing tokens are deliberately migrated as legacy v1 with an empty
-- function scope, so Worker Gateway v2 rejects them fail-closed. The project
-- is still in dev; reissue active tasks/tokens after this migration.
-- Safe to rerun on MySQL 8.0/8.4.
-- ============================================================

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_ensure_column //
CREATE PROCEDURE gov142_ensure_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN column_definition TEXT
)
BEGIN
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND COLUMN_NAME = target_column;

    IF column_count = 0 THEN
        SET @gov142_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` ADD COLUMN `', REPLACE(target_column, '`', '``'), '` ', column_definition
        );
        PREPARE gov142_statement FROM @gov142_sql;
        EXECUTE gov142_statement;
        DEALLOCATE PREPARE gov142_statement;
    END IF;
END //

DELIMITER ;

CALL gov142_ensure_column('business_task_scoped_token', 'row_version', 'BIGINT NOT NULL DEFAULT 0');
CALL gov142_ensure_column('business_task_scoped_token', 'token_version', 'INT NOT NULL DEFAULT 1');
CALL gov142_ensure_column('business_task_scoped_token', 'generation', 'INT NOT NULL DEFAULT 1');
CALL gov142_ensure_column('business_task_scoped_token', 'audience', 'VARCHAR(64) NOT NULL DEFAULT ''LEGACY''');
CALL gov142_ensure_column('business_task_scoped_token', 'identity_assurance', 'VARCHAR(64) NOT NULL DEFAULT ''legacy-unverified''');
CALL gov142_ensure_column('business_task_scoped_token', 'function_scope_json', 'LONGTEXT NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'worker_id', 'VARCHAR(128) NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'worker_lease_id', 'VARCHAR(128) NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'issued_at', 'DATETIME(6) NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'revoked_at', 'DATETIME(6) NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'revoked_by', 'VARCHAR(128) NULL');
CALL gov142_ensure_column('business_task_scoped_token', 'revoke_reason', 'VARCHAR(512) NULL');

UPDATE business_task_scoped_token
   SET function_scope_json = '[]'
 WHERE function_scope_json IS NULL OR function_scope_json = '';

UPDATE business_task_scoped_token
   SET issued_at = COALESCE(created_at, CURRENT_TIMESTAMP(6))
 WHERE issued_at IS NULL;

ALTER TABLE business_task_scoped_token
    MODIFY COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN token_version INT NOT NULL DEFAULT 1,
    MODIFY COLUMN generation INT NOT NULL DEFAULT 1,
    MODIFY COLUMN audience VARCHAR(64) NOT NULL DEFAULT 'LEGACY',
    MODIFY COLUMN identity_assurance VARCHAR(64) NOT NULL DEFAULT 'legacy-unverified',
    MODIFY COLUMN function_scope_json LONGTEXT NOT NULL,
    MODIFY COLUMN issued_at DATETIME(6) NOT NULL;

DROP PROCEDURE IF EXISTS gov142_ensure_column;

-- Operational follow-up:
-- 1. Restart with ddl-auto=validate and confirm schema validation passes.
-- 2. Reissue active dev tasks/tokens. Legacy v1 rows intentionally cannot use
--    Worker Gateway v2 and may be deleted after confirming no task needs them.
