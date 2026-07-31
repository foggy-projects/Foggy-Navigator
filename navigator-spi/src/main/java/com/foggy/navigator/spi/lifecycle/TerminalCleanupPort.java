package com.foggy.navigator.spi.lifecycle;

public interface TerminalCleanupPort {
    boolean supports(String participant, TerminalCleanupContext context);

    /**
     * Exact durable resource inspection used while freezing the cleanup plan.
     * Supporting a capability does not imply that a token or receipt was
     * actually created for this task.
     */
    default boolean resourcePresent(
            String participant, TerminalCleanupContext context) {
        return false;
    }

    String execute(
            String participant,
            TerminalCleanupContext context,
            String idempotencyKey);
}
