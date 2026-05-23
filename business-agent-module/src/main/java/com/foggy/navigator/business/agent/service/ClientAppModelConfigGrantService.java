package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ClientAppModelConfigGrantService {

    public static final String LANGGRAPH_BIZ_BACKEND = "LANGGRAPH_BIZ";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final ClientAppModelConfigGrantRepository grantRepository;
    private final ClientAppService clientAppService;
    private final LlmModelManager llmModelManager;

    @Transactional(readOnly = true)
    public List<ClientAppModelConfigGrantDTO> listGrants(String tenantId, String clientAppId) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        return grantRepository.findByClientAppIdOrderByCreatedAtDesc(clientAppId).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public ClientAppModelConfigGrantDTO grantModelConfig(String tenantId, String actorUserId,
                                                         String clientAppId, GrantModelConfigForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getModelConfigId(), "modelConfigId is required");
        clientAppService.requireClientApp(tenantId, clientAppId);
        LlmModelConfigDTO model = requireModelConfig(tenantId, form.getModelConfigId());

        ClientAppModelConfigGrantEntity existing = grantRepository
                .findByClientAppIdAndModelConfigId(clientAppId, form.getModelConfigId())
                .orElse(null);
        if (existing != null) {
            return ensureExistingGrant(tenantId, clientAppId, existing, model, form);
        }
        if (Boolean.TRUE.equals(form.getIsDefault())) {
            clearDefaults(clientAppId, model.getCategory());
        }

        ClientAppModelConfigGrantEntity entity = new ClientAppModelConfigGrantEntity();
        entity.setClientAppId(clientAppId);
        entity.setTenantId(tenantId);
        entity.setModelConfigId(form.getModelConfigId());
        entity.setStatus(STATUS_ENABLED);
        entity.setIsDefault(Boolean.TRUE.equals(form.getIsDefault()));
        entity.setGrantScope(StringUtils.hasText(form.getGrantScope()) ? form.getGrantScope() : "APP");
        entity.setCreatedBy(actorUserId);
        return toDTO(grantRepository.save(entity));
    }

    private ClientAppModelConfigGrantDTO ensureExistingGrant(String tenantId,
                                                             String clientAppId,
                                                             ClientAppModelConfigGrantEntity existing,
                                                             LlmModelConfigDTO model,
                                                             GrantModelConfigForm form) {
        if (!tenantId.equals(existing.getTenantId())) {
            throw new IllegalArgumentException("grant tenant mismatch");
        }
        boolean changed = false;
        if (!STATUS_ENABLED.equals(existing.getStatus())) {
            existing.setStatus(STATUS_ENABLED);
            changed = true;
        }
        if (Boolean.TRUE.equals(form.getIsDefault()) && !Boolean.TRUE.equals(existing.getIsDefault())) {
            clearDefaults(clientAppId, model.getCategory());
            existing.setIsDefault(true);
            changed = true;
        }
        return toDTO(changed ? grantRepository.save(existing) : existing);
    }

    @Transactional
    public ClientAppModelConfigGrantDTO updateStatus(String tenantId, String clientAppId, Long grantId, String status) {
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("unsupported grant status: " + status);
        }
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppModelConfigGrantEntity grant = requireGrant(clientAppId, grantId);
        if (!tenantId.equals(grant.getTenantId())) {
            throw new IllegalArgumentException("grant tenant mismatch");
        }
        grant.setStatus(status);
        if (STATUS_DISABLED.equals(status)) {
            grant.setIsDefault(false);
        }
        return toDTO(grantRepository.save(grant));
    }

    @Transactional
    public ClientAppModelConfigGrantDTO setDefault(String tenantId, String clientAppId, Long grantId) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppModelConfigGrantEntity grant = requireGrant(clientAppId, grantId);
        if (!tenantId.equals(grant.getTenantId())) {
            throw new IllegalArgumentException("grant tenant mismatch");
        }
        if (!STATUS_ENABLED.equals(grant.getStatus())) {
            throw new IllegalArgumentException("disabled grant cannot be default");
        }
        LlmModelConfigDTO model = requireModelConfig(tenantId, grant.getModelConfigId());
        clearDefaults(clientAppId, model.getCategory());
        grant.setIsDefault(true);
        return toDTO(grantRepository.save(grant));
    }

    @Transactional(readOnly = true)
    public String resolveEffectiveModelConfigId(String tenantId, String clientAppId, String requestedModelConfigId) {
        return resolveEffectiveModelConfigId(tenantId, clientAppId, requestedModelConfigId, null);
    }

    @Transactional(readOnly = true)
    public String resolveEffectiveModelConfigId(String tenantId, String clientAppId,
                                                String requestedModelConfigId,
                                                LlmModelCategory category) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppModelConfigGrantEntity grant;
        if (StringUtils.hasText(requestedModelConfigId)) {
            grant = grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                            clientAppId, requestedModelConfigId, STATUS_ENABLED)
                    .orElseThrow(() -> new IllegalArgumentException("requested model config is not granted"));
            LlmModelConfigDTO model = requireModelConfig(tenantId, grant.getModelConfigId());
            requireCategory(model, category);
            return grant.getModelConfigId();
        } else {
            List<ClientAppModelConfigGrantEntity> defaults =
                    grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                            clientAppId, STATUS_ENABLED);
            grant = defaults.stream()
                    .filter(defaultGrant -> matchesDefaultBucket(tenantId, defaultGrant, category))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(defaultModelRequiredMessage(category)));
        }
        requireModelConfig(tenantId, grant.getModelConfigId());
        return grant.getModelConfigId();
    }

    @Transactional(readOnly = true)
    public Optional<String> tryResolveEffectiveModelConfigId(String tenantId, String clientAppId,
                                                            String requestedModelConfigId,
                                                            LlmModelCategory category) {
        try {
            return Optional.of(resolveEffectiveModelConfigId(tenantId, clientAppId, requestedModelConfigId, category));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private ClientAppModelConfigGrantEntity requireGrant(String clientAppId, Long grantId) {
        if (grantId == null) {
            throw new IllegalArgumentException("grantId is required");
        }
        return grantRepository.findByIdAndClientAppId(grantId, clientAppId)
                .orElseThrow(() -> new IllegalArgumentException("grant not found: " + grantId));
    }

    private LlmModelConfigDTO requireModelConfig(String tenantId, String modelConfigId) {
        LlmModelConfigDTO model = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("model config not found: " + modelConfigId));
        if (!tenantId.equals(model.getTenantId())) {
            throw new IllegalArgumentException("model config tenant mismatch");
        }
        String backend = model.getWorkerBackend();
        boolean isValidBackend = LANGGRAPH_BIZ_BACKEND.equals(backend)
                || "CLAUDE_CODE".equals(backend)
                || "OPENAI_CODEX".equals(backend)
                || "GEMINI_CLI".equals(backend);
        if (!isValidBackend) {
            throw new IllegalArgumentException("model config worker backend must be LANGGRAPH_BIZ_BACKEND, CLAUDE_CODE, OPENAI_CODEX, or GEMINI_CLI");
        }
        return model;
    }

    private void clearDefaults(String clientAppId, LlmModelCategory category) {
        List<ClientAppModelConfigGrantEntity> defaults =
                grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                        clientAppId, STATUS_ENABLED);
        List<ClientAppModelConfigGrantEntity> sameCategoryDefaults = defaults.stream()
                .filter(defaultGrant -> matchesDefaultBucket(defaultGrant.getTenantId(), defaultGrant, category))
                .toList();
        sameCategoryDefaults.forEach(defaultGrant -> defaultGrant.setIsDefault(false));
        grantRepository.saveAll(sameCategoryDefaults);
    }

    private ClientAppModelConfigGrantDTO toDTO(ClientAppModelConfigGrantEntity entity) {
        ClientAppModelConfigGrantDTO dto = ClientAppModelConfigGrantDTO.fromEntity(entity);
        llmModelManager.getModelConfig(entity.getModelConfigId()).ifPresent(model -> {
            dto.setModelConfigName(model.getName());
            dto.setWorkerBackend(model.getWorkerBackend());
        });
        return dto;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private boolean matchesDefaultBucket(String tenantId, ClientAppModelConfigGrantEntity grant, LlmModelCategory category) {
        try {
            LlmModelConfigDTO model = requireModelConfig(tenantId, grant.getModelConfigId());
            if (category == LlmModelCategory.VISION) {
                return model.getCategory() == LlmModelCategory.VISION;
            }
            if (category == null || category == LlmModelCategory.GENERAL) {
                return model.getCategory() != LlmModelCategory.VISION;
            }
            return model.getCategory() == category;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void requireCategory(LlmModelConfigDTO model, LlmModelCategory category) {
        if (category != null && model.getCategory() != category) {
            throw new IllegalArgumentException("requested model config category must be " + category);
        }
    }

    private String defaultModelRequiredMessage(LlmModelCategory category) {
        return category == null
                ? "default model config grant is required"
                : "default " + category + " model config grant is required";
    }
}
