package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Widens Claude checkpoint storage so durable Worker replay is not blocked
 * when a long-running task accumulates more data than a MySQL TEXT column can
 * hold.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCheckpointStorageMigration implements DatabaseStartupMigration {

    private static final String TASK_TABLE = "claude_tasks";
    private static final String CHECKPOINT_COLUMN = "checkpoints";

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationSupport migrationSupport;

    @Override
    public String id() {
        return "startup-007-claude-checkpoint-storage";
    }

    @Override
    public String description() {
        return "Widen Claude checkpoint storage for durable replay";
    }

    @Override
    public void migrate() {
        if (!migrationSupport.tableExists(TASK_TABLE)) {
            return;
        }

        String actualColumn = migrationSupport.findColumn(TASK_TABLE, CHECKPOINT_COLUMN)
                .orElse(null);
        if (actualColumn == null || isAtLeastMediumText(actualColumn)) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(TASK_TABLE)
                + " MODIFY COLUMN " + migrationSupport.quoteIdentifier(actualColumn) + " MEDIUMTEXT NULL");
        log.info("Widened {}.{} to MEDIUMTEXT", TASK_TABLE, actualColumn);
    }

    private boolean isAtLeastMediumText(String column) {
        String dataType = jdbcTemplate.queryForObject("""
                SELECT DATA_TYPE
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, String.class, TASK_TABLE, column);
        return "mediumtext".equalsIgnoreCase(dataType)
                || "longtext".equalsIgnoreCase(dataType);
    }
}
