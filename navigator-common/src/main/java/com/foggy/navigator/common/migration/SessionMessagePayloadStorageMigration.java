package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the descriptor table for tiered session-message payload storage and
 * widens the active result-bearing columns before large final responses are
 * persisted. Production deployments must run the matching SQL migration before
 * Hibernate {@code ddl-auto=validate}; this startup migration is an idempotent
 * safety net for already-running MySQL installations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionMessagePayloadStorageMigration implements DatabaseStartupMigration {

    private static final String PAYLOAD_TABLE = "session_message_payloads";
    private static final List<MediumTextColumn> MEDIUMTEXT_COLUMNS = List.of(
            new MediumTextColumn("session_messages", "content", "content"),
            new MediumTextColumn("session_messages", "metadata", "metadata"),
            new MediumTextColumn("session_tasks", "result_text", "resultText"),
            new MediumTextColumn("codex_tasks", "result_text", "resultText"),
            new MediumTextColumn("claude_tasks", "result_text", "resultText"),
            new MediumTextColumn("gemini_tasks", "result_text", "resultText"),
            new MediumTextColumn("langgraph_tasks", "result_text", "resultText"),
            // LangGraph returns business final output in both resultText and,
            // for structured executions, structuredOutput. Neither may be
            // silently truncated below the documented final-reply capacity.
            new MediumTextColumn("langgraph_tasks", "structured_output", "structuredOutput")
    );

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationSupport migrationSupport;

    @Override
    public String id() {
        return "startup-004-session-message-payload-storage";
    }

    @Override
    public String description() {
        return "Create session message payload descriptors and widen active result columns";
    }

    @Override
    public void migrate() {
        ensurePayloadTable();
        ensurePayloadIndexes();
        MEDIUMTEXT_COLUMNS.forEach(this::ensureMediumText);
    }

    private void ensurePayloadTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS session_message_payloads (
                    id VARCHAR(64) NOT NULL,
                    message_id VARCHAR(64) NOT NULL,
                    session_id VARCHAR(64) NOT NULL,
                    backend VARCHAR(32) NOT NULL,
                    storage_key VARCHAR(512) NULL,
                    content_type VARCHAR(128) NOT NULL,
                    content_encoding VARCHAR(32) NOT NULL,
                    original_bytes BIGINT NOT NULL,
                    stored_bytes BIGINT NULL,
                    sha256 VARCHAR(64) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    expires_at DATETIME(6) NULL,
                    version BIGINT NOT NULL,
                    created_at DATETIME(6) NOT NULL,
                    updated_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
    }

    private void ensurePayloadIndexes() {
        ensureIndex("uk_smp_message_id", "UNIQUE INDEX", "(message_id)");
        ensureIndex("idx_smp_session_id", "INDEX", "(session_id)");
        ensureIndex("idx_smp_status_expires_at", "INDEX", "(status, expires_at)");
        ensureIndex("idx_smp_expires_at", "INDEX", "(expires_at)");
    }

    private void ensureIndex(String indexName, String indexKind, String columns) {
        if (migrationSupport.indexExists(PAYLOAD_TABLE, indexName)) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(PAYLOAD_TABLE)
                + " ADD " + indexKind + " " + migrationSupport.quoteIdentifier(indexName) + " " + columns);
        log.info("Created {} on {}", indexName, PAYLOAD_TABLE);
    }

    private void ensureMediumText(MediumTextColumn target) {
        if (!migrationSupport.tableExists(target.table())) {
            return;
        }

        String actualColumn = migrationSupport.findColumn(target.table(), target.preferredName(), target.legacyName())
                .orElse(null);
        if (actualColumn == null || isAtLeastMediumText(target.table(), actualColumn)) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(target.table())
                + " MODIFY COLUMN " + migrationSupport.quoteIdentifier(actualColumn) + " MEDIUMTEXT NULL");
        log.info("Widened {}.{} to MEDIUMTEXT", target.table(), actualColumn);
    }

    private boolean isAtLeastMediumText(String table, String column) {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, String.class, table, column);
        return "mediumtext".equalsIgnoreCase(dataType)
                || "longtext".equalsIgnoreCase(dataType);
    }

    private record MediumTextColumn(String table, String preferredName, String legacyName) {
    }
}
