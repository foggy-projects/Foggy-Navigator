package com.foggy.navigator.session.lifecycle;

public interface TerminalCleanupAction {
    TerminalCleanupParticipant participant();

    /**
     * Must be idempotent for the supplied key. Throwing leaves the checkpoint
     * pending for a later bounded retry.
     */
    String execute(String taskId, String idempotencyKey);
}
