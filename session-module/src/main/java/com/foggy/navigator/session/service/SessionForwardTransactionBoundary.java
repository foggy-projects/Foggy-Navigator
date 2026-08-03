package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.TaskStateRepairedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Spring-proxied transaction boundaries for the two session-forward execution modes.
 */
@Component
public class SessionForwardTransactionBoundary {

    @Transactional(isolation = Isolation.READ_COMMITTED,
            noRollbackFor = TaskStateRepairedException.class)
    public <T> T executeExistingTarget(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> T executeNewTarget(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }
}
