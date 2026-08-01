package com.foggy.navigator.session.lifecycle;

import java.time.LocalDateTime;

public interface LifecycleAuthorityClock {
    LocalDateTime databaseNow();

    DatabaseIdentity databaseIdentity();

    record DatabaseIdentity(
            String product,
            String version,
            String database,
            String host,
            int port) {
    }
}
