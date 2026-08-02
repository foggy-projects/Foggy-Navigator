-- A definitive pre-effect rejection has no provider task identity.  The
-- terminal tombstone must retain that fact rather than inventing one.
-- MODIFY is intentionally reapply-safe on MySQL 8.
ALTER TABLE task_terminal_tombstones
    MODIFY COLUMN provider_task_id VARCHAR(128) NULL;

CREATE TABLE IF NOT EXISTS task_terminal_cleanup_repairs (
    task_id VARCHAR(64) NOT NULL PRIMARY KEY,
    client_request_id VARCHAR(96) NOT NULL,
    repair_accepted BOOLEAN NOT NULL,
    terminal_tombstone_present BOOLEAN NOT NULL,
    cleanup_complete BOOLEAN NOT NULL,
    safe_reason_code VARCHAR(96) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_ttcr_client_request (client_request_id)
) ENGINE=InnoDB;
