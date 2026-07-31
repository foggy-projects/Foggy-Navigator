package com.foggy.navigator.spi.lifecycle;

public interface TerminalCleanupPort {
    boolean supports(String participant, TerminalCleanupContext context);

    String execute(
            String participant,
            TerminalCleanupContext context,
            String idempotencyKey);
}
