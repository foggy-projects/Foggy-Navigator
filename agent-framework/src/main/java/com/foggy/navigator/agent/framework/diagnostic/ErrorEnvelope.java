package com.foggy.navigator.agent.framework.diagnostic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Safe structured error summary propagated through Worker, AgentMessage,
 * Task DTO and SSE boundaries. It must never contain raw prompts, tool data,
 * credentials, full paths or an anonymous share token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorEnvelope {
    private String errorCode;
    private String message;
    private ErrorCategory category;
    private ErrorRuntimePhase runtimePhase;
    private Boolean recoverable;
    private String diagnosticRef;
    private Instant occurredAt;
    private String taskId;
    private String providerType;
    private String runtimeType;
}
