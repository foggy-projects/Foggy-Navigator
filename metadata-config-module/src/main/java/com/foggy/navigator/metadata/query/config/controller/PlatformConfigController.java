package com.foggy.navigator.metadata.query.config.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.*;
import com.foggy.navigator.common.form.AgentModelOverrideForm;
import com.foggy.navigator.common.form.GitProviderConfigForm;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.spi.config.GitProviderManager;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 平台配置管理 REST API
 * 包含：Git 提供者、LLM 模型、Agent 模型覆盖、初始化状态
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/config/platform")
@RequiredArgsConstructor
@RequireAuth
public class PlatformConfigController {

    private final GitProviderManager gitProviderManager;
    private final LlmModelManager llmModelManager;

    /**
     * 获取当前用户的 tenantId，SUPER_ADMIN 无 tenantId 时用 userId 兜底
     */
    private String resolveTenantId() {
        CurrentUser user = UserContext.getCurrentUser();
        String tenantId = user.getTenantId();
        return (tenantId != null && !tenantId.isEmpty()) ? tenantId : user.getUserId();
    }

    // ========== 初始化状态 ==========

    @GetMapping("/setup-status")
    public RX<SetupStatusDTO> getSetupStatus() {
        String tenantId = resolveTenantId();
        log.info("Get setup status: tenantId={}", tenantId);

        SetupStatusDTO status = new SetupStatusDTO();
        status.setGitConfigured(gitProviderManager.hasAnyProvider(tenantId));
        status.setLlmConfigured(llmModelManager.hasAnyModel(tenantId));
        status.setSetupComplete(status.isGitConfigured() && status.isLlmConfigured());
        return RX.ok(status);
    }

    // ========== Git 提供者 ==========

    @PostMapping("/git")
    public RX<String> saveGitProvider(@RequestBody GitProviderConfigForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Save git provider: type={}, operator={}", form.getProviderType(), user.getUsername());
        String id = gitProviderManager.saveGitProvider(resolveTenantId(), form);
        return RX.ok(id);
    }

    @PutMapping("/git/{id}")
    public RX<Void> updateGitProvider(@PathVariable String id, @RequestBody GitProviderConfigForm form) {
        log.info("Update git provider: id={}", id);
        gitProviderManager.updateGitProvider(id, form);
        return RX.ok();
    }

    @DeleteMapping("/git/{id}")
    public RX<Void> deleteGitProvider(@PathVariable String id) {
        log.info("Delete git provider: id={}", id);
        gitProviderManager.deleteGitProvider(id);
        return RX.ok();
    }

    @GetMapping("/git")
    public RX<List<GitProviderConfigDTO>> listGitProviders() {
        String tenantId = resolveTenantId();
        log.info("List git providers: tenantId={}", tenantId);
        List<GitProviderConfigDTO> list = gitProviderManager.listGitProviders(tenantId);
        return RX.ok(list);
    }

    @GetMapping("/git/{id}")
    public RX<GitProviderConfigDTO> getGitProvider(@PathVariable String id) {
        log.info("Get git provider: id={}", id);
        GitProviderConfigDTO dto = gitProviderManager.getGitProvider(id)
                .orElseThrow(() -> RX.throwB("Git provider not found: " + id));
        return RX.ok(dto);
    }

    // ========== LLM 模型配置 ==========

    @PostMapping("/llm/test-connection")
    public RX<String> testLlmConnection(@RequestBody LlmModelConfigForm form) {
        log.info("Test LLM connection: baseUrl={}, model={}", form.getBaseUrl(), form.getModelName());
        String reply = llmModelManager.testConnection(form.getBaseUrl(), form.getApiKey(), form.getModelName());
        return RX.ok(reply);
    }

    @PostMapping("/llm/{id}/test-connection")
    public RX<String> testSavedLlmConnection(@PathVariable String id) {
        log.info("Test saved LLM connection: id={}", id);
        LlmModelConfigDTO dto = llmModelManager.getModelConfig(id)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + id));
        String apiKey = llmModelManager.getDecryptedApiKey(id);
        String reply = llmModelManager.testConnection(dto.getBaseUrl(), apiKey, dto.getModelName());
        return RX.ok(reply);
    }

    @PostMapping("/llm")
    public RX<String> saveModelConfig(@RequestBody LlmModelConfigForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Save LLM model: name={}, operator={}", form.getName(), user.getUsername());
        String id = llmModelManager.saveModelConfig(resolveTenantId(), form);
        return RX.ok(id);
    }

    @PutMapping("/llm/{id}")
    public RX<Void> updateModelConfig(@PathVariable String id, @RequestBody LlmModelConfigForm form) {
        log.info("Update LLM model: id={}", id);
        llmModelManager.updateModelConfig(id, form);
        return RX.ok();
    }

    @DeleteMapping("/llm/{id}")
    public RX<Void> deleteModelConfig(@PathVariable String id) {
        log.info("Delete LLM model: id={}", id);
        llmModelManager.deleteModelConfig(id);
        return RX.ok();
    }

    @GetMapping("/llm")
    public RX<List<LlmModelConfigDTO>> listModelConfigs() {
        String tenantId = resolveTenantId();
        log.info("List LLM models: tenantId={}", tenantId);
        List<LlmModelConfigDTO> list = llmModelManager.listModelConfigs(tenantId);
        return RX.ok(list);
    }

    @GetMapping("/llm/{id}")
    public RX<LlmModelConfigDTO> getModelConfig(@PathVariable String id) {
        log.info("Get LLM model: id={}", id);
        LlmModelConfigDTO dto = llmModelManager.getModelConfig(id)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + id));
        return RX.ok(dto);
    }

    // ========== Agent 模型覆盖 ==========

    @PostMapping("/agent-model")
    public RX<Void> setAgentModelOverride(@RequestBody AgentModelOverrideForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Set agent model override: agentId={}, modelConfigId={}, operator={}",
                form.getAgentId(), form.getModelConfigId(), user.getUsername());
        llmModelManager.setAgentModelOverride(resolveTenantId(), form);
        return RX.ok();
    }

    @DeleteMapping("/agent-model/{agentId}")
    public RX<Void> removeAgentModelOverride(@PathVariable String agentId) {
        log.info("Remove agent model override: agentId={}", agentId);
        llmModelManager.removeAgentModelOverride(resolveTenantId(), agentId);
        return RX.ok();
    }

    @GetMapping("/agent-model")
    public RX<List<AgentModelOverrideForm>> listAgentModelOverrides() {
        String tenantId = resolveTenantId();
        log.info("List agent model overrides: tenantId={}", tenantId);
        List<AgentModelOverrideForm> list = llmModelManager.listAgentModelOverrides(tenantId);
        return RX.ok(list);
    }
}
