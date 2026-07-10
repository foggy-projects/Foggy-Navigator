-- ============================================================
-- Migration: Codex runtime registry and immutable task/session affinity
-- Date: 2026-07-10
-- Production order: DB -> Java control plane -> app-server Worker -> routing
-- This is a one-time expand/backfill migration for prod ddl-auto=validate.
-- ============================================================

CREATE TABLE IF NOT EXISTS codex_runtime_revisions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    runtime_id VARCHAR(64) NOT NULL,
    revision INT NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    runtime_type VARCHAR(32) NOT NULL,
    endpoint_url VARCHAR(512) NOT NULL,
    auth_token_ciphertext TEXT NOT NULL,
    instance_id VARCHAR(128) NULL,
    enabled BIT(1) NOT NULL DEFAULT b'0',
    routing_policy VARCHAR(32) NOT NULL DEFAULT 'DARK',
    rollout_percentage INT NOT NULL DEFAULT 0,
    priority INT NOT NULL DEFAULT 0,
    routing_epoch BIGINT NOT NULL DEFAULT 1,
    readiness_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    contract_version VARCHAR(32) NULL,
    cli_version VARCHAR(64) NULL,
    schema_digest VARCHAR(128) NULL,
    expected_cli_version VARCHAR(64) NOT NULL DEFAULT '0.144.1',
    expected_schema_digest VARCHAR(128) NOT NULL,
    capability_manifest_json TEXT NULL,
    readiness_message TEXT NULL,
    last_capability_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_crr_runtime_revision (runtime_id, revision),
    KEY idx_crr_worker_type (worker_id, runtime_type),
    KEY idx_crr_routing (enabled, readiness_status, routing_policy)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE codex_tasks
    ADD COLUMN runtime_id VARCHAR(128) NULL AFTER worker_task_id,
    ADD COLUMN runtime_revision INT NULL AFTER runtime_id,
    ADD COLUMN runtime_type VARCHAR(32) NULL AFTER runtime_revision,
    ADD COLUMN runtime_instance_id VARCHAR(128) NULL AFTER runtime_type,
    ADD COLUMN routing_epoch BIGINT NULL AFTER runtime_instance_id,
    ADD COLUMN runtime_acceptance_state VARCHAR(32) NULL AFTER routing_epoch,
    ADD COLUMN runtime_request_hash VARCHAR(64) NULL AFTER runtime_acceptance_state,
    ADD COLUMN runtime_request_ciphertext LONGTEXT NULL AFTER runtime_request_hash,
    ADD KEY idx_cxt_runtime_affinity (runtime_id, runtime_revision);

-- Existing tasks/sessions must never be silently moved by a later rollout.
UPDATE codex_tasks
SET runtime_id = CONCAT('legacy-sdk:', worker_id),
    runtime_revision = 1,
    runtime_type = 'SDK_EXEC',
    routing_epoch = 0
WHERE runtime_id IS NULL OR runtime_type IS NULL;

UPDATE sessions s
JOIN (
    SELECT ct.session_id, ct.worker_id
    FROM codex_tasks ct
    JOIN (
        SELECT session_id, MAX(id) AS latest_id
        FROM codex_tasks
        WHERE session_id IS NOT NULL
        GROUP BY session_id
    ) latest ON latest.latest_id = ct.id
) legacy ON legacy.session_id = s.id
SET s.provider_state_json = JSON_SET(
        CASE
            WHEN JSON_VALID(s.provider_state_json)
                 AND JSON_TYPE(s.provider_state_json) = 'OBJECT'
                THEN s.provider_state_json
            ELSE JSON_OBJECT()
        END,
        '$.codexRuntimeId', CONCAT('legacy-sdk:', legacy.worker_id),
        '$.codexRuntimeRevision', 1,
        '$.codexRuntimeType', 'SDK_EXEC',
        '$.codexRoutingEpoch', 0
    )
WHERE s.provider_type IN ('codex-worker', 'codex-biz-worker')
  AND JSON_EXTRACT(
      CASE
          WHEN JSON_VALID(s.provider_state_json)
               AND JSON_TYPE(s.provider_state_json) = 'OBJECT'
              THEN s.provider_state_json
          ELSE JSON_OBJECT()
      END,
      '$.codexRuntimeId'
  ) IS NULL;

SELECT runtime_type, COUNT(*) AS task_count
FROM codex_tasks
GROUP BY runtime_type;

SELECT readiness_status, routing_policy, COUNT(*) AS runtime_revision_count
FROM codex_runtime_revisions
GROUP BY readiness_status, routing_policy;
