package com.foggy.navigator.session.exception;

/**
 * 会话已绑定 Provider，新请求试图切换执行 Provider 时抛出。
 */
public class SessionProviderBoundMismatchException extends IllegalArgumentException {

    public static final String ERROR_CODE = "SESSION_PROVIDER_MISMATCH";

    private final String sessionId;
    private final String boundProviderType;
    private final String requestedProviderType;

    public SessionProviderBoundMismatchException(String sessionId,
                                                 String boundProviderType,
                                                 String requestedProviderType) {
        super(String.format(
                "%s: Session [%s] is bound to provider [%s], cannot switch to [%s]. "
                        + "Please create a new session or fork the current one.",
                ERROR_CODE,
                sessionId,
                boundProviderType,
                requestedProviderType));
        this.sessionId = sessionId;
        this.boundProviderType = boundProviderType;
        this.requestedProviderType = requestedProviderType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getBoundProviderType() {
        return boundProviderType;
    }

    public String getRequestedProviderType() {
        return requestedProviderType;
    }
}
