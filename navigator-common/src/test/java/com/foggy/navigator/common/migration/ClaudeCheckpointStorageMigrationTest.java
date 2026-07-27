package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClaudeCheckpointStorageMigrationTest {

    @Test
    void widensTextCheckpointColumnToMediumText() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = migrationSupportWithColumn("checkpoints");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("claude_tasks"), eq("checkpoints"))).thenReturn("text");

        ClaudeCheckpointStorageMigration migration =
                new ClaudeCheckpointStorageMigration(jdbcTemplate, migrationSupport);
        migration.migrate();

        assertEquals("startup-007-claude-checkpoint-storage", migration.id());
        assertEquals("Widen Claude checkpoint storage for durable replay", migration.description());
        verify(jdbcTemplate).execute(contains(
                "ALTER TABLE `claude_tasks` MODIFY COLUMN `checkpoints` MEDIUMTEXT NULL"));
    }

    @Test
    void preservesMediumTextCheckpointColumn() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = migrationSupportWithColumn("checkpoints");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("claude_tasks"), eq("checkpoints"))).thenReturn("mediumtext");

        new ClaudeCheckpointStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void preservesLongTextCheckpointColumn() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = migrationSupportWithColumn("checkpoints");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("claude_tasks"), eq("checkpoints"))).thenReturn("longtext");

        new ClaudeCheckpointStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    @Test
    void skipsMissingTaskTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("claude_tasks")).thenReturn(false);

        new ClaudeCheckpointStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void skipsMissingCheckpointColumn() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("claude_tasks")).thenReturn(true);
        when(migrationSupport.findColumn("claude_tasks", "checkpoints")).thenReturn(Optional.empty());

        new ClaudeCheckpointStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verifyNoInteractions(jdbcTemplate);
    }

    private DatabaseMigrationSupport migrationSupportWithColumn(String column) {
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("claude_tasks")).thenReturn(true);
        when(migrationSupport.findColumn("claude_tasks", "checkpoints")).thenReturn(Optional.of(column));
        when(migrationSupport.quoteIdentifier(anyString()))
                .thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");
        return migrationSupport;
    }
}
