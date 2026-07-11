-- Split editable Codex App Server endpoint profiles from immutable runtime revisions.
--
-- Production order: apply this script after
-- 2026-07-11-codex-runtime-archive.sql and before deploying a launcher using
-- ddl-auto=validate. Development environments using ddl-auto=update create the
-- same schema automatically.

CREATE TABLE codex_app_server_endpoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    endpoint_id VARCHAR(48) NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    endpoint_url VARCHAR(512) NOT NULL,
    auth_token_ciphertext TEXT NOT NULL,
    configuration_version BIGINT NOT NULL DEFAULT 1,
    last_sync_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    last_sync_message TEXT NULL,
    last_synced_at DATETIME(6) NULL,
    last_runtime_id VARCHAR(64) NULL,
    last_runtime_revision INT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_case_endpoint_id (endpoint_id),
    KEY idx_case_worker (worker_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE codex_runtime_revisions
    ADD COLUMN endpoint_id VARCHAR(48) NULL AFTER auth_token_ciphertext,
    ADD COLUMN runtime_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' AFTER endpoint_id,
    ADD COLUMN reported_runtime_id VARCHAR(64) NULL AFTER runtime_source,
    ADD COLUMN reported_runtime_revision INT NULL AFTER reported_runtime_id,
    ADD COLUMN capability_fingerprint VARCHAR(64) NULL AFTER reported_runtime_revision,
    ADD KEY idx_crr_endpoint (endpoint_id);
