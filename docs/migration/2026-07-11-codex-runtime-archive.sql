-- Add reversible archive state to immutable Codex runtime revisions.
-- Apply after 2026-07-10-codex-runtime-affinity.sql and before deploying
-- a launcher that maps CodexRuntimeEntity.archivedAt with ddl-auto=validate.

ALTER TABLE codex_runtime_revisions
    ADD COLUMN archived_at DATETIME(6) NULL AFTER last_capability_at;
