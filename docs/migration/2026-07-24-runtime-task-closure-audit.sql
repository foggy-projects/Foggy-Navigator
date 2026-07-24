-- Runtime-only STANDARD task admission, termination, and reconciliation audit facts.
-- Apply before starting the launcher with spring.jpa.hibernate.ddl-auto=validate.
-- This migration stores only sanitized identifiers, counters, booleans, and scope facts.

ALTER TABLE runtime_request_audit
    ADD COLUMN physical_worker_id VARCHAR(128) NULL AFTER task_id,
    ADD COLUMN model_config_id VARCHAR(128) NULL AFTER physical_worker_id,
    ADD COLUMN model_variant VARCHAR(255) NULL AFTER model_config_id,
    ADD COLUMN requested_tool_count INT NULL AFTER status,
    ADD COLUMN requested_function_count INT NULL AFTER tool_scope_source,
    ADD COLUMN model_dispatched BIT NULL AFTER runtime_dispatched,
    ADD COLUMN business_function_dispatched BIT NULL AFTER model_dispatched,
    ADD COLUMN dispatch_count INT NULL AFTER business_function_dispatched,
    ADD COLUMN retry_count INT NULL AFTER dispatch_count,
    ADD COLUMN recovery_count INT NULL AFTER retry_count;
