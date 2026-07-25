package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdGeneratorTest {

    @Test
    void shortIdUsesAsiaShanghaiDateAcrossUtcDayBoundary() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T16:00:00Z"), ZoneOffset.UTC);

        assertTrue(IdGenerator.shortId(clock).startsWith("20260725-"));
    }

    @Test
    void shortIdDateMatchesCreatedAtInContractTimezone() {
        Instant createdAt = Instant.parse("2026-07-25T15:59:59Z");
        Clock clock = Clock.fixed(createdAt, ZoneOffset.UTC);
        String taskId = IdGenerator.shortId(clock);
        LocalDate taskIdDate = LocalDate.parse(
                taskId.substring(0, 8),
                DateTimeFormatter.BASIC_ISO_DATE);
        LocalDate createdAtDate = createdAt.atZone(IdGenerator.TASK_ID_DATE_ZONE).toLocalDate();

        assertEquals(createdAtDate, taskIdDate);
    }
}
