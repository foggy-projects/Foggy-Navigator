-- ============================================================
-- Migration: provider-native subtask latest-state projection
-- Date: 2026-07-10
-- Purpose: persist Codex Ultra child-thread state for UI recovery
--
-- This table is not a Navigator Agent, Session, or Task table. It stores only
-- the latest sanitized state per provider child thread; chat/event history is
-- intentionally not persisted here.
--
-- Production gate:
--   Run this script before starting a build that contains NativeSubtaskStateEntity.
--   The prod profile uses ddl-auto=validate and will fail fast when this table is
--   missing; this file is an explicit pre-deploy migration, not a startup script.
-- ============================================================

CREATE TABLE IF NOT EXISTS native_subtask_states (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(64) NOT NULL,
    provider_type VARCHAR(32) NOT NULL,
    subtask_id VARCHAR(128) NOT NULL,
    parent_subtask_id VARCHAR(128) NULL,
    depth INT NOT NULL,
    label VARCHAR(255) NULL,
    role VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    activity VARCHAR(64) NULL,
    message VARCHAR(64) NULL,
    duration_ms BIGINT NULL,
    contract_version INT NOT NULL,
    last_event_seq INT NOT NULL,
    started_at DATETIME(6) NULL,
    event_updated_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_native_subtask_task_child (task_id, subtask_id),
    KEY idx_native_subtask_task (task_id),
    KEY idx_native_subtask_session (session_id),
    KEY idx_native_subtask_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT COUNT(*) AS native_subtask_state_count
FROM native_subtask_states;
