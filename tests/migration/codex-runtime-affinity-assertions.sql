DELIMITER //

DROP PROCEDURE IF EXISTS assert_true//
CREATE PROCEDURE assert_true(IN condition_value BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_value IS NULL OR condition_value = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END//

DELIMITER ;

CALL assert_true(
    (SELECT COUNT(*) = 11
       FROM sessions
      WHERE provider_type IN ('codex-worker', 'codex-biz-worker')
        AND JSON_VALID(provider_state_json)
        AND JSON_TYPE(provider_state_json) = 'OBJECT'),
    'all Codex provider states must be JSON objects'
);

CALL assert_true(
    (SELECT COUNT(*) = 7
       FROM sessions
      WHERE id IN ('sql-null', 'blank', 'invalid', 'json-null',
                   'string-scalar', 'number-scalar', 'array-value')
        AND JSON_LENGTH(provider_state_json) = 4
        AND JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeType')) = 'SDK_EXEC'),
    'non-object provider states must be replaced, not reused'
);

CALL assert_true(
    (SELECT JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.preserved')) = 'yes'
            AND JSON_EXTRACT(provider_state_json, '$.nested.value') = 7
            AND JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId')) = 'legacy-sdk:new-object'
       FROM sessions
      WHERE id = 'object-value'),
    'object fields must survive while runtime affinity is added'
);

CALL assert_true(
    (SELECT JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId')) = 'already-bound'
            AND JSON_EXTRACT(provider_state_json, '$.codexRuntimeRevision') = 9
            AND JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeType')) = 'APP_SERVER'
            AND JSON_EXTRACT(provider_state_json, '$.codexRoutingEpoch') = 777
       FROM sessions
      WHERE id = 'existing-binding'),
    'existing runtime affinity must remain immutable'
);

CALL assert_true(
    (SELECT JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId')) = 'legacy-sdk:aaaa-latest-by-id'
       FROM sessions
      WHERE id = 'latest-worker'),
    'session affinity must use the worker from MAX(codex_tasks.id)'
);

CALL assert_true(
    (SELECT CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId'))) = 75
            AND JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId')) =
                CONCAT('legacy-sdk:', REPEAT('w', 64))
       FROM sessions
      WHERE id = 'max-worker-id'),
    '64-character worker id must produce an untruncated 75-character runtime id'
);

CALL assert_true(
    (SELECT provider_state_json = '{"preserved":"unchanged"}'
       FROM sessions
      WHERE id = 'other-provider'),
    'non-Codex provider state must remain untouched'
);

CALL assert_true(
    (SELECT COUNT(*) = 24
       FROM codex_tasks
      WHERE runtime_id = CONCAT('legacy-sdk:', worker_id)
        AND runtime_revision = 1
        AND runtime_type = 'SDK_EXEC'
        AND routing_epoch = 0),
    'every legacy task must retain its own worker affinity'
);

CALL assert_true(
    (SELECT CHARACTER_MAXIMUM_LENGTH = 128
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'codex_tasks'
        AND column_name = 'runtime_id'),
    'codex_tasks.runtime_id must be VARCHAR(128)'
);

CALL assert_true(
    (SELECT DATA_TYPE = 'bigint' AND IS_NULLABLE = 'YES'
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'codex_tasks'
        AND column_name = 'created_at_epoch_ms'),
    'codex_tasks.created_at_epoch_ms must be a nullable BIGINT'
);

CALL assert_true(
    (SELECT COUNT(*) = 24
       FROM codex_tasks
      WHERE created_at_epoch_ms IS NULL),
    'legacy LocalDateTime rows must not receive an inferred epoch'
);

CALL assert_true(
    (SELECT COUNT(*) = 1
       FROM information_schema.statistics
      WHERE table_schema = DATABASE()
        AND table_name = 'codex_tasks'
        AND index_name = 'idx_cxt_runtime_affinity'
        AND seq_in_index = 1
        AND column_name = 'runtime_id'),
    'runtime affinity index must exist'
);

CALL assert_true(
    (SELECT COUNT(*) = 1
       FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name = 'codex_runtime_revisions'),
    'codex_runtime_revisions table must exist'
);

CALL assert_true(
    (SELECT DATA_TYPE = 'datetime' AND IS_NULLABLE = 'YES'
       FROM information_schema.columns
      WHERE table_schema = DATABASE()
        AND table_name = 'codex_runtime_revisions'
        AND column_name = 'archived_at'),
    'codex_runtime_revisions.archived_at must be a nullable DATETIME'
);

SELECT VERSION() AS mysql_version,
       (SELECT COUNT(*) FROM codex_tasks) AS migrated_tasks,
       (SELECT COUNT(*)
          FROM sessions
         WHERE provider_type IN ('codex-worker', 'codex-biz-worker')
           AND JSON_TYPE(provider_state_json) = 'OBJECT') AS object_provider_states,
       (SELECT CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.codexRuntimeId')))
          FROM sessions
         WHERE id = 'max-worker-id') AS max_runtime_id_length;

DROP PROCEDURE assert_true;
