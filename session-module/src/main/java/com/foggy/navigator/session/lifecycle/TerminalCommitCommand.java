package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.spi.lifecycle.TerminalTombstoneContext;

public record TerminalCommitCommand(
        TerminalTombstoneContext tombstoneContext,
        String terminalFactId,
        String writerGenerationId,
        TaskTerminalOutcome outcome,
        TaskTerminalSource source,
        TerminalCleanupResources cleanupResources
) {
    public String taskId() {
        return tombstoneContext.taskId();
    }
}
