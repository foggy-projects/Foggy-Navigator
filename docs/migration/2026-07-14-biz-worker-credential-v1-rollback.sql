-- Rollback for 2026-07-14-biz-worker-credential-v1.sql.
--
-- Keep external mode disabled, stop every v1 launcher and database writer,
-- then back up biz_worker_identity before running this script. Do not remove
-- columns under a running v1 application: Hibernate would immediately observe
-- an incompatible schema. Modern credentials are disabled and cleared before
-- their lifecycle columns are removed, so rollback cannot turn a scoped,
-- revocable credential into an implicitly trusted legacy token.

DELIMITER //

DROP PROCEDURE IF EXISTS gov142_disable_modern_worker_credentials //
CREATE PROCEDURE gov142_disable_modern_worker_credentials()
BEGIN
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'biz_worker_identity'
       AND COLUMN_NAME = 'credential_version';
    IF column_count > 0 THEN
        UPDATE biz_worker_identity
           SET status = 'DISABLED',
               token_hash = NULL
         WHERE credential_version > 0;
    END IF;
END //

DROP PROCEDURE IF EXISTS gov142_drop_worker_credential_column //
CREATE PROCEDURE gov142_drop_worker_credential_column(IN target_column VARCHAR(64))
BEGIN
    DECLARE column_count INT DEFAULT 0;
    SELECT COUNT(*) INTO column_count
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'biz_worker_identity'
       AND COLUMN_NAME = target_column;
    IF column_count > 0 THEN
        SET @gov142_worker_rollback_sql = CONCAT(
            'ALTER TABLE `biz_worker_identity` DROP COLUMN `',
            REPLACE(target_column, '`', '``'), '`'
        );
        PREPARE gov142_worker_rollback_statement FROM @gov142_worker_rollback_sql;
        EXECUTE gov142_worker_rollback_statement;
        DEALLOCATE PREPARE gov142_worker_rollback_statement;
    END IF;
END //

DELIMITER ;

CALL gov142_disable_modern_worker_credentials();
CALL gov142_drop_worker_credential_column('credential_rotated_at');
CALL gov142_drop_worker_credential_column('credential_revoked_at');
CALL gov142_drop_worker_credential_column('credential_expires_at');
CALL gov142_drop_worker_credential_column('credential_issued_at');
CALL gov142_drop_worker_credential_column('credential_version');
CALL gov142_drop_worker_credential_column('row_version');

DROP PROCEDURE IF EXISTS gov142_drop_worker_credential_column;
DROP PROCEDURE IF EXISTS gov142_disable_modern_worker_credentials;

-- Operational follow-up:
-- Re-register/rotate affected development Workers only after deciding how the
-- older application will authenticate them. Do not re-enable external routing
-- merely because the old schema starts successfully.
