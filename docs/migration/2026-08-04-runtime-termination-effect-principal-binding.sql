-- Additive owner/tenant binding for newly accepted runtime termination effects.
--
-- This migration intentionally performs no UPDATE or backfill. Existing
-- PREPARED termination rows keep NULL bindings and fail closed at effect
-- authorization; an environment owner must handle them separately if desired.
-- Apply before starting a production launcher with ddl-auto=validate.

DELIMITER $$
CREATE PROCEDURE runtime_termination_effect_add_column(
    IN column_name_value VARCHAR(64),
    IN column_definition_value VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'lifecycle_effect_outbox'
          AND column_name = column_name_value
    ) THEN
        SET @runtime_termination_effect_ddl = CONCAT(
            'ALTER TABLE `lifecycle_effect_outbox` ADD COLUMN `',
            column_name_value, '` ', column_definition_value);
        PREPARE runtime_termination_effect_statement
            FROM @runtime_termination_effect_ddl;
        EXECUTE runtime_termination_effect_statement;
        DEALLOCATE PREPARE runtime_termination_effect_statement;
    END IF;
END$$
DELIMITER ;

CALL runtime_termination_effect_add_column(
    'owner_user_id', 'VARCHAR(64) NULL');
CALL runtime_termination_effect_add_column(
    'tenant_id', 'VARCHAR(64) NULL');

DROP PROCEDURE runtime_termination_effect_add_column;
