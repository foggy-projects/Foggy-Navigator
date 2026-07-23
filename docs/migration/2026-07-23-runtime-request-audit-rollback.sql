-- Destructive rollback for 2026-07-23-runtime-request-audit.sql.
-- Stop launcher instances and remove the runtime-audit endpoint/writers first.
-- Audit rows are deliberately short-lived; export only sanitized evidence that
-- is still operationally required before running this rollback.

DROP TABLE IF EXISTS runtime_request_audit_stage;
DROP TABLE IF EXISTS runtime_request_audit;
