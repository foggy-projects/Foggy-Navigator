-- ============================================================
-- 迁移脚本：claude_tasks → session_tasks
-- 日期：2026-03-25
-- 需求：#25 Session 存储统一化 Phase 3
-- 幂等设计：可安全多次执行
-- ============================================================

-- 1. 将 claude_tasks 数据插入 session_tasks（跳过已存在的 task_id）
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
    ct.task_id,
    ct.session_id,
    ct.user_id,
    s.tenant_id,                          -- ClaudeTaskEntity 无 tenant_id，从 sessions 表获取
    'claude-worker',                       -- agent_id = provider type
    'claude-worker',                       -- provider_type
    ct.worker_task_id,                     -- provider_task_id
    ct.worker_id,
    ct.directory_id,
    ct.prompt,
    ct.cwd,
    ct.status,
    ct.model,
    ct.source,
    ct.input_tokens,
    ct.output_tokens,
    ct.cost_usd,
    ct.duration_ms,
    ct.num_turns,
    ct.result_text,
    ct.error_message,
    ct.last_acked_seq,
    ct.last_alive_at,
    JSON_OBJECT(
        'claudeSessionId', ct.claude_session_id,
        'contextId', ct.context_id,
        'dedupKey', ct.dedup_key,
        'agentTeamsConfigId', ct.agent_teams_config_id,
        'fileCheckpointingEnabled', ct.file_checkpointing_enabled,
        'checkpoints', ct.checkpoints
    ),
    ct.created_at,
    ct.updated_at
FROM claude_tasks ct
LEFT JOIN sessions s ON s.id = ct.session_id
WHERE ct.task_id NOT IN (SELECT task_id FROM session_tasks);

-- 2. 输出迁移结果统计
SELECT
    'claude_tasks → session_tasks' AS migration,
    (SELECT COUNT(*) FROM claude_tasks) AS source_total,
    (SELECT COUNT(*) FROM session_tasks WHERE provider_type = 'claude-worker') AS migrated_total;
