package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.dto.E2eModelConfigEnsureResultDTO;
import com.foggy.navigator.business.agent.model.form.EnsureE2eModelConfigForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class E2eModelConfigEnsureService {

    public static final String STANDARD_BIZ_WORKER = "biz-worker";
    public static final String MODEL_NAME = "navigator-e2e-scripted";
    public static final String MODEL_NAME_PREFIX = "Navigator E2E Test Model - ";

    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService grantService;
    private final LlmModelManager llmModelManager;

    @Transactional
    public E2eModelConfigEnsureResultDTO ensure(String tenantId, String actorUserId, String clientAppId,
                                                EnsureE2eModelConfigForm form) {
        requireText(tenantId, "tenantId is required");
        requireText(actorUserId, "actorUserId is required");
        requireText(clientAppId, "clientAppId is required");
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        String standard = normalizeStandard(form);
        String mockBaseUrl = normalizeMockBaseUrl(form);
        String configName = MODEL_NAME_PREFIX + clientAppId;

        ModelEnsureResult model = ensureModelConfig(tenantId, actorUserId, clientAppId, configName, mockBaseUrl, standard);
        GrantEnsureResult grant = ensureGrant(tenantId, actorUserId, clientAppId, model.modelConfigId(),
                form != null && Boolean.TRUE.equals(form.getSetDefault()));

        E2eModelConfigEnsureResultDTO result = new E2eModelConfigEnsureResultDTO();
        result.setClientAppId(clientAppId);
        result.setStandard(standard);
        result.setMockBaseUrl(mockBaseUrl);
        result.setModelConfigId(model.modelConfigId());
        result.setModelConfigName(configName);
        result.setModelCreated(model.created());
        result.setModelUpdated(model.updated());
        result.setGrantId(grant.grant().getId());
        result.setGrantCreated(grant.created());
        result.setGrantStatus(grant.grant().getStatus());
        result.setIsDefault(grant.grant().getIsDefault());
        return result;
    }

    private ModelEnsureResult ensureModelConfig(String tenantId,
                                                String actorUserId,
                                                String clientAppId,
                                                String configName,
                                                String mockBaseUrl,
                                                String standard) {
        Optional<LlmModelConfigDTO> existing = llmModelManager.listModelConfigs(tenantId).stream()
                .filter(model -> configName.equals(model.getName()))
                .filter(model -> ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(model.getWorkerBackend()))
                .filter(model -> model.getOwnerType() == ResourceOwnerType.CLIENT_APP
                        && clientAppId.equals(model.getOwnerId()))
                .findFirst();

        if (existing.isEmpty()) {
            String modelConfigId = llmModelManager.saveModelConfig(
                    tenantId,
                    buildModelForm(configName, mockBaseUrl, standard),
                    ResourceOwnerType.CLIENT_APP,
                    clientAppId,
                    ResourceOwnerType.CLIENT_APP,
                    clientAppId,
                    actorUserId);
            return new ModelEnsureResult(modelConfigId, true, false);
        }

        LlmModelConfigDTO model = existing.get();
        if (needsUpdate(model, mockBaseUrl)) {
            llmModelManager.updateModelConfig(model.getId(), buildModelForm(configName, mockBaseUrl, standard));
            return new ModelEnsureResult(model.getId(), false, true);
        }
        return new ModelEnsureResult(model.getId(), false, false);
    }

    private GrantEnsureResult ensureGrant(String tenantId, String actorUserId, String clientAppId,
                                          String modelConfigId, boolean setDefault) {
        Optional<ClientAppModelConfigGrantDTO> existing = grantService.listGrants(tenantId, clientAppId).stream()
                .filter(grant -> modelConfigId.equals(grant.getModelConfigId()))
                .findFirst();

        ClientAppModelConfigGrantDTO grant;
        boolean created = false;
        if (existing.isEmpty()) {
            GrantModelConfigForm grantForm = new GrantModelConfigForm();
            grantForm.setModelConfigId(modelConfigId);
            grantForm.setIsDefault(setDefault);
            grantForm.setGrantScope("APP");
            grant = grantService.grantModelConfig(tenantId, actorUserId, clientAppId, grantForm);
            created = true;
        } else {
            grant = existing.get();
            if (!ClientAppModelConfigGrantService.STATUS_ENABLED.equals(grant.getStatus())) {
                grant = grantService.updateStatus(tenantId, clientAppId, grant.getId(),
                        ClientAppModelConfigGrantService.STATUS_ENABLED);
            }
            if (setDefault && !Boolean.TRUE.equals(grant.getIsDefault())) {
                grant = grantService.setDefault(tenantId, clientAppId, grant.getId());
            }
        }
        return new GrantEnsureResult(grant, created);
    }

    private LlmModelConfigForm buildModelForm(String configName, String mockBaseUrl, String standard) {
        LlmModelConfigForm modelForm = new LlmModelConfigForm();
        modelForm.setName(configName);
        modelForm.setCategory(LlmModelCategory.GENERAL);
        modelForm.setBaseUrl(mockBaseUrl);
        modelForm.setModelName(MODEL_NAME);
        modelForm.setApiKey("navigator-e2e-test-key");
        modelForm.setIsDefault(false);
        modelForm.setScope(ModelAccessScope.GLOBAL);
        modelForm.setWorkerBackend(ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        modelForm.setAvailableModels(List.of(MODEL_NAME));
        modelForm.setEnvVars(Map.of(
                "NAVI_E2E_SCRIPTED", "true",
                "NAVI_LLM_PROVIDER", "openai",
                "NAVI_E2E_STANDARD", standard
        ));
        return modelForm;
    }

    private boolean needsUpdate(LlmModelConfigDTO model, String mockBaseUrl) {
        return !Objects.equals(mockBaseUrl, model.getBaseUrl())
                || model.getCategory() != LlmModelCategory.GENERAL
                || !MODEL_NAME.equals(model.getModelName())
                || !ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(model.getWorkerBackend())
                || !Boolean.TRUE.equals(model.getHasApiKey())
                || Boolean.TRUE.equals(model.getIsDefault());
    }

    private String normalizeStandard(EnsureE2eModelConfigForm form) {
        String standard = form == null ? null : form.getStandard();
        if (!StringUtils.hasText(standard)) {
            return STANDARD_BIZ_WORKER;
        }
        String normalized = standard.trim();
        if (!STANDARD_BIZ_WORKER.equals(normalized)) {
            throw new IllegalArgumentException("unsupported e2e standard: " + normalized);
        }
        return normalized;
    }

    private String normalizeMockBaseUrl(EnsureE2eModelConfigForm form) {
        String value = form == null ? null : form.getMockBaseUrl();
        requireText(value, "mockBaseUrl is required");
        String normalized = value.trim();
        URI uri = URI.create(normalized);
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("mockBaseUrl must be http(s)");
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.endsWith("/v1")) {
            normalized = normalized + "/v1";
        }
        return normalized;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private record ModelEnsureResult(String modelConfigId, boolean created, boolean updated) {
    }

    private record GrantEnsureResult(ClientAppModelConfigGrantDTO grant, boolean created) {
    }
}
