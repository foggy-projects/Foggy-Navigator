-- ARCH-001 third-remediation additive lifecycle metadata.
-- Safe for databases that already applied the 2026-07-30 baseline.

DELIMITER $$
CREATE PROCEDURE arch001_r3_add_column(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN column_definition_value VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND column_name = column_name_value
    ) THEN
        SET @arch001_r3_ddl = CONCAT(
            'ALTER TABLE `', table_name_value,
            '` ADD COLUMN `', column_name_value,
            '` ', column_definition_value);
        PREPARE arch001_r3_statement FROM @arch001_r3_ddl;
        EXECUTE arch001_r3_statement;
        DEALLOCATE PREPARE arch001_r3_statement;
    END IF;
END$$
DELIMITER ;

CALL arch001_r3_add_column(
    'lifecycle_effect_outbox', 'ownership_mode', 'VARCHAR(16) NULL');
CALL arch001_r3_add_column(
    'lifecycle_effect_outbox', 'state_generation', 'VARCHAR(128) NULL');
CALL arch001_r3_add_column(
    'lifecycle_effect_outbox', 'instance_epoch', 'VARCHAR(128) NULL');
CALL arch001_r3_add_column(
    'lifecycle_effect_outbox', 'binding_digest_version', 'VARCHAR(32) NULL');
CALL arch001_r3_add_column(
    'task_terminal_tombstones', 'client_request_id', 'VARCHAR(96) NULL');
CALL arch001_r3_add_column(
    'lifecycle_writer_exclusivity_proofs',
    'quarantine_cursor',
    'VARCHAR(160) NULL');

DROP PROCEDURE arch001_r3_add_column;
