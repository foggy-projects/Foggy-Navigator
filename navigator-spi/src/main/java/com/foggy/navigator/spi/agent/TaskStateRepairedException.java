package com.foggy.navigator.spi.agent;

/**
 * Signals that a task command repaired durable state and must still return an actionable retry.
 */
public class TaskStateRepairedException extends IllegalStateException {

    protected TaskStateRepairedException(String message) {
        super(message);
    }
}
