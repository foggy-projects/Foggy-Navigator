package com.foggy.navigator.common.migration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseStartupMigrationRunnerTest {

    @Test
    void runMigrationsSkipsWithoutCheckingDatabaseWhenDisabled() {
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        RecordingMigration migration = new RecordingMigration("startup-001", "first", new ArrayList<>());
        DatabaseStartupMigrationProperties properties = new DatabaseStartupMigrationProperties();
        properties.setEnabled(false);

        new DatabaseStartupMigrationRunner(migrationSupport, List.of(migration), properties).runMigrations();

        verify(migrationSupport, never()).isMySql();
        assertEquals(List.of(), migration.events);
    }

    @Test
    void runMigrationsSkipsWhenDatabaseIsNotMysql() {
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.isMySql()).thenReturn(false);
        RecordingMigration migration = new RecordingMigration("startup-001", "first", new ArrayList<>());
        DatabaseStartupMigrationProperties properties = new DatabaseStartupMigrationProperties();

        new DatabaseStartupMigrationRunner(migrationSupport, List.of(migration), properties).runMigrations();

        assertEquals(List.of(), migration.events);
    }

    @Test
    void runMigrationsDryRunPrintsManifestWithoutApplying() {
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.isMySql()).thenReturn(true);
        RecordingMigration migration = new RecordingMigration("startup-001", "first", new ArrayList<>());
        DatabaseStartupMigrationProperties properties = new DatabaseStartupMigrationProperties();
        properties.setDryRun(true);

        new DatabaseStartupMigrationRunner(migrationSupport, List.of(migration), properties).runMigrations();

        assertEquals(List.of(), migration.events);
    }

    @Test
    void runMigrationsAppliesInIdOrderAndContinuesAfterFailure() {
        DatabaseMigrationSupport migrationSupport = mock(DatabaseMigrationSupport.class);
        when(migrationSupport.isMySql()).thenReturn(true);
        List<String> events = new ArrayList<>();
        RecordingMigration second = new RecordingMigration("startup-002", "second", events);
        RecordingMigration first = new RecordingMigration("startup-001", "first", events);
        RecordingMigration third = new RecordingMigration("startup-003", "third", events);
        second.fail = true;
        DatabaseStartupMigrationProperties properties = new DatabaseStartupMigrationProperties();

        new DatabaseStartupMigrationRunner(
                migrationSupport,
                List.of(second, third, first),
                properties
        ).runMigrations();

        assertEquals(List.of("startup-001", "startup-002", "startup-003"), events);
    }

    @Test
    void manifestIsSortedByMigrationId() {
        DatabaseStartupMigrationProperties properties = new DatabaseStartupMigrationProperties();
        DatabaseStartupMigrationRunner runner = new DatabaseStartupMigrationRunner(
                mock(DatabaseMigrationSupport.class),
                List.of(
                        new RecordingMigration("startup-020", "twentieth", new ArrayList<>()),
                        new RecordingMigration("startup-010", "tenth", new ArrayList<>())
                ),
                properties
        );

        assertEquals(List.of(
                new DatabaseStartupMigrationDescriptor("startup-010", "tenth"),
                new DatabaseStartupMigrationDescriptor("startup-020", "twentieth")
        ), runner.manifest());
    }

    private static class RecordingMigration implements DatabaseStartupMigration {

        private final String id;
        private final String description;
        private final List<String> events;
        private boolean fail;

        private RecordingMigration(String id, String description, List<String> events) {
            this.id = id;
            this.description = description;
            this.events = events;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public void migrate() {
            events.add(id);
            if (fail) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
