package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.common.enums.LlmModelCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Central resolver for upstream-visible A2Agent runtime resources.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class A2AgentResourceResolver {

    private final ClientAppModelConfigGrantService modelConfigGrantService;

    public record ResolvedModelResource(
            String modelConfigId,
            String requestedModelConfigId,
            LlmModelCategory category,
            String source) {
    }

    @Transactional(readOnly = true)
    public String resolveRequiredModelConfigId(String tenantId,
                                               String clientAppId,
                                               String requestedModelConfigId,
                                               LlmModelCategory category) {
        return resolveRequiredModel(tenantId, clientAppId, requestedModelConfigId, category).modelConfigId();
    }

    @Transactional(readOnly = true)
    public ResolvedModelResource resolveRequiredModel(String tenantId,
                                                      String clientAppId,
                                                      String requestedModelConfigId,
                                                      LlmModelCategory category) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        String normalizedRequestedModelConfigId = trimToNull(requestedModelConfigId);
        String resolvedModelConfigId = modelConfigGrantService.resolveEffectiveModelConfigId(
                tenantId,
                clientAppId,
                normalizedRequestedModelConfigId,
                category);
        ResolvedModelResource resolved = new ResolvedModelResource(
                resolvedModelConfigId,
                normalizedRequestedModelConfigId,
                category,
                StringUtils.hasText(normalizedRequestedModelConfigId)
                        ? "REQUESTED_MODEL_GRANT"
                        : "DEFAULT_MODEL_GRANT");
        log.info("Resolved A2Agent model resource: tenantId={}, clientAppId={}, category={}, source={}, modelConfigId={}, requestedModelConfigId={}",
                tenantId,
                clientAppId,
                category,
                resolved.source(),
                resolved.modelConfigId(),
                resolved.requestedModelConfigId());
        return resolved;
    }

    @Transactional(readOnly = true)
    public String resolveOptionalModelConfigId(String tenantId,
                                               String clientAppId,
                                               LlmModelCategory category) {
        return resolveOptionalModel(tenantId, clientAppId, category)
                .map(ResolvedModelResource::modelConfigId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedModelResource> resolveOptionalModel(String tenantId,
                                                               String clientAppId,
                                                               LlmModelCategory category) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        return modelConfigGrantService.tryResolveEffectiveModelConfigId(tenantId, clientAppId, null, category)
                .map(modelConfigId -> {
                    ResolvedModelResource resolved = new ResolvedModelResource(
                            modelConfigId,
                            null,
                            category,
                            "DEFAULT_MODEL_GRANT");
                    log.info("Resolved optional A2Agent model resource: tenantId={}, clientAppId={}, category={}, source={}, modelConfigId={}",
                            tenantId,
                            clientAppId,
                            category,
                            resolved.source(),
                            resolved.modelConfigId());
                    return resolved;
                });
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
