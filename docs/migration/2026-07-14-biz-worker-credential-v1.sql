-- ============================================================
-- GOV-002: versioned Biz Worker identity credentials
-- Date: 2026-07-14
--
-- Apply this migration before starting any production-profile launcher whose
-- Hibernate ddl-auto mode is validate. Existing token_hash values remain
-- credential_version=0 and are deliberately legacy-only; strict external
-- Worker authentication must reject them and owners must rotate a new secret.
-- Safe to rerun on MySQL 8.0/8.4.
-- ============================================================

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_ensure_worker_credential_column //
CREATE PROCEDURE gov142_ensure_worker_credential_column(
    IN target_column VARCHAR(64),
    IN column_definition TEXT
)
BEGIN
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'biz_worker_identity'
       AND COLUMN_NAME = target_column;

    IF column_count = 0 THEN
        SET @gov142_worker_sql = CONCAT(
            'ALTER TABLE `biz_worker_identity` ADD COLUMN `',
            REPLACE(target_column, '`', '``'), '` ', column_definition
        );
        PREPARE gov142_worker_statement FROM @gov142_worker_sql;
        EXECUTE gov142_worker_statement;
        DEALLOCATE PREPARE gov142_worker_statement;
    END IF;
END //

DELIMITER ;

CALL gov142_ensure_worker_credential_column('row_version', 'BIGINT NOT NULL DEFAULT 0');
CALL gov142_ensure_worker_credential_column('credential_version', 'INT NOT NULL DEFAULT 0');
CALL gov142_ensure_worker_credential_column('credential_issued_at', 'DATETIME(6) NULL');
CALL gov142_ensure_worker_credential_column('credential_expires_at', 'DATETIME(6) NULL');
CALL gov142_ensure_worker_credential_column('credential_revoked_at', 'DATETIME(6) NULL');
CALL gov142_ensure_worker_credential_column('credential_rotated_at', 'DATETIME(6) NULL');

-- Preserve every pre-migration token as explicit legacy v0. Do not synthesize
-- issue/expiry timestamps: a timestamp would make an old token look modern.
UPDATE biz_worker_identity
   SET credential_version = 0
 WHERE credential_version IS NULL;

ALTER TABLE biz_worker_identity
    MODIFY COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN credential_version INT NOT NULL DEFAULT 0;

DROP PROCEDURE IF EXISTS gov142_ensure_worker_credential_column;

-- Operational follow-up:
-- 1. Start one launcher with ddl-auto=validate and verify schema validation.
-- 2. Rotate each Worker through its owner-scoped credential endpoint. Store the
--    returned bwc_ secret immediately; Navigator persists only its SHA-256 hash.
-- 3. Keep external Worker strict mode disabled until every required Worker has
--    credential_version > 0 and an unexpired credential.
