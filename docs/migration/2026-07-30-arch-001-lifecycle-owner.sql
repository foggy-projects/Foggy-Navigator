-- ARCH-001 additive lifecycle-owner schema.
-- Production must pre-apply this migration before starting a binary with
-- spring.jpa.hibernate.ddl-auto=validate. No historical Task is backfilled.

CREATE TABLE IF NOT EXISTS lifecycle_facts (
    fact_id VARCHAR(96) NOT NULL PRIMARY KEY,
    fact_type VARCHAR(96) NOT NULL,
    schema_version INT NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(64) NULL,
    session_id VARCHAR(64) NULL,
    operation_id VARCHAR(64) NULL,
    physical_worker_id VARCHAR(128) NULL,
    state_generation VARCHAR(128) NULL,
    instance_epoch VARCHAR(128) NULL,
    provider_task_id VARCHAR(128) NULL,
    dispatch_id VARCHAR(96) NULL,
    safe_binding_digest_version VARCHAR(32) NULL,
    safe_binding_digest VARCHAR(128) NULL,
    ownership_mode VARCHAR(16) NOT NULL,
    source_sequence BIGINT NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    safe_reason_code VARCHAR(96) NULL,
    content_free_payload_json TEXT NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_lf_idempotency (idempotency_key),
    KEY idx_lf_aggregate_cursor (aggregate_type, aggregate_id, source_sequence),
    KEY idx_lf_task (task_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS worker_lifecycle_snapshots (
    physical_worker_id VARCHAR(128) NOT NULL PRIMARY KEY,
    ownership_mode VARCHAR(16) NOT NULL,
    state_generation VARCHAR(128) NULL,
    instance_epoch VARCHAR(128) NULL,
    availability VARCHAR(32) NOT NULL,
    conflict_state VARCHAR(48) NOT NULL,
    fact_cursor BIGINT NOT NULL,
    policy_version VARCHAR(48) NOT NULL,
    writer_generation_id VARCHAR(96) NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_lifecycle_snapshots (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    session_id VARCHAR(64) NULL,
    physical_worker_id VARCHAR(128) NULL,
    state_generation VARCHAR(128) NULL,
    instance_epoch VARCHAR(128) NULL,
    provider_task_id VARCHAR(128) NULL,
    dispatch_id VARCHAR(96) NULL,
    operation_id VARCHAR(64) NULL,
    safe_binding_digest_version VARCHAR(32) NULL,
    safe_binding_digest VARCHAR(128) NULL,
    ownership_mode VARCHAR(16) NOT NULL,
    canonical_phase VARCHAR(16) NOT NULL,
    terminal_outcome VARCHAR(32) NULL,
    terminal_source VARCHAR(48) NULL,
    availability VARCHAR(32) NOT NULL,
    conflict_state VARCHAR(48) NOT NULL,
    cleanup_state VARCHAR(24) NOT NULL,
    fact_cursor BIGINT NOT NULL,
    policy_version VARCHAR(48) NOT NULL,
    writer_generation_id VARCHAR(96) NULL,
    snapshot_json MEDIUMTEXT NOT NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS session_lifecycle_snapshots (
    session_id VARCHAR(64) NOT NULL PRIMARY KEY,
    physical_worker_id VARCHAR(128) NULL,
    ownership_mode VARCHAR(16) NOT NULL,
    canonical_phase VARCHAR(16) NOT NULL,
    foreground_task_id VARCHAR(64) NULL,
    foreground_lane_state VARCHAR(24) NOT NULL,
    availability VARCHAR(32) NOT NULL,
    conflict_state VARCHAR(48) NOT NULL,
    writer_generation_id VARCHAR(96) NULL,
    row_version BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    KEY idx_sls_foreground_task (foreground_task_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lifecycle_effect_outbox (
    effect_id VARCHAR(96) NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    physical_worker_id VARCHAR(128) NULL,
    provider_type VARCHAR(32) NULL,
    provider_task_id VARCHAR(128) NULL,
    dispatch_id VARCHAR(96) NULL,
    operation_id VARCHAR(64) NULL,
    ownership_mode VARCHAR(16) NULL,
    state_generation VARCHAR(128) NULL,
    instance_epoch VARCHAR(128) NULL,
    binding_digest_version VARCHAR(32) NULL,
    binding_digest VARCHAR(128) NULL,
    effect_claim VARCHAR(64) NULL,
    aggregate_reference_id VARCHAR(160) NULL,
    writer_generation_id VARCHAR(96) NULL,
    controller_inventory_digest VARCHAR(128) NULL,
    effect_type VARCHAR(64) NOT NULL,
    effect_class VARCHAR(32) NOT NULL,
    effect_state VARCHAR(24) NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    proof_id VARCHAR(96) NULL,
    effect_authorization_proof_version VARCHAR(96) NULL,
    authorized_at DATETIME(6) NULL,
    content_free_payload_json TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    row_version BIGINT NOT NULL,
    UNIQUE KEY uk_leo_idempotency (idempotency_key),
    KEY idx_leo_state (effect_state)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_terminal_tombstones (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    tenant_id VARCHAR(64) NULL,
    provider_task_id VARCHAR(128) NULL,
    provider_task_user_id VARCHAR(64) NULL,
    source_agent_id VARCHAR(64) NULL,
    operation_id VARCHAR(64) NULL,
    client_request_id VARCHAR(96) NULL,
    terminal_outcome VARCHAR(32) NOT NULL,
    terminal_source VARCHAR(48) NOT NULL,
    terminal_fact_id VARCHAR(96) NOT NULL,
    writer_generation_id VARCHAR(96) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ttt_terminal_fact (terminal_fact_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS task_terminal_cleanup_plan (
    task_id VARCHAR(64) NOT NULL,
    participant VARCHAR(48) NOT NULL,
    applicability VARCHAR(24) NOT NULL,
    not_applicable_reason VARCHAR(96) NULL,
    checkpoint_state VARCHAR(24) NOT NULL,
    checkpoint_fact_id VARCHAR(96) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (task_id, participant)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lifecycle_writer_generations (
    generation_id VARCHAR(96) NOT NULL PRIMARY KEY,
    minimum_owner_protocol INT NOT NULL,
    target_commit VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    activated_at DATETIME(6) NULL,
    row_version BIGINT NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lifecycle_writer_instance_registrations (
    instance_id VARCHAR(128) NOT NULL PRIMARY KEY,
    generation_id VARCHAR(96) NOT NULL,
    owner_protocol INT NOT NULL,
    target_commit VARCHAR(64) NOT NULL,
    last_heartbeat_at DATETIME(6) NOT NULL,
    KEY idx_lwir_generation (generation_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lifecycle_writer_exclusivity_proofs (
    proof_id VARCHAR(96) NOT NULL PRIMARY KEY,
    generation_id VARCHAR(96) NOT NULL,
    controller_inventory_digest VARCHAR(128) NOT NULL,
    holder_instance_id VARCHAR(128) NOT NULL,
    proof_version BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    acquired_at DATETIME(6) NOT NULL,
    last_verified_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    quarantine_cursor VARCHAR(160) NULL,
    row_version BIGINT NOT NULL,
    KEY idx_lwep_generation_status (generation_id, status)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS lifecycle_writer_exclusivity_references (
    reference_id VARCHAR(160) NOT NULL PRIMARY KEY,
    proof_id VARCHAR(96) NOT NULL,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    acquired_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    release_reason VARCHAR(96) NULL,
    UNIQUE KEY uk_lwer_active (proof_id, aggregate_type, aggregate_id),
    KEY idx_lwer_proof_release (proof_id, released_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS worker_lifecycle_sentinel_leases (
    physical_worker_id VARCHAR(128) NOT NULL PRIMARY KEY,
    holder_instance_id VARCHAR(128) NOT NULL,
    fence_token BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB;
