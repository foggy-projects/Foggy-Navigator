package com.foggy.navigator.session.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface SentinelLeaseStore {
    Optional<SentinelLease> tryAcquire(
            String physicalWorkerId, String holderId, Instant now, Duration duration);
}
