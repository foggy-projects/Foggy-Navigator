package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AgentSubmitValidationPipelineStage implements AgentSubmitPipelineStage {

    @Override
    public String name() {
        return "agent-submit-validation";
    }

    @Override
    public int order() {
        return -1000;
    }

    @Override
    public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
        if (request == null) {
            throw new IllegalArgumentException("submit request is required");
        }
        if (!hasText(request.getAgentId())
                && !hasText(request.getDirectoryId())
                && !hasText(request.getWorkerId())
                && !hasText(request.getProviderType())) {
            throw new IllegalArgumentException("agentId, directoryId, workerId, or providerType is required");
        }
        if (!hasText(request.getPrompt()) && !hasMessageText(request.getMessage())) {
            throw new IllegalArgumentException("prompt or message text is required");
        }
        return chain.proceed(request);
    }

    private boolean hasMessageText(A2aMessage message) {
        return message != null
                && message.getParts() != null
                && message.getParts().stream()
                .filter(Objects::nonNull)
                .map(A2aPart::getText)
                .anyMatch(this::hasText);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
