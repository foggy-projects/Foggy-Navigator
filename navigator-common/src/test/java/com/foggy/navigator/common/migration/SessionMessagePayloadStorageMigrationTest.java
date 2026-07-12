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
import static org.mockito.Mockito.when;

class SessionMessagePayloadStorageMigrationTest {

    @Test
    void createsDescriptorAndIndexesWithoutAttemptingMissingOptionalTaskTables() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.quoteIdentifier(anyString()))
                .thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");

        SessionMessagePayloadStorageMigration migration =
                new SessionMessagePayloadStorageMigration(jdbcTemplate, migrationSupport);

        migration.migrate();

        assertEquals("startup-004-session-message-payload-storage", migration.id());
        verify(jdbcTemplate).execute(contains("CREATE TABLE IF NOT EXISTS session_message_payloads"));
        verify(jdbcTemplate).execute(contains("UNIQUE INDEX `uk_smp_message_id`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_smp_session_id`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_smp_status_expires_at`"));
        verify(jdbcTemplate).execute(contains("INDEX `idx_smp_expires_at`"));
    }

    @Test
    void widensExistingResultBearingColumnOnlyWhenItIsBelowMediumTextCapacity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("session_messages")).thenReturn(true);
        when(migrationSupport.findColumn("session_messages", "content", "content"))
                .thenReturn(Optional.of("content"));
        when(migrationSupport.quoteIdentifier(anyString()))
                .thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("session_messages"), eq("content")))
                .thenReturn("text");

        new SessionMessagePayloadStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate).execute(contains("ALTER TABLE `session_messages` MODIFY COLUMN `content` MEDIUMTEXT NULL"));
    }

    @Test
    void preservesLongTextColumnsInsteadOfNarrowingThem() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("session_messages")).thenReturn(true);
        when(migrationSupport.findColumn("session_messages", "content", "content"))
                .thenReturn(Optional.of("content"));
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class), eq("session_messages"), eq("content")))
                .thenReturn("longtext");

        new SessionMessagePayloadStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate, never()).execute(
                contains("ALTER TABLE `session_messages` MODIFY COLUMN `content` MEDIUMTEXT NULL"));
    }

    @Test
    void widensLangGraphStructuredFinalOutputAlongsideResultText() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.tableExists("langgraph_tasks")).thenReturn(true);
        when(migrationSupport.findColumn("langgraph_tasks", "structured_output", "structuredOutput"))
                .thenReturn(Optional.of("structured_output"));
        when(migrationSupport.quoteIdentifier(anyString()))
                .thenAnswer(invocation -> "`" + invocation.getArgument(0) + "`");
        when(jdbcTemplate.queryForObject(anyString(), eq(String.class),
                eq("langgraph_tasks"), eq("structured_output")))
                .thenReturn("text");

        new SessionMessagePayloadStorageMigration(jdbcTemplate, migrationSupport).migrate();

        verify(jdbcTemplate).execute(contains(
                "ALTER TABLE `langgraph_tasks` MODIFY COLUMN `structured_output` MEDIUMTEXT NULL"));
    }
}
