package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResponseTimeoutSupportTest {

    @Test
    void runningTaskTimesOutAfterFiveSilentMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 10, 0);

        assertTrue(TaskResponseTimeoutSupport.isResponseTimedOut(
                "RUNNING",
                now.minusSeconds(300),
                now.minusMinutes(10),
                now));
        assertEquals(300L, TaskResponseTimeoutSupport.silentForSeconds(
                "RUNNING",
                now.minusSeconds(300),
                now.minusMinutes(10),
                now));
    }

    @Test
    void recentOutputClearsTimeout() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 10, 0);

        assertFalse(TaskResponseTimeoutSupport.isResponseTimedOut(
                "RUNNING",
                now.minusSeconds(120),
                now.minusMinutes(10),
                now));
    }

    @Test
    void nonRunningStatesNeverShowResponseTimeout() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 10, 0);

        assertFalse(TaskResponseTimeoutSupport.isResponseTimedOut(
                "AWAITING_PERMISSION",
                now.minusMinutes(30),
                now.minusMinutes(30),
                now));
        assertEquals(0L, TaskResponseTimeoutSupport.silentForSeconds(
                "ABORTED",
                now.minusMinutes(30),
                now.minusMinutes(30),
                now));
    }

    @Test
    void createdAtIsFallbackBaseline() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 30, 10, 0);

        assertTrue(TaskResponseTimeoutSupport.isResponseTimedOut(
                "RUNNING",
                null,
                now.minusMinutes(6),
                now));
    }
}
