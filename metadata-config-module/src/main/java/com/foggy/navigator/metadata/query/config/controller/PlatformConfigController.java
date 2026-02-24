package com.foggy.navigator.metadata.query.config.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.*;
import com.foggy.navigator.common.enums.AuthType;
import com.foggy.navigator.common.enums.UserMemorySource;
import com.foggy.navigator.common.form.AgentModelOverrideForm;
import com.foggy.navigator.common.form.ApiCredentialForm;
import com.foggy.navigator.common.form.GitProviderConfigForm;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.common.form.UserMemoryForm;
import com.foggy.navigator.spi.config.ApiCredentialManager;
import com.foggy.navigator.spi.config.GitProviderManager;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.memory.UserMemoryManager;
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
    private final UserMemoryManager userMemoryManager;
    private final ApiCredentialManager apiCredentialManager;

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
        status.setCredentialConfigured(apiCredentialManager.hasAnyCredential(tenantId));
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

    // ========== API 凭证配置 ==========

    @PostMapping("/credentials/test-connection")
    public RX<String> testCredentialConnection(@RequestBody ApiCredentialForm form) {
        log.info("Test credential connection: baseUrl={}, authType={}", form.getBaseUrl(), form.getAuthType());
        String authType = form.getAuthType() != null ? form.getAuthType().name() : AuthType.API_KEY.name();
        String reply = apiCredentialManager.testConnection(
                form.getBaseUrl(), form.getApiKey(), authType, form.getAuthHeaderName()
        );
        return RX.ok(reply);
    }

    @PostMapping("/credentials/{id}/test-connection")
    public RX<String> testSavedCredentialConnection(@PathVariable String id) {
        log.info("Test saved credential connection: id={}", id);
        ApiCredentialDTO dto = apiCredentialManager.getCredential(id)
                .orElseThrow(() -> RX.throwB("API credential not found: " + id));
        String apiKey = apiCredentialManager.getDecryptedApiKey(id);
        String reply = apiCredentialManager.testConnection(
                dto.getBaseUrl(), apiKey, dto.getAuthType().name(), dto.getAuthHeaderName()
        );
        return RX.ok(reply);
    }

    @PostMapping("/credentials")
    public RX<String> saveCredential(@RequestBody ApiCredentialForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Save API credential: name={}, operator={}", form.getName(), user.getUsername());
        String id = apiCredentialManager.saveCredential(resolveTenantId(), form);
        return RX.ok(id);
    }

    @PutMapping("/credentials/{id}")
    public RX<Void> updateCredential(@PathVariable String id, @RequestBody ApiCredentialForm form) {
        log.info("Update API credential: id={}", id);
        apiCredentialManager.updateCredential(id, form);
        return RX.ok();
    }

    @DeleteMapping("/credentials/{id}")
    public RX<Void> deleteCredential(@PathVariable String id) {
        log.info("Delete API credential: id={}", id);
        apiCredentialManager.deleteCredential(id);
        return RX.ok();
    }

    @GetMapping("/credentials")
    public RX<List<ApiCredentialDTO>> listCredentials() {
        String tenantId = resolveTenantId();
        log.info("List API credentials: tenantId={}", tenantId);
        List<ApiCredentialDTO> list = apiCredentialManager.listCredentials(tenantId);
        return RX.ok(list);
    }

    @GetMapping("/credentials/{id}")
    public RX<ApiCredentialDTO> getCredential(@PathVariable String id) {
        log.info("Get API credential: id={}", id);
        ApiCredentialDTO dto = apiCredentialManager.getCredential(id)
                .orElseThrow(() -> RX.throwB("API credential not found: " + id));
        return RX.ok(dto);
    }

    @GetMapping("/credentials/category/{category}")
    public RX<List<ApiCredentialDTO>> listCredentialsByCategory(@PathVariable String category) {
        String tenantId = resolveTenantId();
        log.info("List API credentials by category: tenantId={}, category={}", tenantId, category);
        List<ApiCredentialDTO> list = apiCredentialManager.listCredentialsByCategory(tenantId, category);
        return RX.ok(list);
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

    // ========== 用户记忆 ==========

    @GetMapping("/memories")
    public RX<List<UserMemoryDTO>> listMemories() {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("List user memories: userId={}", user.getUserId());
        List<UserMemoryDTO> list = userMemoryManager.listMemories(user.getUserId());
        return RX.ok(list);
    }

    @PostMapping("/memories")
    public RX<String> saveMemory(@RequestBody UserMemoryForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Save user memory: userId={}, category={}", user.getUserId(), form.getCategory());
        String id = userMemoryManager.saveMemory(user.getUserId(), resolveTenantId(), form, UserMemorySource.MANUAL);
        return RX.ok(id);
    }

    @PutMapping("/memories/{id}")
    public RX<Void> updateMemory(@PathVariable String id, @RequestBody UserMemoryForm form) {
        log.info("Update user memory: id={}", id);
        userMemoryManager.updateMemory(id, form);
        return RX.ok();
    }

    @DeleteMapping("/memories/{id}")
    public RX<Void> deleteMemory(@PathVariable String id) {
        log.info("Delete user memory: id={}", id);
        userMemoryManager.deleteMemory(id);
        return RX.ok();
    }
}
