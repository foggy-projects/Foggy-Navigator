-- ============================================================
-- 迁移脚本：codex_tasks → session_tasks
-- 日期：2026-03-25
-- 需求：#25 Session 存储统一化 Phase 3
-- 幂等设计：可安全多次执行
-- ============================================================

INSERT INTO session_tasks (
    task_id, session_id, user_id, tenant_id, agent_id,
    provider_type, provider_task_id,
    worker_id, directory_id, prompt, cwd, status, model, source,
    input_tokens, output_tokens, cost_usd, duration_ms, num_turns,
    result_text, error_message, last_acked_seq, last_alive_at,
    task_state_json,
    created_at, updated_at
)
SELECT
    cx.task_id,
    cx.session_id,
    cx.user_id,
    cx.tenant_id,                          -- CodexTaskEntity 自带 tenant_id
    'codex-worker',                        -- agent_id
    'codex-worker',                        -- provider_type
    cx.worker_task_id,                     -- provider_task_id
    cx.worker_id,
    cx.directory_id,
    cx.prompt,
    cx.cwd,
    cx.status,
    cx.model,
    cx.source,
    cx.input_tokens,
    cx.output_tokens,
    cx.cost_usd,
    cx.duration_ms,
    cx.num_turns,
    cx.result_text,
    cx.error_message,
    cx.last_acked_seq,
    cx.last_alive_at,
    JSON_OBJECT('codexThreadId', cx.codex_thread_id),
    cx.created_at,
    cx.updated_at
FROM codex_tasks cx
WHERE cx.task_id NOT IN (SELECT task_id FROM session_tasks);

SELECT
    'codex_tasks → session_tasks' AS migration,
    (SELECT COUNT(*) FROM codex_tasks) AS source_total,
    (SELECT COUNT(*) FROM session_tasks WHERE provider_type = 'codex-worker') AS migrated_total;
