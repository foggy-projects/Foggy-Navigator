package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Adds the user-visible output timestamp used by the advisory response-timeout indicator.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskLastOutputAtMigration implements DatabaseStartupMigration {

    private static final List<String> TABLES = List.of(
            "claude_tasks",
            "codex_tasks",
            "gemini_tasks",
            "session_tasks"
    );
    private static final String COLUMN = "last_output_at";

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseMigrationSupport migrationSupport;

    @Override
    public String id() {
        return "startup-003-task-last-output-at";
    }

    @Override
    public String description() {
        return "Add task last_output_at timestamp for worker response-timeout indicator";
    }

    @Override
    public void migrate() {
        for (String table : TABLES) {
            migrateTable(table);
        }
    }

    private void migrateTable(String table) {
        if (!migrationSupport.tableExists(table)) {
            return;
        }
        if (!migrationSupport.columnExists(table, COLUMN)) {
            jdbcTemplate.execute("ALTER TABLE " + migrationSupport.quoteIdentifier(table)
                    + " ADD COLUMN " + migrationSupport.quoteIdentifier(COLUMN) + " DATETIME(6) NULL");
            log.info("Added {}.{} for response-timeout indicator", table, COLUMN);
        }
        Optional<String> createdAtColumn = migrationSupport.findColumn(table, "created_at", "createdAt");
        Optional<String> updatedAtColumn = migrationSupport.findColumn(table, "updated_at", "updatedAt");
        jdbcTemplate.update("UPDATE " + migrationSupport.quoteIdentifier(table)
                + " SET " + migrationSupport.quoteIdentifier(COLUMN)
                + " = COALESCE(" + backfillBaseline(createdAtColumn, updatedAtColumn) + ")"
                + " WHERE " + migrationSupport.quoteIdentifier(COLUMN) + " IS NULL");
    }

    private String backfillBaseline(Optional<String> createdAtColumn, Optional<String> updatedAtColumn) {
        StringBuilder baseline = new StringBuilder(migrationSupport.quoteIdentifier(COLUMN));
        createdAtColumn.ifPresent(column -> baseline.append(", ").append(migrationSupport.quoteIdentifier(column)));
        updatedAtColumn.ifPresent(column -> baseline.append(", ").append(migrationSupport.quoteIdentifier(column)));
        baseline.append(", NOW(6)");
        return baseline.toString();
    }
}
