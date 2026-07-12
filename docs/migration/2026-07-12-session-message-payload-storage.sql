-- ============================================================
-- OPT-001: tiered session-message payload storage (Stage 1)
--
-- Run this pre-deploy migration before a production launcher using
-- ddl-auto=validate starts. It is safe to rerun on MySQL 8.0 and 8.4.
-- It creates only descriptor metadata; payload files are written by the
-- session-module filesystem backend and are intentionally not migrated here.
-- ============================================================

CREATE TABLE IF NOT EXISTS session_message_payloads (
    id VARCHAR(64) NOT NULL,
    message_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    backend VARCHAR(32) NOT NULL,
    storage_key VARCHAR(512) NULL,
    content_type VARCHAR(128) NOT NULL,
    content_encoding VARCHAR(32) NOT NULL,
    original_bytes BIGINT NOT NULL,
    stored_bytes BIGINT NULL,
    sha256 VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //

DROP PROCEDURE IF EXISTS opt001_ensure_index //
CREATE PROCEDURE opt001_ensure_index(
    IN target_table VARCHAR(64),
    IN target_index VARCHAR(64),
    IN index_definition TEXT
)
BEGIN
    DECLARE index_count INT DEFAULT 0;
    SELECT COUNT(*) INTO index_count
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND INDEX_NAME = target_index;

    IF index_count = 0 THEN
        SET @opt001_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'), '` ADD ', index_definition
        );
        PREPARE opt001_statement FROM @opt001_sql;
        EXECUTE opt001_statement;
        DEALLOCATE PREPARE opt001_statement;
    END IF;
END //

DROP PROCEDURE IF EXISTS opt001_ensure_mediumtext //
CREATE PROCEDURE opt001_ensure_mediumtext(
    IN target_table VARCHAR(64),
    IN target_column VARCHAR(64)
)
BEGIN
    DECLARE column_count INT DEFAULT 0;
    DECLARE column_type VARCHAR(64);
    SELECT COUNT(*), MAX(DATA_TYPE)
      INTO column_count, column_type
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = target_table
       AND COLUMN_NAME = target_column;

    IF column_count > 0 AND LOWER(column_type) NOT IN ('mediumtext', 'longtext') THEN
        SET @opt001_sql = CONCAT(
            'ALTER TABLE `', REPLACE(target_table, '`', '``'),
            '` MODIFY COLUMN `', REPLACE(target_column, '`', '``'), '` MEDIUMTEXT NULL'
        );
        PREPARE opt001_statement FROM @opt001_sql;
        EXECUTE opt001_statement;
        DEALLOCATE PREPARE opt001_statement;
    END IF;
END //

DELIMITER ;

CALL opt001_ensure_index(
    'session_message_payloads',
    'uk_smp_message_id',
    'UNIQUE INDEX `uk_smp_message_id` (`message_id`)'
);
CALL opt001_ensure_index(
    'session_message_payloads',
    'idx_smp_session_id',
    'INDEX `idx_smp_session_id` (`session_id`)'
);
CALL opt001_ensure_index(
    'session_message_payloads',
    'idx_smp_status_expires_at',
    'INDEX `idx_smp_status_expires_at` (`status`, `expires_at`)'
);
CALL opt001_ensure_index(
    'session_message_payloads',
    'idx_smp_expires_at',
    'INDEX `idx_smp_expires_at` (`expires_at`)'
);

CALL opt001_ensure_mediumtext('session_messages', 'content');
CALL opt001_ensure_mediumtext('session_messages', 'metadata');
CALL opt001_ensure_mediumtext('session_tasks', 'result_text');
CALL opt001_ensure_mediumtext('codex_tasks', 'result_text');
CALL opt001_ensure_mediumtext('claude_tasks', 'result_text');
CALL opt001_ensure_mediumtext('gemini_tasks', 'result_text');
CALL opt001_ensure_mediumtext('langgraph_tasks', 'result_text');
CALL opt001_ensure_mediumtext('langgraph_tasks', 'structured_output');

DROP PROCEDURE IF EXISTS opt001_ensure_mediumtext;
DROP PROCEDURE IF EXISTS opt001_ensure_index;

-- The storage key is intentionally database-only. Do not add it to any
-- session message list, history, SSE snapshot, or external API projection.
