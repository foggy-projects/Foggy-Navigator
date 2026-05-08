package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

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
        requireModelConfig(tenantId, form.getModelConfigId());

        grantRepository.findByClientAppIdAndModelConfigId(clientAppId, form.getModelConfigId()).ifPresent(existing -> {
            throw new IllegalArgumentException("model config already granted: " + form.getModelConfigId());
        });
        if (Boolean.TRUE.equals(form.getIsDefault())) {
            clearDefaults(clientAppId);
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
        requireModelConfig(tenantId, grant.getModelConfigId());
        clearDefaults(clientAppId);
        grant.setIsDefault(true);
        return toDTO(grantRepository.save(grant));
    }

    @Transactional(readOnly = true)
    public String resolveEffectiveModelConfigId(String tenantId, String clientAppId, String requestedModelConfigId) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppModelConfigGrantEntity grant;
        if (StringUtils.hasText(requestedModelConfigId)) {
            grant = grantRepository.findByClientAppIdAndModelConfigIdAndStatus(
                            clientAppId, requestedModelConfigId, STATUS_ENABLED)
                    .orElseThrow(() -> new IllegalArgumentException("requested model config is not granted"));
        } else {
            List<ClientAppModelConfigGrantEntity> defaults =
                    grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                            clientAppId, STATUS_ENABLED);
            if (defaults.isEmpty()) {
                throw new IllegalArgumentException("default model config grant is required");
            }
            grant = defaults.get(0);
        }
        requireModelConfig(tenantId, grant.getModelConfigId());
        return grant.getModelConfigId();
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
        if (!LANGGRAPH_BIZ_BACKEND.equals(model.getWorkerBackend())) {
            throw new IllegalArgumentException("model config worker backend must be " + LANGGRAPH_BIZ_BACKEND);
        }
        return model;
    }

    private void clearDefaults(String clientAppId) {
        List<ClientAppModelConfigGrantEntity> defaults =
                grantRepository.findByClientAppIdAndStatusAndIsDefaultTrueOrderByUpdatedAtDesc(
                        clientAppId, STATUS_ENABLED);
        defaults.forEach(defaultGrant -> defaultGrant.setIsDefault(false));
        grantRepository.saveAll(defaults);
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
}
