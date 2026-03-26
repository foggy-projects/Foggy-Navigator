-- ============================================================
-- 迁移脚本：claude_conversation_configs → sessions 表字段合并
-- 日期：2026-03-25
-- 需求：#25 Session 存储统一化 Phase 3
-- 幂等设计：仅更新 NULL 字段，不覆盖已有值
-- ============================================================

-- 将 ConversationConfig 字段合并到 SessionEntity
UPDATE sessions s
INNER JOIN claude_conversation_configs ccc ON ccc.session_id = s.id
SET
    s.pinned            = COALESCE(s.pinned, ccc.pinned),
    s.pinned_at         = COALESCE(s.pinned_at, ccc.pinned_at),
    s.title             = COALESCE(s.title, ccc.custom_title),
    s.auth_mode         = COALESCE(s.auth_mode, ccc.auth_mode),
    s.auth_token_ciphertext = COALESCE(s.auth_token_ciphertext, ccc.auth_token),
    s.auth_base_url     = COALESCE(s.auth_base_url, ccc.base_url),
    s.auth_bound_at     = COALESCE(s.auth_bound_at, ccc.auth_bound_at),
    s.tags_json         = COALESCE(s.tags_json, ccc.tags),
    s.interaction_state = COALESCE(s.interaction_state, ccc.interaction_state),
    s.current_worker_id = COALESCE(s.current_worker_id, ccc.worker_id);

-- 对于有 session 但 sessions 表中不存在的 ConversationConfig，
-- 补创建对应的 session 记录
INSERT INTO sessions (
    id, user_id, provider_type, status,
    title, pinned, pinned_at,
    auth_mode, auth_token_ciphertext, auth_base_url, auth_bound_at,
    tags_json, interaction_state, current_worker_id,
    created_at, updated_at
)
SELECT
    ccc.session_id,
    ccc.user_id,
    'claude-worker',
    'ACTIVE',
    ccc.custom_title,
    ccc.pinned,
    ccc.pinned_at,
    ccc.auth_mode,
    ccc.auth_token,
    ccc.base_url,
    ccc.auth_bound_at,
    ccc.tags,
    ccc.interaction_state,
    ccc.worker_id,
    ccc.created_at,
    ccc.updated_at
FROM claude_conversation_configs ccc
WHERE ccc.session_id NOT IN (SELECT id FROM sessions);

SELECT
    'conversation_configs → sessions' AS migration,
    (SELECT COUNT(*) FROM claude_conversation_configs) AS source_total,
    (SELECT COUNT(*) FROM sessions WHERE provider_type = 'claude-worker') AS sessions_total;
