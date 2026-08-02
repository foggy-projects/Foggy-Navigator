package com.foggy.navigator.claude.worker.model.entity;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeTaskEntityTest {

    @Test
    void prePersistCapturesAuthoritativeEpochIndependentOfJvmTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                ClaudeTaskEntity entity = new ClaudeTaskEntity();
                entity.setCreatedAtEpochMs(1L);
                long before = Instant.now().toEpochMilli();

                entity.onCreate();

                long after = Instant.now().toEpochMilli();
                assertTrue(entity.getCreatedAtEpochMs() >= before);
                assertTrue(entity.getCreatedAtEpochMs() <= after);
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void epochMappingAndMigrationAreNullableAdditiveAndNeverBackfillHistory() throws Exception {
        Column column = ClaudeTaskEntity.class.getDeclaredField("createdAtEpochMs")
                .getAnnotation(Column.class);
        assertNotNull(column);
        assertEquals("created_at_epoch_ms", column.name());
        assertFalse(column.updatable());
        assertTrue(column.nullable());

        String migration = readRepositoryFile(
                "docs/migration/2026-08-03-claude-task-created-at-epoch-ms.sql")
                .toLowerCase(Locale.ROOT);
        assertTrue(migration.contains("information_schema.columns"));
        assertTrue(migration.contains(
                "alter table claude_tasks add column created_at_epoch_ms bigint null"));
        assertFalse(migration.matches("(?s).*\\bupdate\\s+claude_tasks\\b.*"));
        assertFalse(migration.matches("(?s).*\\b(insert|delete|merge)\\b.*"));

        String baseline = readRepositoryFile(
                "docs/migration/2026-08-01-arch-001-current-schema-baseline.sql")
                .toLowerCase(Locale.ROOT);
        int tableStart = baseline.indexOf("create table if not exists `claude_tasks`");
        int tableEnd = baseline.indexOf(") engine=innodb", tableStart);
        assertTrue(tableStart >= 0 && tableEnd > tableStart);
        assertTrue(baseline.substring(tableStart, tableEnd)
                .contains("`created_at_epoch_ms` bigint default null"));

        String productionProfile = readRepositoryFile(
                "launcher/src/main/resources/application-prod.yml").toLowerCase(Locale.ROOT);
        assertTrue(productionProfile.contains("ddl-auto: validate"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path root = workingDirectory; root != null; root = root.getParent()) {
            Path candidate = root.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return Files.readString(candidate);
        }
        throw new IOException("Cannot locate repository file " + relativePath + " from " + workingDirectory);
    }
}
