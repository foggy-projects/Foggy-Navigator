-- STANDARD ask request correlation and admission/terminal audit facts.
-- Apply before starting the launcher with spring.jpa.hibernate.ddl-auto=validate.
-- All timestamps are persisted as UTC instants; no credential, token, prompt,
-- response, Authorization/header set, workspace path, or raw request body is stored.

ALTER TABLE runtime_request_audit
    ADD COLUMN parent_client_request_id VARCHAR(36) NULL AFTER operation,
    ADD COLUMN correlation_id VARCHAR(36) NULL AFTER parent_client_request_id,
    ADD COLUMN runtime_token_exchange_count INT NULL AFTER runtime_token_issued,
    ADD COLUMN standard_ask_request_received BIT NULL AFTER runtime_token_exchange_count,
    ADD COLUMN admission_completed BIT NULL AFTER standard_ask_request_received,
    ADD COLUMN task_created BIT NULL AFTER admission_completed,
    ADD COLUMN task_token_issued BIT NULL AFTER task_created;

-- Existing audit rows keep their original request ID as the correlation root.
-- This is a structural backfill only; it does not synthesize missing request
-- stages or alter historical task/terminal evidence.
UPDATE runtime_request_audit
SET correlation_id = client_request_id
WHERE correlation_id IS NULL;

ALTER TABLE runtime_request_audit
    MODIFY COLUMN correlation_id VARCHAR(36) NOT NULL;

ALTER TABLE client_app_runtime_access_token
    ADD COLUMN client_request_id VARCHAR(36) NULL AFTER app_key;
