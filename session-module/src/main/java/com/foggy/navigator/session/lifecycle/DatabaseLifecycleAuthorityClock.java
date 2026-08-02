package com.foggy.navigator.session.lifecycle;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.net.URI;

@Component
public class DatabaseLifecycleAuthorityClock implements LifecycleAuthorityClock {
    @PersistenceContext
    private EntityManager entityManager;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseLifecycleAuthorityClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public LocalDateTime databaseNow() {
        Object value = entityManager.createNativeQuery(
                "select utc_timestamp(6)").getSingleResult();
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof Timestamp time) return time.toLocalDateTime();
        if (value instanceof OffsetDateTime time) return time.toLocalDateTime();
        throw new IllegalStateException("LIFECYCLE_DATABASE_TIME_UNAVAILABLE");
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
