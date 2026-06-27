package com.foggy.navigator.common.migration;

/**
 * Idempotent startup migration managed by {@link DatabaseStartupMigrationRunner}.
 */
public interface DatabaseStartupMigration {

    String id();

    String description();

    void migrate();
}
