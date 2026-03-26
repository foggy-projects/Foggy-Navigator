-- ============================================================
-- 迁移脚本：deleted_claude_sessions → sessions.deleted_at 软删除
-- 日期：2026-03-25
-- 需求：#25 Session 存储统一化 Phase 3
-- 幂等设计：仅更新 deleted_at 为 NULL 的记录
-- ============================================================

-- 方法1：通过 provider_state_json 中的 claudeSessionId 匹配
UPDATE sessions s
INNER JOIN deleted_claude_sessions dcs
    ON JSON_UNQUOTE(JSON_EXTRACT(s.provider_state_json, '$.claudeSessionId')) = dcs.claude_session_id
SET s.deleted_at = dcs.deleted_at
WHERE s.deleted_at IS NULL;

-- 方法2：对于尚未在 sessions 表中有记录的已删除会话，
-- 创建一个标记为已删除的 session（防止 sync 时重新导入）
INSERT INTO sessions (
    id, user_id, provider_type, status, deleted_at,
    current_worker_id, provider_state_json,
    created_at, updated_at
)
SELECT
    CONCAT('deleted-', dcs.claude_session_id),
    dcs.user_id,
    'claude-worker',
    'DELETED',
    dcs.deleted_at,
    dcs.worker_id,
    JSON_OBJECT('claudeSessionId', dcs.claude_session_id),
    dcs.deleted_at,
    dcs.deleted_at
FROM deleted_claude_sessions dcs
WHERE dcs.claude_session_id NOT IN (
    SELECT JSON_UNQUOTE(JSON_EXTRACT(provider_state_json, '$.claudeSessionId'))
    FROM sessions
    WHERE provider_state_json IS NOT NULL
      AND JSON_EXTRACT(provider_state_json, '$.claudeSessionId') IS NOT NULL
);

SELECT
    'deleted_sessions → sessions.deleted_at' AS migration,
    (SELECT COUNT(*) FROM deleted_claude_sessions) AS source_total,
    (SELECT COUNT(*) FROM sessions WHERE deleted_at IS NOT NULL) AS soft_deleted_total;
