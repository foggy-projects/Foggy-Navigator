package com.foggy.navigator.spi.lifecycle;

public record TerminalCleanupContext(
        String taskId,
        String sessionId,
        String providerType,
        String tenantId,
        String providerTaskId,
        String providerTaskUserId,
        String sourceAgentId,
        String operationId,
        String clientRequestId,
        String terminalOutcome
) {
    public TerminalCleanupContext(
            String taskId,
            String sessionId,
            String providerType,
            String tenantId,
            String providerTaskId,
            String providerTaskUserId,
            String sourceAgentId,
            String operationId,
            String terminalOutcome) {
        this(taskId, sessionId, providerType, tenantId, providerTaskId,
                providerTaskUserId, sourceAgentId, operationId, null,
                terminalOutcome);
    }
}
