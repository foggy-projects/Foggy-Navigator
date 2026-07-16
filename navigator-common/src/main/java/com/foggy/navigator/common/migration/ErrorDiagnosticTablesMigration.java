package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Idempotent development/startup safety net; production must pre-apply the matching SQL. */
@Component
@RequiredArgsConstructor
public class ErrorDiagnosticTablesMigration implements DatabaseStartupMigration {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String id() {
        return "startup-005-error-diagnostic-tables";
    }

    @Override
    public String description() {
        return "Create redacted error diagnostic snapshot and temporary share tables";
    }

    @Override
    public void migrate() {
        jdbcTemplate.execute(ErrorDiagnosticTableDdl.DIAGNOSTICS);
        jdbcTemplate.execute(ErrorDiagnosticTableDdl.SHARES);
    }

    static final class ErrorDiagnosticTableDdl {
        private static final String DIAGNOSTICS = """
                CREATE TABLE IF NOT EXISTS error_diagnostics (
                    diagnostic_id VARCHAR(64) NOT NULL,
                    schema_version INT NOT NULL,
                    redaction_version INT NOT NULL,
                    task_id VARCHAR(64) NOT NULL,
                    session_id VARCHAR(64) NOT NULL,
                    owner_user_id VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NULL,
                    provider_type VARCHAR(32) NOT NULL,
                    runtime_type VARCHAR(32) NULL,
                    worker_label VARCHAR(128) NULL,
                    error_code VARCHAR(160) NOT NULL,
                    category VARCHAR(32) NOT NULL,
                    runtime_phase VARCHAR(48) NOT NULL,
                    safe_message VARCHAR(512) NOT NULL,
                    recoverable BIT NOT NULL,
                    provider_status VARCHAR(160) NULL,
                    http_status INT NULL,
                    retry_count INT NULL,
                    exception_type VARCHAR(160) NULL,
                    diagnostic_text TEXT NULL,
                    occurred_at DATETIME(6) NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    expires_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (diagnostic_id),
                    KEY idx_ed_task_id (task_id),
                    KEY idx_ed_session_id (session_id),
                    KEY idx_ed_owner_scope (owner_user_id, tenant_id),
                    KEY idx_ed_expires_at (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        private static final String SHARES = """
                CREATE TABLE IF NOT EXISTS error_diagnostic_shares (
                    share_id VARCHAR(64) NOT NULL,
                    diagnostic_id VARCHAR(64) NOT NULL,
                    token_hash VARCHAR(64) NOT NULL,
                    created_by VARCHAR(64) NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    expires_at DATETIME(6) NOT NULL,
                    revoked_at DATETIME(6) NULL,
                    last_access_at DATETIME(6) NULL,
                    access_count BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (share_id),
                    UNIQUE KEY idx_eds_token_hash (token_hash),
                    KEY idx_eds_diagnostic_id (diagnostic_id),
                    KEY idx_eds_expires_at (expires_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;

        private ErrorDiagnosticTableDdl() {
        }
    }
}
