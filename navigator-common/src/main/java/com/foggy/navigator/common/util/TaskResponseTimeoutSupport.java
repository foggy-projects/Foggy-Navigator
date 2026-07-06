package com.foggy.navigator.common.util;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Computes the advisory worker response timeout indicator from persisted task timestamps.
 */
public final class TaskResponseTimeoutSupport {

    public static final long DEFAULT_RESPONSE_TIMEOUT_SECONDS = 300L;

    private TaskResponseTimeoutSupport() {
    }

    public static boolean isResponseTimedOut(String status,
                                             LocalDateTime lastOutputAt,
                                             LocalDateTime createdAt,
                                             LocalDateTime now) {
        return silentForSeconds(status, lastOutputAt, createdAt, now) >= DEFAULT_RESPONSE_TIMEOUT_SECONDS;
    }

    public static long silentForSeconds(String status,
                                        LocalDateTime lastOutputAt,
                                        LocalDateTime createdAt,
                                        LocalDateTime now) {
        if (!"RUNNING".equals(status) || now == null) {
            return 0L;
        }
        LocalDateTime baseline = lastOutputAt != null ? lastOutputAt : createdAt;
        if (baseline == null || baseline.isAfter(now)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(baseline, now).getSeconds());
    }
}
