-- Forward-only, additive substrate for content-free command once receipts.
-- Existing requests are intentionally not synthesized or backfilled.
CREATE TABLE IF NOT EXISTS command_once_receipts (
    receipt_id VARCHAR(64) NOT NULL,
    command_schema_version VARCHAR(64) NOT NULL,
    command_kind VARCHAR(32) NOT NULL,
    command_ingress VARCHAR(32) NOT NULL,
    client_surface VARCHAR(128) NOT NULL,
    route_id VARCHAR(256) NOT NULL,
    client_request_id VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    correlation_id VARCHAR(256) NOT NULL,
    actor_kind VARCHAR(32) NOT NULL,
    principal_type VARCHAR(64) NULL,
    credential_lane VARCHAR(64) NULL,
    principal_fingerprint VARCHAR(256) NULL,
    server_process_authority_reference VARCHAR(256) NULL,
    tenant_reference VARCHAR(256) NOT NULL,
    owner_reference VARCHAR(256) NOT NULL,
    client_app_reference VARCHAR(256) NULL,
    upstream_reference VARCHAR(256) NULL,
    target_kind VARCHAR(32) NOT NULL,
    target_id VARCHAR(256) NOT NULL,
    logical_agent_id VARCHAR(256) NULL,
    provider_type VARCHAR(256) NULL,
    physical_worker_id VARCHAR(256) NULL,
    model_config_id VARCHAR(256) NULL,
    task_id VARCHAR(256) NULL,
    session_id VARCHAR(256) NULL,
    action_id VARCHAR(256) NOT NULL,
    effect_scope_reference VARCHAR(256) NOT NULL,
    authorization_metadata_schema_version VARCHAR(64) NOT NULL,
    authorization_decision_id VARCHAR(256) NOT NULL,
    authorization_policy_version VARCHAR(256) NOT NULL,
    authorization_correlation_id VARCHAR(256) NOT NULL,
    authorization_issued_at_epoch_second BIGINT NOT NULL,
    authorization_issued_at_nano INT NOT NULL,
    authorization_not_before_epoch_second BIGINT NOT NULL,
    authorization_not_before_nano INT NOT NULL,
    authorization_expires_at_epoch_second BIGINT NOT NULL,
    authorization_expires_at_nano INT NOT NULL,
    binding_digest_version VARCHAR(32) NOT NULL,
    binding_digest VARCHAR(64) NOT NULL,
    authorization_binding_digest_version VARCHAR(32) NOT NULL,
    authorization_binding_digest VARCHAR(64) NOT NULL,
    receipt_state VARCHAR(32) NOT NULL,
    effect_attempt_id VARCHAR(64) NULL,
    opaque_result_reference VARCHAR(320) NULL,
    safe_code VARCHAR(128) NULL,
    prepared_at DATETIME(6) NOT NULL,
    effect_started_at DATETIME(6) NULL,
    result_recorded_at DATETIME(6) NULL,
    ambiguous_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL,
    PRIMARY KEY (receipt_id),
    UNIQUE KEY uk_cor_client_request (client_request_id),
    UNIQUE KEY uk_cor_effect_attempt (effect_attempt_id),
    CONSTRAINT chk_cor_command_kind CHECK (
        command_kind IN (
            'CREATE', 'TERMINATE', 'RESUME', 'RECONNECT', 'RESYNC',
            'APPROVAL_RESUME', 'RUNTIME_RECOVERY'
        )
    ),
    CONSTRAINT chk_cor_command_ingress CHECK (
        command_ingress IN ('A2A', 'DIRECT', 'OPENAPI', 'SHARED', 'SYSTEM_RECOVERY')
    ),
    CONSTRAINT chk_cor_target_kind CHECK (
        target_kind IN ('LOGICAL_AGENT', 'TASK', 'SESSION', 'APPROVAL', 'RUNTIME')
    ),
    CONSTRAINT chk_cor_actor_shape CHECK (
        (
            actor_kind = 'AUTHENTICATED_PRINCIPAL'
            AND principal_type IS NOT NULL
            AND principal_type <> 'UNKNOWN'
            AND credential_lane IS NOT NULL
            AND credential_lane <> 'UNKNOWN'
            AND principal_fingerprint IS NOT NULL
            AND server_process_authority_reference IS NULL
        ) OR (
            actor_kind = 'SERVER_PROCESS'
            AND principal_type IS NULL
            AND credential_lane IS NULL
            AND principal_fingerprint IS NULL
            AND server_process_authority_reference IS NOT NULL
        )
    ),
    CONSTRAINT chk_cor_auth_correlation CHECK (
        authorization_correlation_id = correlation_id
    ),
    CONSTRAINT chk_cor_auth_time CHECK (
        authorization_issued_at_nano BETWEEN 0 AND 999999999
        AND authorization_not_before_nano BETWEEN 0 AND 999999999
        AND authorization_expires_at_nano BETWEEN 0 AND 999999999
        AND (
            authorization_issued_at_epoch_second < authorization_not_before_epoch_second
            OR (
                authorization_issued_at_epoch_second = authorization_not_before_epoch_second
                AND authorization_issued_at_nano <= authorization_not_before_nano
            )
        )
        AND (
            authorization_not_before_epoch_second < authorization_expires_at_epoch_second
            OR (
                authorization_not_before_epoch_second = authorization_expires_at_epoch_second
                AND authorization_not_before_nano < authorization_expires_at_nano
            )
        )
    ),
    CONSTRAINT chk_cor_state_shape CHECK (
        (
            receipt_state = 'PREPARED'
            AND effect_attempt_id IS NULL
            AND opaque_result_reference IS NULL
            AND safe_code IS NULL
            AND effect_started_at IS NULL
            AND result_recorded_at IS NULL
            AND ambiguous_at IS NULL
        ) OR (
            receipt_state = 'EFFECT_STARTED'
            AND effect_attempt_id IS NOT NULL
            AND opaque_result_reference IS NULL
            AND safe_code IS NULL
            AND effect_started_at IS NOT NULL
            AND result_recorded_at IS NULL
            AND ambiguous_at IS NULL
        ) OR (
            receipt_state = 'RESULT_RECORDED'
            AND effect_attempt_id IS NOT NULL
            AND opaque_result_reference IS NOT NULL
            AND safe_code IS NOT NULL
            AND effect_started_at IS NOT NULL
            AND result_recorded_at IS NOT NULL
            AND ambiguous_at IS NULL
        ) OR (
            receipt_state = 'AMBIGUOUS'
            AND effect_attempt_id IS NOT NULL
            AND opaque_result_reference IS NULL
            AND safe_code IS NOT NULL
            AND effect_started_at IS NOT NULL
            AND result_recorded_at IS NULL
            AND ambiguous_at IS NOT NULL
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
