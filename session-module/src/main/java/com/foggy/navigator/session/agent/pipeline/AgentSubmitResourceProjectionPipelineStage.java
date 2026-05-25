package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class AgentSubmitResourceProjectionPipelineStage implements AgentSubmitPipelineStage {

    @Override
    public String name() {
        return "agent-submit-resource-projection";
    }

    @Override
    public int order() {
        return -500;
    }

    @Override
    public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
        A2aMessage message = request.getMessage();
        if (message != null) {
            if (!hasText(request.getPrompt())) {
                request.setPrompt(extractTextPrompt(message));
            }
            if (!hasText(request.getContextId()) && hasText(message.getContextId())) {
                request.setContextId(message.getContextId());
            } else if (hasText(request.getContextId()) && !hasText(message.getContextId())) {
                message.setContextId(request.getContextId());
            }
            if (!hasText(request.getContextAlias()) && hasText(message.getContextAlias())) {
                request.setContextAlias(message.getContextAlias());
            } else if (hasText(request.getContextAlias()) && !hasText(message.getContextAlias())) {
                message.setContextAlias(request.getContextAlias());
            }
            request.setMetadata(mergeMetadata(message.getMetadata(), request.getMetadata()));
        }
        log.debug("Agent submit resource projection: agentId={}, metadataKeys={}",
                request.getAgentId(),
                request.getMetadata() != null ? request.getMetadata().keySet() : null);
        return chain.proceed(request);
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> messageMetadata,
                                              Map<String, Object> requestMetadata) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (messageMetadata != null) {
            merged.putAll(messageMetadata);
        }
        if (requestMetadata != null) {
            merged.putAll(requestMetadata);
        }
        return merged.isEmpty() ? null : merged;
    }

    private String extractTextPrompt(A2aMessage message) {
        if (message.getParts() == null) {
            return null;
        }
        return message.getParts().stream()
                .filter(Objects::nonNull)
                .filter(part -> "text".equals(part.getType()))
                .map(A2aPart::getText)
                .filter(this::hasText)
                .findFirst()
                .orElse(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
