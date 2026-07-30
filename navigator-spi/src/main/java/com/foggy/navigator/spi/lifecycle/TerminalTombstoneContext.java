package com.foggy.navigator.spi.lifecycle;

public record TerminalTombstoneContext(
        String taskId,
        String providerType,
        String tenantId,
        String providerTaskId,
        String providerTaskUserId,
        String sourceAgentId
) {
}
