package com.foggy.navigator.common.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseStartupMigrationRunner {

    private final DatabaseMigrationSupport migrationSupport;
    private final List<DatabaseStartupMigration> migrations;
    private final DatabaseStartupMigrationProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void runStartupMigrations() {
        runMigrations();
    }

    void runMigrations() {
        List<DatabaseStartupMigration> sortedMigrations = sortedMigrations();
        if (sortedMigrations.isEmpty()) {
            return;
        }
        if (!properties.isEnabled()) {
            log.info("Database startup migrations disabled; manifest={}", manifestSummary());
            return;
        }
        if (!migrationSupport.isMySql()) {
            log.info("Skip database startup migrations: database is not MySQL; manifest={}", manifestSummary());
            return;
        }

        log.info("Database startup migration manifest: {}", manifestSummary());
        for (DatabaseStartupMigration migration : sortedMigrations) {
            if (properties.isDryRun()) {
                log.info("Dry-run database startup migration: {} - {}", migration.id(), migration.description());
                continue;
            }
            try {
                migration.migrate();
                log.info("Completed database startup migration: {}", migration.id());
            } catch (Exception e) {
                log.warn("Failed database startup migration {}: {}", migration.id(), e.getMessage());
            }
        }
    }

    public List<DatabaseStartupMigrationDescriptor> manifest() {
        return sortedMigrations().stream()
                .map(migration -> new DatabaseStartupMigrationDescriptor(migration.id(), migration.description()))
                .toList();
    }

    private String manifestSummary() {
        return manifest().stream()
                .map(entry -> entry.id() + " (" + entry.description() + ")")
                .collect(Collectors.joining(", "));
    }

    private List<DatabaseStartupMigration> sortedMigrations() {
        return migrations.stream()
                .sorted(Comparator.comparing(DatabaseStartupMigration::id))
                .toList();
    }
}
