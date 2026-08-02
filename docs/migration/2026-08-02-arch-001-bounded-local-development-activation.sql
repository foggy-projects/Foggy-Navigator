-- ARCH-001-ACT-002 additive support for an explicitly bounded standard Codex
-- local-development target. Applying this migration opens no activation gate.
-- The standard codex-worker route has no scoped Codex home key; the older
-- disposable codex-biz-worker target continues to require one in server-side
-- manifest validation.

ALTER TABLE lifecycle_activation_targets
    MODIFY COLUMN codex_home_key VARCHAR(256) NULL;
