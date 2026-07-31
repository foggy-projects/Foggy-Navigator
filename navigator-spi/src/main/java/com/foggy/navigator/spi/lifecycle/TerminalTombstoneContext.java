package com.foggy.navigator.spi.lifecycle;

public record TerminalTombstoneContext(
        String taskId,
        String sessionId,
        String providerType,
        String tenantId,
        String providerTaskId,
        String providerTaskUserId,
        String sourceAgentId,
        String operationId
) {
    public TerminalTombstoneContext(
            String taskId,
            String providerType,
            String tenantId,
            String providerTaskId,
            String providerTaskUserId,
            String sourceAgentId) {
        this(taskId, null, providerType, tenantId, providerTaskId,
                providerTaskUserId, sourceAgentId, null);
    }
}
