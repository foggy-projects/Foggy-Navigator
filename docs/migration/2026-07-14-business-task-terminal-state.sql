-- ============================================================
-- GOV-002: durable terminal-state guard for task capabilities
-- Date: 2026-07-14
--
-- Apply before starting a launcher that uses ddl-auto=validate.
-- Safe to rerun on MySQL 8.0/8.4.
-- ============================================================

CREATE TABLE IF NOT EXISTS business_task_terminal_state (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id VARCHAR(64) NOT NULL,
    worker_task_id VARCHAR(128) NOT NULL,
    business_task_id VARCHAR(64) NULL,
    source_agent_id VARCHAR(64) NULL,
    provider_task_user_id VARCHAR(64) NOT NULL,
    navigator_effective_user_id VARCHAR(64) NULL,
    terminal_status VARCHAR(32) NOT NULL,
    terminal_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revocation_completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_terminal_tenant_worker_task (tenant_id, worker_task_id),
    KEY idx_biz_terminal_tenant_business_task (tenant_id, business_task_id),
    KEY idx_biz_terminal_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_terminal_ensure_column //
CREATE PROCEDURE gov142_terminal_ensure_column(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64),
    IN column_definition TEXT
)
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND COLUMN_NAME = target_column;

    IF table_count > 0 AND column_count = 0 THEN
        SET @gov142_terminal_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` ADD COLUMN `', REPLACE(target_column, '`', '``'), '` ', column_definition
        );
        PREPARE gov142_terminal_statement FROM @gov142_terminal_sql;
        EXECUTE gov142_terminal_statement;
        DEALLOCATE PREPARE gov142_terminal_statement;
    END IF;
END //

DROP PROCEDURE IF EXISTS gov142_terminal_ensure_index //
CREATE PROCEDURE gov142_terminal_ensure_index(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN index_columns TEXT
)
BEGIN
    DECLARE table_count INT DEFAULT 0;
    DECLARE index_count INT DEFAULT 0;
    SELECT COUNT(*) INTO table_count
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table;
    SELECT COUNT(*) INTO index_count
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND INDEX_NAME = target_index;

    IF table_count > 0 AND index_count = 0 THEN
        SET @gov142_terminal_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` ADD INDEX `', REPLACE(target_index, '`', '``'), '` ', index_columns
        );
        PREPARE gov142_terminal_statement FROM @gov142_terminal_sql;
        EXECUTE gov142_terminal_statement;
        DEALLOCATE PREPARE gov142_terminal_statement;
    END IF;
END //

DELIMITER ;

CALL gov142_terminal_ensure_column(
    'business_task_terminal_state', 'provider_task_user_id', 'VARCHAR(64) NULL');
CALL gov142_terminal_ensure_column(
    'business_task_terminal_state', 'source_agent_id', 'VARCHAR(64) NULL');

-- Upgrade an earlier development draft safely: its actor column held the
-- provider task owner for unbound rows. Preserve that subject separately and
-- clear capability correlation when no business task was ever bound.
UPDATE business_task_terminal_state
   SET provider_task_user_id = COALESCE(
           provider_task_user_id, navigator_effective_user_id),
       navigator_effective_user_id = CASE
           WHEN business_task_id IS NULL THEN NULL
           ELSE navigator_effective_user_id
       END;

ALTER TABLE business_task_terminal_state
    MODIFY COLUMN provider_task_user_id VARCHAR(64) NOT NULL,
    MODIFY COLUMN navigator_effective_user_id VARCHAR(64) NULL;

CALL gov142_terminal_ensure_index(
    'business_task_scoped_token',
    'idx_biz_token_tenant_worker_task',
    '(`tenant_id`, `worker_task_id`)');

DROP PROCEDURE IF EXISTS gov142_terminal_ensure_index;
DROP PROCEDURE IF EXISTS gov142_terminal_ensure_column;

-- Operational follow-up:
-- 1. Restart with ddl-auto=validate and confirm schema validation passes.
-- 2. A terminal tombstone is authorization-authoritative; physical REVOKED
--    token rows are a retryable materialized view, not the security boundary.
-- 3. Expired tombstone rows are not currently deleted automatically; add an
--    evidence-backed cleanup job before long-lived production enablement.
