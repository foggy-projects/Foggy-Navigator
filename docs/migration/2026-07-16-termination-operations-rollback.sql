-- Destructive rollback, not an operational undo. Stop new termination
-- requests, retain/export the audit records according to deployment policy,
-- and obtain explicit approval before dropping this table.
DROP TABLE IF EXISTS termination_operations;
