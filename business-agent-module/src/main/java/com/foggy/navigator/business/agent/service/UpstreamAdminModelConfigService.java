package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.ClientAppModelConfigForm;
import com.foggy.navigator.business.agent.model.form.RotateModelConfigKeyForm;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UpstreamAdminModelConfigService {

    private final LlmModelManager llmModelManager;

    @Transactional(readOnly = true)
    public List<LlmModelConfigDTO> list(String tenantId, UpstreamClientAppAdminPrincipal principal) {
        requirePrincipal(principal);
        return llmModelManager.listModelConfigs(tenantId).stream()
                .filter(model -> isOwnedByPrincipal(model, principal))
                .toList();
    }

    @Transactional
    public LlmModelConfigDTO create(String tenantId,
                                    UpstreamClientAppAdminPrincipal principal,
                                    ClientAppModelConfigForm form) {
        requirePrincipal(principal);
        requireCreateForm(form);
        String modelConfigId = llmModelManager.saveModelConfig(
                tenantId,
                buildCreateModelForm(form),
                ResourceOwnerType.UPSTREAM_SYSTEM,
                principal.getUpstreamSystemId(),
                ResourceOwnerType.UPSTREAM_SYSTEM,
                principal.getUpstreamSystemId(),
                principal.getCredentialId());
        return requireModel(tenantId, principal, modelConfigId);
    }

    @Transactional
    public LlmModelConfigDTO update(String tenantId,
                                    UpstreamClientAppAdminPrincipal principal,
                                    String modelConfigId,
                                    ClientAppModelConfigForm form) {
        requireModel(tenantId, principal, modelConfigId);
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        llmModelManager.updateModelConfig(modelConfigId, buildUpdateModelForm(form, false));
        return requireModel(tenantId, principal, modelConfigId);
    }

    @Transactional
    public LlmModelConfigDTO rotateKey(String tenantId,
                                       UpstreamClientAppAdminPrincipal principal,
                                       String modelConfigId,
                                       RotateModelConfigKeyForm form) {
        requireModel(tenantId, principal, modelConfigId);
        if (form == null || !StringUtils.hasText(form.getApiKey())) {
            throw new IllegalArgumentException("apiKey is required");
        }
        LlmModelConfigForm modelForm = new LlmModelConfigForm();
        modelForm.setApiKey(form.getApiKey());
        llmModelManager.updateModelConfig(modelConfigId, modelForm);
        return requireModel(tenantId, principal, modelConfigId);
    }

    private LlmModelConfigDTO requireModel(String tenantId,
                                           UpstreamClientAppAdminPrincipal principal,
                                           String modelConfigId) {
        if (!StringUtils.hasText(modelConfigId)) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        LlmModelConfigDTO model = llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalArgumentException("model config not found: " + modelConfigId));
        if (!tenantId.equals(model.getTenantId())) {
            throw new IllegalArgumentException("model config tenant mismatch");
        }
        if (!isOwnedByPrincipal(model, principal)) {
            throw new IllegalArgumentException("model config is not owned by this upstream system");
        }
        return model;
    }

    private boolean isOwnedByPrincipal(LlmModelConfigDTO model, UpstreamClientAppAdminPrincipal principal) {
        return model != null
                && model.getOwnerType() == ResourceOwnerType.UPSTREAM_SYSTEM
                && StringUtils.hasText(principal.getUpstreamSystemId())
                && principal.getUpstreamSystemId().equals(model.getOwnerId());
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

    private void requirePrincipal(UpstreamClientAppAdminPrincipal principal) {
        if (principal == null || !StringUtils.hasText(principal.getUpstreamSystemId())) {
            throw new SecurityException("upstream admin principal is required");
        }
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
