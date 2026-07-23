-- Sanitized, short-retention audit evidence for runtime-token and safe-ask.
-- Apply before deploying a launcher that uses spring.jpa.hibernate.ddl-auto=validate.
-- The tables intentionally contain no key/secret, access token, task token,
-- Authorization/header set, prompt, payload, model response, environment, or stack.

CREATE TABLE IF NOT EXISTS runtime_request_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_request_id VARCHAR(36) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    upstream_system_id VARCHAR(128) NOT NULL,
    client_app_id VARCHAR(128) NOT NULL,
    credential_id VARCHAR(128) NOT NULL,
    agent_code VARCHAR(255) NULL,
    upstream_user_id VARCHAR(255) NULL,
    received_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    terminal BIT NULL,
    result VARCHAR(128) NULL,
    sanitized_error_code VARCHAR(128) NULL,
    safe_error_summary VARCHAR(255) NULL,
    http_request_received BIT NULL,
    runtime_token_request_received BIT NULL,
    runtime_token_issued BIT NULL,
    safe_smoke_request_received BIT NULL,
    synthetic_evidence_created BIT NULL,
    task_id VARCHAR(64) NULL,
    status VARCHAR(64) NULL,
    effective_tool_count INT NULL,
    tool_scope_kind VARCHAR(128) NULL,
    tool_scope_source VARCHAR(128) NULL,
    effective_function_count INT NULL,
    function_scope_source VARCHAR(128) NULL,
    task_token_function_scope_empty BIT NULL,
    task_token_status VARCHAR(64) NULL,
    runtime_dispatched BIT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_runtime_audit_request (client_request_id),
    KEY idx_runtime_audit_scope_time (
        tenant_id, upstream_system_id, client_app_id, received_at
    ),
    KEY idx_runtime_audit_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS runtime_request_audit_stage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    client_request_id VARCHAR(36) NOT NULL,
    stage VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sanitized_error_code VARCHAR(128) NULL,
    occurred_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_runtime_audit_stage_request_time (client_request_id, occurred_at),
    CONSTRAINT fk_runtime_audit_stage_request
        FOREIGN KEY (client_request_id)
        REFERENCES runtime_request_audit (client_request_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
