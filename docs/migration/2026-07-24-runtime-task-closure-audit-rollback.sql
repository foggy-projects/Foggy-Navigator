-- Roll back only the additive runtime task closure audit columns.

ALTER TABLE runtime_request_audit
    DROP COLUMN recovery_count,
    DROP COLUMN retry_count,
    DROP COLUMN dispatch_count,
    DROP COLUMN business_function_dispatched,
    DROP COLUMN model_dispatched,
    DROP COLUMN requested_function_count,
    DROP COLUMN requested_tool_count,
    DROP COLUMN model_variant,
    DROP COLUMN model_config_id,
    DROP COLUMN physical_worker_id;
