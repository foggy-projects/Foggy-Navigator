-- ARCH-001-ACT-001 additive activation-authority metadata.
-- Applying this schema does not open activation; all gates remain closed until
-- an exact target manifest, generation, registration and DB-time proof exist.

CREATE TABLE IF NOT EXISTS lifecycle_activation_targets (
    target_id VARCHAR(96) NOT NULL PRIMARY KEY,
    run_id VARCHAR(96) NOT NULL,
    target_class VARCHAR(48) NOT NULL,
    provider_evidence_lane VARCHAR(32) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    physical_worker_id VARCHAR(128) NOT NULL,
    model_config_id VARCHAR(64) NOT NULL,
    model VARCHAR(128) NOT NULL,
    codex_home_key VARCHAR(256) NOT NULL,
    prompt_sha256 VARCHAR(64) NOT NULL,
    target_commit VARCHAR(64) NOT NULL,
    candidate_patch_sha256 VARCHAR(64) NOT NULL,
    owner_protocol INT NOT NULL,
    worker_version VARCHAR(64) NOT NULL,
    worker_protocol INT NOT NULL,
    required_capabilities_json TEXT NOT NULL,
    manifest_digest VARCHAR(128) NOT NULL,
    controller_inventory_digest VARCHAR(128) NOT NULL,
    generation_id VARCHAR(96) NOT NULL,
    writer_instance_id VARCHAR(128) NOT NULL,
    proof_id VARCHAR(96) NULL,
    worker_state_generation VARCHAR(128) NULL,
    worker_instance_epoch VARCHAR(128) NULL,
    reserved_session_id VARCHAR(64) NULL,
    reserved_task_id VARCHAR(64) NULL,
    reserved_at DATETIME(6) NULL,
    status VARCHAR(24) NOT NULL,
    safe_reason_code VARCHAR(96) NULL,
    last_observed_at DATETIME(6) NULL,
    destroyed_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_lat_run_id (run_id),
    KEY idx_lat_status (status),
    KEY idx_lat_reserved_task (reserved_task_id)
) ENGINE=InnoDB;

DELIMITER $$
CREATE PROCEDURE arch001_act_add_column(
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
        SET @arch001_act_ddl = CONCAT(
            'ALTER TABLE `', table_name_value,
            '` ADD COLUMN `', column_name_value,
            '` ', column_definition_value);
        PREPARE arch001_act_statement FROM @arch001_act_ddl;
        EXECUTE arch001_act_statement;
        DEALLOCATE PREPARE arch001_act_statement;
    END IF;
END$$

CREATE PROCEDURE arch001_act_add_index(
    IN table_name_value VARCHAR(64),
    IN index_name_value VARCHAR(64),
    IN index_definition_value VARCHAR(255))
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = table_name_value
          AND index_name = index_name_value
    ) THEN
        SET @arch001_act_ddl = CONCAT(
            'ALTER TABLE `', table_name_value,
            '` ADD ', index_definition_value);
        PREPARE arch001_act_statement FROM @arch001_act_ddl;
        EXECUTE arch001_act_statement;
        DEALLOCATE PREPARE arch001_act_statement;
    END IF;
END$$
DELIMITER ;

CALL arch001_act_add_column(
    'lifecycle_writer_generations', 'target_id', 'VARCHAR(96) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_generations', 'run_id', 'VARCHAR(96) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_generations', 'controller_inventory_digest',
    'VARCHAR(128) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_generations', 'active_slot', 'VARCHAR(16) NULL');
CALL arch001_act_add_index(
    'lifecycle_writer_generations', 'uk_lwg_active_slot',
    'UNIQUE KEY `uk_lwg_active_slot` (`active_slot`)');

CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'target_id',
    'VARCHAR(96) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'run_id',
    'VARCHAR(96) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations',
    'controller_inventory_digest', 'VARCHAR(128) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'status',
    'VARCHAR(24) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'registered_at',
    'DATETIME(6) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'expires_at',
    'DATETIME(6) NULL');
CALL arch001_act_add_column(
    'lifecycle_writer_instance_registrations', 'row_version',
    'BIGINT NOT NULL DEFAULT 0');

DROP PROCEDURE arch001_act_add_index;
DROP PROCEDURE arch001_act_add_column;
