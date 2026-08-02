package com.foggy.navigator.session.lifecycle;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.net.URI;

@Component
public class DatabaseLifecycleAuthorityClock implements LifecycleAuthorityClock {
    private final JdbcTemplate jdbcTemplate;

    public DatabaseLifecycleAuthorityClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public LocalDateTime databaseNow() {
        LocalDateTime value = jdbcTemplate.queryForObject(
                "select utc_timestamp(6)", LocalDateTime.class);
        if (value == null) {
            throw new IllegalStateException(
                    "LIFECYCLE_DATABASE_TIME_UNAVAILABLE");
        }
        return value;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public DatabaseIdentity databaseIdentity() {
        return jdbcTemplate.execute((ConnectionCallback<DatabaseIdentity>) connection -> {
            var metadata = connection.getMetaData();
            String jdbcUrl = metadata.getURL();
            URI endpoint = URI.create(jdbcUrl.substring("jdbc:".length()));
            return new DatabaseIdentity(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseProductVersion(),
                    connection.getCatalog(), endpoint.getHost(),
                    endpoint.getPort());
        });
    }
}
