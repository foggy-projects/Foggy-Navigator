package com.foggy.navigator.session.agent.pipeline;

import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.session.repository.SessionCodingAgentRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSubmitDirectoryResolutionPipelineStage implements AgentSubmitPipelineStage {

    private static final String[] DIRECTORY_METADATA_KEYS = {
            "directoryId",
            "directory_id",
            "workingDirectoryId",
            "working_directory_id"
    };

    private final SessionCodingAgentRepository codingAgentRepository;

    @Override
    public String name() {
        return "agent-submit-directory-resolution";
    }

    @Override
    public int order() {
        return -400;
    }

    @Override
    public AgentTaskSubmitResult handle(AgentTaskSubmitRequest request, AgentSubmitPipelineChain chain) {
        if (request == null) {
            return chain.proceed(null);
        }

        String explicitDirectoryId = trimToNull(request.getDirectoryId());
        if (explicitDirectoryId != null) {
            applyDirectoryId(request, explicitDirectoryId);
            return chain.proceed(request);
        }

        String metadataDirectoryId = resolveMetadataDirectoryId(request);
        if (metadataDirectoryId != null) {
            applyDirectoryId(request, metadataDirectoryId);
            log.debug("Resolved agent submit directory from metadata: agentId={}, directoryId={}",
                    request.getAgentId(), metadataDirectoryId);
            return chain.proceed(request);
        }

        String defaultDirectoryId = resolveDefaultDirectoryId(request);
        if (defaultDirectoryId != null) {
            applyDirectoryId(request, defaultDirectoryId);
            log.debug("Resolved agent submit directory from agent default: agentId={}, directoryId={}",
                    request.getAgentId(), defaultDirectoryId);
        }
        return chain.proceed(request);
    }

    private String resolveMetadataDirectoryId(AgentTaskSubmitRequest request) {
        String value = firstText(request.getMetadata());
        if (value != null) {
            return value;
        }
        A2aMessage message = request.getMessage();
        return message != null ? firstText(message.getMetadata()) : null;
    }

    private String firstText(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        for (String key : DIRECTORY_METADATA_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String text) {
                String trimmed = trimToNull(text);
                if (trimmed != null) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private String resolveDefaultDirectoryId(AgentTaskSubmitRequest request) {
        String agentId = trimToNull(request.getAgentId());
        if (agentId == null) {
            return null;
        }

        AgentResolveContext context = request.getResolveContext();
        Optional<CodingAgentEntity> agent = Optional.empty();
        String tenantId = context != null ? trimToNull(context.getTenantId()) : null;
        if (tenantId != null) {
            agent = codingAgentRepository.findByAgentIdAndTenantId(agentId, tenantId);
        } else {
            String userId = context != null ? trimToNull(context.getUserId()) : null;
            if (userId != null) {
                agent = codingAgentRepository.findByAgentIdAndUserId(agentId, userId);
            }
        }

        return agent
                .filter(entity -> !Boolean.FALSE.equals(entity.getEnabled()))
                .map(CodingAgentEntity::getDefaultDirectoryId)
                .map(this::trimToNull)
                .orElse(null);
    }

    private void applyDirectoryId(AgentTaskSubmitRequest request, String directoryId) {
        request.setDirectoryId(directoryId);
        Map<String, Object> metadata = request.getMetadata() != null
                ? new LinkedHashMap<>(request.getMetadata())
                : new LinkedHashMap<>();
        metadata.put("directoryId", directoryId);
        request.setMetadata(metadata);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
