package com.foggy.navigator.agent.framework.core;

/**
 * Agent配置异常
 */
public class AgentConfigException extends RuntimeException {

    public AgentConfigException(String message) {
        super(message);
    }

    public AgentConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
