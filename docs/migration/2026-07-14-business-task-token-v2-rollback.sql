-- Rollback for 2026-07-14-business-task-token-v2.sql.
-- Stop external task entry points and Worker Gateway traffic first.
-- Back up the table, apply this script while the v2 schema is still present,
-- then deploy the older application code. Do not restart the older application
-- until this script has completed.
--
-- Revoking active capabilities before dropping v2 scope columns prevents an
-- older application from accepting a formerly scoped v2 token as a broad token.

UPDATE business_task_scoped_token
   SET status = 'REVOKED',
       revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP(6)),
       revoked_by = COALESCE(revoked_by, 'migration-rollback'),
       revoke_reason = COALESCE(revoke_reason, 'task token v2 rollback')
 WHERE status = 'ACTIVE';

ALTER TABLE business_task_scoped_token
    DROP COLUMN revoke_reason,
    DROP COLUMN revoked_by,
    DROP COLUMN revoked_at,
    DROP COLUMN issued_at,
    DROP COLUMN worker_lease_id,
    DROP COLUMN worker_id,
    DROP COLUMN function_scope_json,
    DROP COLUMN identity_assurance,
    DROP COLUMN audience,
    DROP COLUMN generation,
    DROP COLUMN token_version,
    DROP COLUMN row_version;
