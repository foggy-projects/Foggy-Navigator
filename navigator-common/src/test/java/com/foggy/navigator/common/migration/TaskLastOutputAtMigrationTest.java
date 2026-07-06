package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskLastOutputAtMigrationTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
    private final TaskLastOutputAtMigration migration = new TaskLastOutputAtMigration(jdbcTemplate, migrationSupport);

    @Test
    void skipsMissingTables() {
        migration.migrate();

        verify(jdbcTemplate, never()).execute(contains("ALTER TABLE"));
        verify(jdbcTemplate, never()).update(contains("UPDATE"));
    }

    @Test
    void addsAndBackfillsLastOutputAtForExistingTaskTables() {
        when(migrationSupport.tableExists("claude_tasks")).thenReturn(true);
        when(migrationSupport.tableExists("codex_tasks")).thenReturn(true);
        when(migrationSupport.tableExists("gemini_tasks")).thenReturn(true);
        when(migrationSupport.tableExists("session_tasks")).thenReturn(true);
        when(migrationSupport.quoteIdentifier("claude_tasks")).thenReturn("`claude_tasks`");
        when(migrationSupport.quoteIdentifier("codex_tasks")).thenReturn("`codex_tasks`");
        when(migrationSupport.quoteIdentifier("gemini_tasks")).thenReturn("`gemini_tasks`");
        when(migrationSupport.quoteIdentifier("session_tasks")).thenReturn("`session_tasks`");
        when(migrationSupport.quoteIdentifier("last_output_at")).thenReturn("`last_output_at`");
        when(migrationSupport.quoteIdentifier("created_at")).thenReturn("`created_at`");
        when(migrationSupport.quoteIdentifier("updated_at")).thenReturn("`updated_at`");
        when(migrationSupport.findColumn("claude_tasks", "created_at", "createdAt")).thenReturn(Optional.of("created_at"));
        when(migrationSupport.findColumn("claude_tasks", "updated_at", "updatedAt")).thenReturn(Optional.of("updated_at"));
        when(migrationSupport.findColumn("codex_tasks", "created_at", "createdAt")).thenReturn(Optional.of("created_at"));
        when(migrationSupport.findColumn("codex_tasks", "updated_at", "updatedAt")).thenReturn(Optional.of("updated_at"));
        when(migrationSupport.findColumn("gemini_tasks", "created_at", "createdAt")).thenReturn(Optional.of("created_at"));
        when(migrationSupport.findColumn("gemini_tasks", "updated_at", "updatedAt")).thenReturn(Optional.of("updated_at"));
        when(migrationSupport.findColumn("session_tasks", "created_at", "createdAt")).thenReturn(Optional.of("created_at"));
        when(migrationSupport.findColumn("session_tasks", "updated_at", "updatedAt")).thenReturn(Optional.of("updated_at"));

        migration.migrate();

        verify(jdbcTemplate).execute(contains("ALTER TABLE `claude_tasks` ADD COLUMN `last_output_at`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `codex_tasks` ADD COLUMN `last_output_at`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `gemini_tasks` ADD COLUMN `last_output_at`"));
        verify(jdbcTemplate).execute(contains("ALTER TABLE `session_tasks` ADD COLUMN `last_output_at`"));
        verify(jdbcTemplate, times(4)).update(contains("SET `last_output_at` = COALESCE"));
    }
}
