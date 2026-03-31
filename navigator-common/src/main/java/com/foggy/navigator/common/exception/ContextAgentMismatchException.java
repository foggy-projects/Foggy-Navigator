package com.foggy.navigator.common.exception;

/**
 * contextId 已绑定到其他 Agent，新请求试图用不同 Agent 复用时抛出。
 */
public class ContextAgentMismatchException extends RuntimeException {

    private final String contextId;
    private final String boundAgentId;
    private final String requestedAgentId;

    public ContextAgentMismatchException(String contextId, String boundAgentId, String requestedAgentId) {
        super(String.format("contextId [%s] is bound to agent [%s], cannot use with agent [%s]. " +
                "Please use a different contextId or call the correct agent.", contextId, boundAgentId, requestedAgentId));
        this.contextId = contextId;
        this.boundAgentId = boundAgentId;
        this.requestedAgentId = requestedAgentId;
    }

    public String getContextId() { return contextId; }
    public String getBoundAgentId() { return boundAgentId; }
    public String getRequestedAgentId() { return requestedAgentId; }
}
