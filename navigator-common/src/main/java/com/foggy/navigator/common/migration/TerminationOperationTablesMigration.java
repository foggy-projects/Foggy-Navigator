package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent development/startup safety net; production must pre-apply the matching SQL. */
@Component
@RequiredArgsConstructor
public class TerminationOperationTablesMigration implements DatabaseStartupMigration {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String id() {
        return "startup-006-termination-operation-tables";
    }

    @Override
    public String description() {
        return "Create auditable, explicitly authorized CLI termination operation table";
    }

    @Override
    public void migrate() {
        jdbcTemplate.execute(TerminationOperationTableDdl.OPERATIONS);
    }

    static final class TerminationOperationTableDdl {
        private static final String OPERATIONS = """
                CREATE TABLE IF NOT EXISTS termination_operations (
                    operation_id VARCHAR(64) NOT NULL,
                    schema_version INT NOT NULL,
                    task_id VARCHAR(64) NOT NULL,
                    provider_task_id VARCHAR(128) NULL,
                    session_id VARCHAR(64) NOT NULL,
                    owner_user_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NULL,
                    provider_type VARCHAR(32) NOT NULL,
                    worker_id VARCHAR(64) NOT NULL,
                    kind VARCHAR(32) NOT NULL,
                    origin VARCHAR(32) NOT NULL,
                    actor_id VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(32) NOT NULL,
                    authorization_decision_id VARCHAR(128) NULL,
                    reason_code VARCHAR(160) NOT NULL,
                    correlation_id VARCHAR(128) NULL,
                    expected_pid INT NULL,
                    expected_process_identity VARCHAR(160) NULL,
                    status VARCHAR(32) NOT NULL,
                    dispatch_state VARCHAR(32) NOT NULL,
                    attention_code VARCHAR(160) NULL,
                    failure_code VARCHAR(160) NULL,
                    requested_at DATETIME(6) NOT NULL,
                    dispatched_at DATETIME(6) NULL,
                    observed_at DATETIME(6) NULL,
                    expires_at DATETIME(6) NULL,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (operation_id),
                    KEY idx_to_task_id (task_id),
                    KEY idx_to_provider_task_id (provider_task_id),
                    KEY idx_to_session_id (session_id),
                    KEY idx_to_owner_scope (owner_user_id, tenant_id),
                    KEY idx_to_worker_id (worker_id),
                    KEY idx_to_status (status),
                    KEY idx_to_expires_at (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        private TerminationOperationTableDdl() {
        }
    }
}
