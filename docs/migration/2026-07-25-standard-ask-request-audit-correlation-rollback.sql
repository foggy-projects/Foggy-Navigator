-- Roll back only the additive STANDARD ask request correlation columns.

ALTER TABLE client_app_runtime_access_token
    DROP COLUMN client_request_id;

ALTER TABLE runtime_request_audit
    DROP COLUMN task_token_issued,
    DROP COLUMN task_created,
    DROP COLUMN admission_completed,
    DROP COLUMN standard_ask_request_received,
    DROP COLUMN runtime_token_exchange_count,
    DROP COLUMN correlation_id,
    DROP COLUMN parent_client_request_id;
