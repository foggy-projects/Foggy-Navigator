package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppModelConfigGrantEntity;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.model.form.RotateModelConfigKeyForm;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ClientAppOwnedModelConfigService {

    public static final String GRANT_SCOPE_CLIENT_APP_OWNED = "CLIENT_APP_OWNED";

    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService grantService;
    private final ClientAppModelConfigGrantRepository grantRepository;
    private final LlmModelManager llmModelManager;

    @Transactional
    public ClientAppModelConfigGrantDTO create(String tenantId, String actorUserId, String clientAppId,
                                               ClientAppModelConfigForm form) {
        requireCreateForm(form);
        clientAppService.requireClientApp(tenantId, clientAppId);

        String modelConfigId = llmModelManager.saveModelConfig(
                tenantId,
                buildCreateModelForm(form),
                ResourceOwnerType.CLIENT_APP,
                clientAppId,
                ResourceOwnerType.CLIENT_APP,
                clientAppId,
                actorUserId);

        GrantModelConfigForm grantForm = new GrantModelConfigForm();
        grantForm.setModelConfigId(modelConfigId);
        grantForm.setIsDefault(Boolean.TRUE.equals(form.getSetDefault()));
        grantForm.setGrantScope(GRANT_SCOPE_CLIENT_APP_OWNED);
        return grantService.grantModelConfig(tenantId, actorUserId, clientAppId, grantForm);
    }

    @Transactional
    public ClientAppModelConfigGrantDTO update(String tenantId, String clientAppId, String modelConfigId,
                                               ClientAppModelConfigForm form) {
        requireText(modelConfigId, "modelConfigId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        ClientAppModelConfigGrantEntity grant = requireOwnedGrant(tenantId, clientAppId, modelConfigId);
        llmModelManager.updateModelConfig(modelConfigId, buildUpdateModelForm(form, false));
        if (Boolean.TRUE.equals(form.getSetDefault())) {
            return grantService.setDefault(tenantId, clientAppId, grant.getId());
        }
        return ClientAppModelConfigGrantDTO.fromEntity(grant);
    }

    @Transactional
    public ClientAppModelConfigGrantDTO rotateKey(String tenantId, String clientAppId, String modelConfigId,
                                                  RotateModelConfigKeyForm form) {
        requireText(modelConfigId, "modelConfigId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getApiKey(), "apiKey is required");
        ClientAppModelConfigGrantEntity grant = requireOwnedGrant(tenantId, clientAppId, modelConfigId);
        LlmModelConfigForm modelForm = new LlmModelConfigForm();
        modelForm.setApiKey(form.getApiKey());
        llmModelManager.updateModelConfig(modelConfigId, modelForm);
        return ClientAppModelConfigGrantDTO.fromEntity(grant);
    }

    private ClientAppModelConfigGrantEntity requireOwnedGrant(String tenantId, String clientAppId, String modelConfigId) {
        clientAppService.requireClientApp(tenantId, clientAppId);
        ClientAppModelConfigGrantEntity grant = grantRepository
                .findByClientAppIdAndModelConfigId(clientAppId, modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("model config is not granted to this ClientApp"));
        if (!tenantId.equals(grant.getTenantId())) {
            throw new IllegalArgumentException("grant tenant mismatch");
        }
        if (!GRANT_SCOPE_CLIENT_APP_OWNED.equals(grant.getGrantScope())) {
            throw new IllegalArgumentException("model config is not owned by this ClientApp");
        }
        return grant;
    }

    private LlmModelConfigForm buildCreateModelForm(ClientAppModelConfigForm form) {
        LlmModelConfigForm modelForm = buildUpdateModelForm(form, true);
        modelForm.setApiKey(form.getApiKey());
        modelForm.setIsDefault(false);
        modelForm.setScope(ModelAccessScope.GLOBAL);
        return modelForm;
    }

    private LlmModelConfigForm buildUpdateModelForm(ClientAppModelConfigForm form, boolean create) {
        LlmModelConfigForm modelForm = new LlmModelConfigForm();
        modelForm.setName(trimToNull(form.getName()));
        modelForm.setCategory(form.getCategory() != null ? form.getCategory() : (create ? LlmModelCategory.GENERAL : null));
        modelForm.setBaseUrl(trimToNull(form.getBaseUrl()));
        modelForm.setModelName(trimToNull(form.getModelName()));
        modelForm.setEnvVars(normalizeEnvVars(form.getEnvVars()));
        modelForm.setAvailableModels(form.getAvailableModels() != null && !form.getAvailableModels().isEmpty()
                ? form.getAvailableModels()
                : (StringUtils.hasText(form.getModelName()) ? List.of(form.getModelName().trim()) : null));
        modelForm.setRuntimeBudgetPresetKey(trimToNull(form.getRuntimeBudgetPresetKey()));
        modelForm.setRuntimeBudgetOverrideJson(trimToNull(form.getRuntimeBudgetOverrideJson()));
        modelForm.setWorkerBackend(resolveWorkerBackend(form.getWorkerBackend(), create));
        return modelForm;
    }

    private String resolveWorkerBackend(String workerBackend, boolean create) {
        String normalized = ClientAppModelConfigGrantService.normalizeWorkerBackend(workerBackend);
        if (normalized == null) {
            return create ? ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND : null;
        }
        if (!ClientAppModelConfigGrantService.isSupportedWorkerBackend(normalized)) {
            throw new IllegalArgumentException(
                    "workerBackend must be LANGGRAPH_BIZ, CLAUDE_CODE, OPENAI_CODEX, or GEMINI_CLI");
        }
        return normalized;
    }

    private Map<String, String> normalizeEnvVars(Map<String, String> envVars) {
        if (envVars == null) {
            return null;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        envVars.forEach((key, value) -> {
            if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                normalized.put(key.trim(), value.trim());
            }
        });
        return normalized;
    }

    private void requireCreateForm(ClientAppModelConfigForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getName(), "name is required");
        requireText(form.getBaseUrl(), "baseUrl is required");
        requireText(form.getModelName(), "modelName is required");
        requireText(form.getApiKey(), "apiKey is required");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
