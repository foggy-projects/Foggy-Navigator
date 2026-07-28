-- BUG-031: bind newly issued task-scoped tokens to the server-owned
-- Navigator instance and the caller authority used at issuance.
--
-- Existing rows intentionally remain NULL and therefore fail closed at use
-- time. Reissue active tasks through normal Task governance after migration.

DROP PROCEDURE IF EXISTS bug031_ensure_column;
DELIMITER $$
CREATE PROCEDURE bug031_ensure_column(
    IN p_table_name VARCHAR(128),
    IN p_column_name VARCHAR(128),
    IN p_definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = DATABASE()
           AND table_name = p_table_name
           AND column_name = p_column_name
    ) THEN
        SET @ddl = CONCAT(
            'ALTER TABLE `', p_table_name,
            '` ADD COLUMN `', p_column_name, '` ', p_definition
        );
        PREPARE statement_handle FROM @ddl;
        EXECUTE statement_handle;
        DEALLOCATE PREPARE statement_handle;
    END IF;
END$$
DELIMITER ;

CALL bug031_ensure_column(
    'business_task_scoped_token',
    'navigator_instance_id',
    'VARCHAR(128) NULL');
CALL bug031_ensure_column(
    'business_task_scoped_token',
    'caller_authority_type',
    'VARCHAR(48) NULL');
CALL bug031_ensure_column(
    'business_task_scoped_token',
    'caller_credential_id',
    'VARCHAR(64) NULL');
CALL bug031_ensure_column(
    'business_task_scoped_token',
    'caller_access_token_id',
    'VARCHAR(64) NULL');

DROP PROCEDURE IF EXISTS bug031_ensure_column;
