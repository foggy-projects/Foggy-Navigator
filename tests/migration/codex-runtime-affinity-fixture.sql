-- Minimal pre-migration schema and data for
-- docs/migration/2026-07-10-codex-runtime-affinity.sql.

DROP TABLE IF EXISTS codex_runtime_revisions;
DROP TABLE IF EXISTS codex_tasks;
DROP TABLE IF EXISTS sessions;

CREATE TABLE sessions (
    id VARCHAR(64) NOT NULL,
    provider_type VARCHAR(32) NULL,
    provider_state_json TEXT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE codex_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    worker_task_id VARCHAR(128) NULL,
    session_id VARCHAR(64) NULL,
    worker_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO sessions (id, provider_type, provider_state_json) VALUES
    ('sql-null', 'codex-worker', NULL),
    ('blank', 'codex-biz-worker', ''),
    ('invalid', 'codex-worker', '{invalid-json'),
    ('json-null', 'codex-biz-worker', 'null'),
    ('string-scalar', 'codex-worker', '"text"'),
    ('number-scalar', 'codex-biz-worker', '42'),
    ('array-value', 'codex-worker', '["must-not-survive"]'),
    ('object-value', 'codex-biz-worker', '{"preserved":"yes","nested":{"value":7}}'),
    ('existing-binding', 'codex-worker', '{"preserved":"yes","codexRuntimeId":"already-bound","codexRuntimeRevision":9,"codexRuntimeType":"APP_SERVER","codexRoutingEpoch":777}'),
    ('latest-worker', 'codex-worker', '{}'),
    ('max-worker-id', 'codex-worker', '{}'),
    ('other-provider', 'claude-worker', '{"preserved":"unchanged"}');

-- Every session has two tasks. MAX(id), rather than creation time or lexical
-- worker order, defines the session affinity selected by the migration.
INSERT INTO codex_tasks (id, worker_task_id, session_id, worker_id) VALUES
    (1, 'old-sql-null', 'sql-null', 'old-sql-null'),
    (2, 'new-sql-null', 'sql-null', 'new-sql-null'),
    (3, 'old-blank', 'blank', 'old-blank'),
    (4, 'new-blank', 'blank', 'new-blank'),
    (5, 'old-invalid', 'invalid', 'old-invalid'),
    (6, 'new-invalid', 'invalid', 'new-invalid'),
    (7, 'old-json-null', 'json-null', 'old-json-null'),
    (8, 'new-json-null', 'json-null', 'new-json-null'),
    (9, 'old-string', 'string-scalar', 'old-string'),
    (10, 'new-string', 'string-scalar', 'new-string'),
    (11, 'old-number', 'number-scalar', 'old-number'),
    (12, 'new-number', 'number-scalar', 'new-number'),
    (13, 'old-array', 'array-value', 'old-array'),
    (14, 'new-array', 'array-value', 'new-array'),
    (15, 'old-object', 'object-value', 'old-object'),
    (16, 'new-object', 'object-value', 'new-object'),
    (17, 'old-existing', 'existing-binding', 'old-existing'),
    (18, 'new-existing', 'existing-binding', 'new-existing'),
    (19, 'old-latest', 'latest-worker', 'zzzz-lexically-later'),
    (20, 'new-latest', 'latest-worker', 'aaaa-latest-by-id'),
    (21, 'old-max', 'max-worker-id', 'short-worker'),
    (22, 'new-max', 'max-worker-id', 'wwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwwww'),
    (23, 'old-other', 'other-provider', 'old-other'),
    (24, 'new-other', 'other-provider', 'new-other');

