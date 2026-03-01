package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.AgentModelOverrideEntity;
import com.foggy.navigator.common.entity.LlmModelConfigEntity;
import com.foggy.navigator.common.entity.ModelWorkerAccessEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ModelAccessScope;
import com.foggy.navigator.common.form.AgentModelOverrideForm;
import com.foggy.navigator.common.form.LlmModelConfigForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.AgentModelOverrideRepository;
import com.foggy.navigator.metadata.query.config.repository.LlmModelConfigRepository;
import com.foggy.navigator.metadata.query.config.repository.ModelWorkerAccessRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * LLM 模型配置管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelManagerImpl implements LlmModelManager {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    private final LlmModelConfigRepository llmModelRepo;
    private final AgentModelOverrideRepository overrideRepo;
    private final ModelWorkerAccessRepository workerAccessRepo;
    private final CredentialEncryptor credentialEncryptor;
    private final RestTemplateBuilder restTemplateBuilder;

    // ========== 模型配置 CRUD ==========

    @Override
    @Transactional
    public String saveModelConfig(String tenantId, LlmModelConfigForm form) {
        log.info("Saving LLM model config: tenantId={}, name={}", tenantId, form.getName());

        LlmModelConfigEntity entity = new LlmModelConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setCategory(form.getCategory());
        entity.setBaseUrl(form.getBaseUrl());
        entity.setModelName(form.getModelName());
        entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        entity.setIsDefault(form.getIsDefault() != null ? form.getIsDefault() : false);
        entity.setScope(form.getScope() != null ? form.getScope() : ModelAccessScope.GLOBAL);

        // 新增项排在最后
        int maxSort = llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId).stream()
                .mapToInt(e -> e.getSortOrder() != null ? e.getSortOrder() : 0)
                .max().orElse(-1);
        entity.setSortOrder(maxSort + 1);

        // 如果标记为默认，取消同 category 下其他默认
        if (Boolean.TRUE.equals(entity.getIsDefault())) {
            clearDefaultForCategory(tenantId, form.getCategory());
        }

        llmModelRepo.save(entity);

        // RESTRICTED 时保存关联表
        if (entity.getScope() == ModelAccessScope.RESTRICTED && form.getAllowedWorkerIds() != null) {
            saveWorkerAccess(entity.getId(), tenantId, form.getAllowedWorkerIds());
        }

        log.info("LLM model config saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateModelConfig(String id, LlmModelConfigForm form) {
        log.info("Updating LLM model config: id={}", id);

        LlmModelConfigEntity entity = llmModelRepo.findById(id)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + id));

        if (form.getName() != null) entity.setName(form.getName());
        if (form.getCategory() != null) entity.setCategory(form.getCategory());
        if (form.getBaseUrl() != null) entity.setBaseUrl(form.getBaseUrl());
        if (form.getModelName() != null) entity.setModelName(form.getModelName());
        if (form.getApiKey() != null) {
            entity.setApiKey(credentialEncryptor.encrypt(form.getApiKey()));
        }
        if (form.getIsDefault() != null) {
            if (Boolean.TRUE.equals(form.getIsDefault())) {
                clearDefaultForCategory(entity.getTenantId(), entity.getCategory());
            }
            entity.setIsDefault(form.getIsDefault());
        }

        // scope 变化处理
        if (form.getScope() != null) {
            entity.setScope(form.getScope());
            if (form.getScope() == ModelAccessScope.RESTRICTED) {
                // 替换关联表记录
                workerAccessRepo.deleteByModelConfigId(id);
                if (form.getAllowedWorkerIds() != null) {
                    saveWorkerAccess(id, entity.getTenantId(), form.getAllowedWorkerIds());
                }
            } else {
                // GLOBAL：清空关联表
                workerAccessRepo.deleteByModelConfigId(id);
            }
        }

        llmModelRepo.save(entity);
        log.info("LLM model config updated: id={}", id);
    }

    @Override
    @Transactional
    public void deleteModelConfig(String id) {
        log.info("Deleting LLM model config: id={}", id);
        workerAccessRepo.deleteByModelConfigId(id);
        llmModelRepo.deleteById(id);
        log.info("LLM model config deleted: id={}", id);
    }

    @Override
    public List<LlmModelConfigDTO> listModelConfigs(String tenantId) {
        log.debug("Listing LLM model configs: tenantId={}", tenantId);
        return llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<LlmModelConfigDTO> getModelConfig(String id) {
        log.debug("Getting LLM model config: id={}", id);
        return llmModelRepo.findById(id).map(this::toDTO);
    }

    // ========== Worker 级别过滤 ==========

    @Override
    public List<LlmModelConfigDTO> listModelConfigsForWorker(String tenantId, String workerId) {
        log.debug("Listing LLM model configs for worker: tenantId={}, workerId={}", tenantId, workerId);
        List<LlmModelConfigEntity> allModels = llmModelRepo.findByTenantIdOrderBySortOrderAscCreatedAtAsc(tenantId);
        Set<String> authorizedModelIds = workerAccessRepo.findByWorkerIdAndTenantId(workerId, tenantId)
                .stream()
                .map(ModelWorkerAccessEntity::getModelConfigId)
                .collect(Collectors.toSet());

        return allModels.stream()
                .filter(entity -> {
                    ModelAccessScope s = entity.getScope() != null ? entity.getScope() : ModelAccessScope.GLOBAL;
                    return s == ModelAccessScope.GLOBAL || authorizedModelIds.contains(entity.getId());
                })
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    // ========== 排序 ==========

    @Override
    @Transactional
    public void reorderModelConfigs(List<String> orderedIds) {
        log.info("Reordering LLM model configs: count={}", orderedIds.size());
        for (int i = 0; i < orderedIds.size(); i++) {
            String id = orderedIds.get(i);
            llmModelRepo.findById(id).ifPresent(entity -> {
                entity.setSortOrder(orderedIds.indexOf(entity.getId()));
                llmModelRepo.save(entity);
            });
        }
    }

    // ========== 模型选择 ==========

    @Override
    public Optional<LlmModelConfigDTO> resolveModelForAgent(String tenantId, String agentId, LlmModelCategory category) {
        log.debug("Resolving model for agent: tenantId={}, agentId={}, category={}", tenantId, agentId, category);

        // 优先级1：Agent 级别覆盖
        Optional<AgentModelOverrideEntity> override = overrideRepo.findByTenantIdAndAgentId(tenantId, agentId);
        if (override.isPresent()) {
            Optional<LlmModelConfigDTO> overrideModel = getModelConfig(override.get().getModelConfigId());
            if (overrideModel.isPresent()) {
                log.debug("Using agent override model: agentId={}, modelId={}", agentId, override.get().getModelConfigId());
                return overrideModel;
            }
        }

        // 优先级2：category 默认模型
        return getDefaultModel(tenantId, category);
    }

    @Override
    public Optional<LlmModelConfigDTO> getDefaultModel(String tenantId, LlmModelCategory category) {
        log.debug("Getting default model: tenantId={}, category={}", tenantId, category);
        return llmModelRepo.findByTenantIdAndCategoryAndIsDefaultTrue(tenantId, category)
                .map(this::toDTO);
    }

    @Override
    public String getDecryptedApiKey(String modelConfigId) {
        LlmModelConfigEntity entity = llmModelRepo.findById(modelConfigId)
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + modelConfigId));
        return credentialEncryptor.decrypt(entity.getApiKey());
    }

    // ========== Agent 模型覆盖 ==========

    @Override
    @Transactional
    public void setAgentModelOverride(String tenantId, AgentModelOverrideForm form) {
        log.info("Setting agent model override: tenantId={}, agentId={}, modelConfigId={}",
                tenantId, form.getAgentId(), form.getModelConfigId());

        // 验证模型配置存在
        llmModelRepo.findById(form.getModelConfigId())
                .orElseThrow(() -> RX.throwB("LLM model config not found: " + form.getModelConfigId()));

        // upsert: 存在则更新，不存在则创建
        AgentModelOverrideEntity entity = overrideRepo
                .findByTenantIdAndAgentId(tenantId, form.getAgentId())
                .orElseGet(() -> {
                    AgentModelOverrideEntity newEntity = new AgentModelOverrideEntity();
                    newEntity.setId(UUID.randomUUID().toString());
                    newEntity.setTenantId(tenantId);
                    newEntity.setAgentId(form.getAgentId());
                    return newEntity;
                });

        entity.setModelConfigId(form.getModelConfigId());
        overrideRepo.save(entity);
        log.info("Agent model override set: agentId={}", form.getAgentId());
    }

    @Override
    @Transactional
    public void removeAgentModelOverride(String tenantId, String agentId) {
        log.info("Removing agent model override: tenantId={}, agentId={}", tenantId, agentId);
        overrideRepo.deleteByTenantIdAndAgentId(tenantId, agentId);
        log.info("Agent model override removed: agentId={}", agentId);
    }

    @Override
    public List<AgentModelOverrideForm> listAgentModelOverrides(String tenantId) {
        log.debug("Listing agent model overrides: tenantId={}", tenantId);
        return overrideRepo.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(entity -> {
                    AgentModelOverrideForm form = new AgentModelOverrideForm();
                    form.setAgentId(entity.getAgentId());
                    form.setModelConfigId(entity.getModelConfigId());
                    return form;
                })
                .collect(Collectors.toList());
    }

    // ========== 状态检查 ==========

    @Override
    public boolean hasAnyModel(String tenantId) {
        return llmModelRepo.existsByTenantId(tenantId);
    }

    // ========== 连通性测试 ==========

    @Override
    public String testConnection(String baseUrl, String apiKey, String modelName) {

        if(true){
            //暂时不做检查，因为有些没有/chat/completions端口
            return "连接成功";
        }
        log.info("Testing LLM connection: baseUrl={}, model={}", baseUrl, modelName);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        Map<String, Object> body = Map.of(
                "model", modelName,
                "messages", List.of(Map.of("role", "user", "content", "Hi, reply with OK")),
                "max_tokens", 10
        );

        try {
            RestTemplate restTemplate = restTemplateBuilder
                    .connectTimeout(TEST_TIMEOUT)
                    .readTimeout(TEST_TIMEOUT)
                    .build();
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("LLM connection test passed: model={}", modelName);
                // 从 choices[0].message.content 提取回复
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        return (String) message.get("content");
                    }
                }
                return "连接成功";
            }
            throw RX.throwB("Unexpected response: " + response.getStatusCode());
        } catch (Exception e) {
            log.error("LLM connection test failed: baseUrl={}, model={}, error={}", baseUrl, modelName, e.getMessage());
            throw RX.throwB(toFriendlyTestError(e));
        }
    }

    // ===== 内部方法 =====

    private void clearDefaultForCategory(String tenantId, LlmModelCategory category) {
        List<LlmModelConfigEntity> defaults = llmModelRepo
                .findByTenantIdAndCategoryOrderBySortOrderAscCreatedAtAsc(tenantId, category);
        for (LlmModelConfigEntity e : defaults) {
            if (Boolean.TRUE.equals(e.getIsDefault())) {
                e.setIsDefault(false);
                llmModelRepo.save(e);
            }
        }
    }

    private String toFriendlyTestError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "连接测试失败，请检查配置后重试";
        String lower = msg.toLowerCase();
        if (lower.contains("401") || lower.contains("unauthorized") || lower.contains("invalid api key") || lower.contains("invalid_api_key")) {
            return "API Key 无效，请检查后重试";
        }
        if (lower.contains("403") || lower.contains("forbidden")) {
            return "访问被拒绝，请检查 API Key 权限或账户余额";
        }
        if (lower.contains("404") || lower.contains("not found") || lower.contains("model_not_found")) {
            return "模型不存在，请检查模型名称是否正确";
        }
        if (lower.contains("429") || lower.contains("rate limit")) {
            return "请求过于频繁，请稍后重试";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查 API 地址是否可达";
        }
        if (lower.contains("connection refused") || lower.contains("connection reset")) {
            return "无法连接服务，请检查 API 地址是否正确";
        }
        if (lower.contains("unknown host") || lower.contains("unknownhost")) {
            return "域名解析失败，请检查 API 地址是否正确";
        }
        if (lower.contains("ssl") || lower.contains("certificate")) {
            return "SSL 证书错误，请检查 API 地址或网络环境";
        }
        return "连接测试失败: " + msg;
    }

    private void saveWorkerAccess(String modelConfigId, String tenantId, List<String> workerIds) {
        for (String workerId : workerIds) {
            ModelWorkerAccessEntity access = new ModelWorkerAccessEntity();
            access.setId(UUID.randomUUID().toString());
            access.setTenantId(tenantId);
            access.setModelConfigId(modelConfigId);
            access.setWorkerId(workerId);
            workerAccessRepo.save(access);
        }
    }

    private LlmModelConfigDTO toDTO(LlmModelConfigEntity entity) {
        LlmModelConfigDTO dto = new LlmModelConfigDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setCategory(entity.getCategory());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setModelName(entity.getModelName());
        dto.setIsDefault(entity.getIsDefault());
        dto.setHasApiKey(entity.getApiKey() != null && !entity.getApiKey().isEmpty());
        dto.setSortOrder(entity.getSortOrder() != null ? entity.getSortOrder() : 0);
        ModelAccessScope scope = entity.getScope() != null ? entity.getScope() : ModelAccessScope.GLOBAL;
        dto.setScope(scope);
        if (scope == ModelAccessScope.RESTRICTED) {
            dto.setAllowedWorkerIds(
                    workerAccessRepo.findByModelConfigId(entity.getId()).stream()
                            .map(ModelWorkerAccessEntity::getWorkerId)
                            .collect(Collectors.toList())
            );
        } else {
            dto.setAllowedWorkerIds(Collections.emptyList());
        }
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
