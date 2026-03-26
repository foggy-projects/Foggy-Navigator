package com.foggy.navigator.session.exception;

/**
 * 会话已绑定 Agent，新请求试图切换到不同 Agent 时抛出。
 */
public class SessionAgentBoundMismatchException extends RuntimeException {

    private final String sessionId;
    private final String boundAgentId;
    private final String requestedAgentId;

    public SessionAgentBoundMismatchException(String sessionId, String boundAgentId, String requestedAgentId) {
        super(String.format("Session [%s] is bound to agent [%s], cannot switch to [%s]. " +
                "Please create a new session or fork the current one.", sessionId, boundAgentId, requestedAgentId));
        this.sessionId = sessionId;
        this.boundAgentId = boundAgentId;
        this.requestedAgentId = requestedAgentId;
    }

    public String getSessionId() { return sessionId; }
    public String getBoundAgentId() { return boundAgentId; }
    public String getRequestedAgentId() { return requestedAgentId; }
}
