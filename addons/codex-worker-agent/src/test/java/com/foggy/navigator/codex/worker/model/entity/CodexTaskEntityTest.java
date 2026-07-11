package com.foggy.navigator.codex.worker.model.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexTaskEntityTest {

    @Test
    void prePersistCapturesAuthoritativeEpochIndependentOfJvmTimeZone() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            for (String timeZone : new String[]{"UTC", "Asia/Shanghai", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(timeZone));
                CodexTaskEntity entity = new CodexTaskEntity();
                entity.setCreatedAtEpochMs(1L);
                long before = Instant.now().toEpochMilli();

                entity.onCreate();

                long after = Instant.now().toEpochMilli();
                assertTrue(entity.getCreatedAtEpochMs() >= before);
                assertTrue(entity.getCreatedAtEpochMs() <= after);
            }
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }
}
