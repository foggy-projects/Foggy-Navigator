-- Persist the immutable Codex task provider used by command and reconnect routing.
-- Apply after 2026-07-12-codex-app-server-endpoints.sql and before deploying a
-- launcher that validates CodexTaskEntity.providerType.

ALTER TABLE codex_tasks
    ADD COLUMN provider_type VARCHAR(32) NULL AFTER worker_id;

UPDATE codex_tasks ct
LEFT JOIN session_tasks st ON st.task_id = ct.task_id
   SET ct.provider_type = CASE
       WHEN ct.runtime_type = 'APP_SERVER' THEN 'codex-app-server-worker'
       WHEN st.provider_type = 'codex-biz-worker' THEN 'codex-biz-worker'
       ELSE 'codex-worker'
   END
 WHERE ct.provider_type IS NULL OR ct.provider_type = '';

-- Keep the unified task/session projections on the same immutable command
-- provider. Existing SDK Biz tasks remain Biz; only historical App Server
-- tasks move from the former shared codex-worker identity.
UPDATE session_tasks st
JOIN codex_tasks ct ON ct.task_id = st.task_id
   SET st.provider_type = ct.provider_type
 WHERE st.provider_type IN ('codex-worker', 'codex-biz-worker')
   AND st.provider_type <> ct.provider_type;

UPDATE sessions s
JOIN (
    SELECT st.session_id, st.provider_type
      FROM session_tasks st
      JOIN (
          SELECT session_id, MAX(id) AS latest_id
            FROM session_tasks
           GROUP BY session_id
      ) latest ON latest.latest_id = st.id
) latest_task ON latest_task.session_id = s.id
   SET s.provider_type = latest_task.provider_type,
       s.provider_state_json = CASE
           WHEN JSON_VALID(s.provider_state_json)
                AND JSON_TYPE(s.provider_state_json) = 'OBJECT'
               THEN JSON_SET(s.provider_state_json, '$.providerType', latest_task.provider_type)
           ELSE s.provider_state_json
       END
 WHERE s.provider_type IN ('codex-worker', 'codex-biz-worker')
   AND latest_task.provider_type IN (
       'codex-worker', 'codex-app-server-worker', 'codex-biz-worker'
   );

ALTER TABLE codex_tasks
    MODIFY COLUMN provider_type VARCHAR(32) NOT NULL,
    ADD KEY idx_cxt_provider_status (provider_type, status);
