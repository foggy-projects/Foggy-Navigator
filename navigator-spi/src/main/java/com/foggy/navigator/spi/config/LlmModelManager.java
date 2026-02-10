package com.foggy.navigator.spi.config;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.form.AgentModelOverrideForm;
import com.foggy.navigator.common.form.LlmModelConfigForm;

import java.util.List;
import java.util.Optional;

/**
 * LLM 模型管理接口（SPI）
 * 提供 LLM 模型配置的增删改查 + Agent 级别模型覆盖
 *
 * 模型选择优先级：Agent 覆盖 → category 默认 → application.yml 兜底
 */
public interface LlmModelManager {

    // ========== 模型配置 CRUD ==========

    /**
     * 保存 LLM 模型配置
     * @param tenantId 租户ID
     * @param form 配置表单
     * @return 保存后的配置ID
     */
    String saveModelConfig(String tenantId, LlmModelConfigForm form);

    /**
     * 更新 LLM 模型配置
     * @param id 配置ID
     * @param form 配置表单（仅更新非 null 字段）
     */
    void updateModelConfig(String id, LlmModelConfigForm form);

    /**
     * 删除 LLM 模型配置
     * @param id 配置ID
     */
    void deleteModelConfig(String id);

    /**
     * 获取租户的所有模型配置
     * @param tenantId 租户ID
     * @return 配置列表（不含 API Key）
     */
    List<LlmModelConfigDTO> listModelConfigs(String tenantId);

    /**
     * 获取单个模型配置
     * @param id 配置ID
     * @return 配置（不含 API Key）
     */
    Optional<LlmModelConfigDTO> getModelConfig(String id);

    // ========== 模型选择 ==========

    /**
     * 获取 Agent 应使用的模型配置（核心方法）
     * 优先级：Agent 覆盖 → category 默认 → 空
     *
     * @param tenantId 租户ID
     * @param agentId Agent ID
     * @param category 模型类别
     * @return 模型配置（不含 API Key）
     */
    Optional<LlmModelConfigDTO> resolveModelForAgent(String tenantId, String agentId, LlmModelCategory category);

    /**
     * 获取指定 category 的默认模型
     * @param tenantId 租户ID
     * @param category 模型类别
     * @return 默认模型配置
     */
    Optional<LlmModelConfigDTO> getDefaultModel(String tenantId, LlmModelCategory category);

    /**
     * 获取解密后的 API Key
     * 仅供内部服务调用
     * @param modelConfigId 模型配置ID
     * @return 解密后的 API Key
     */
    String getDecryptedApiKey(String modelConfigId);

    // ========== Agent 模型覆盖 ==========

    /**
     * 设置 Agent 级别的模型覆盖
     * @param tenantId 租户ID
     * @param form 覆盖配置表单
     */
    void setAgentModelOverride(String tenantId, AgentModelOverrideForm form);

    /**
     * 删除 Agent 级别的模型覆盖（恢复使用默认模型）
     * @param tenantId 租户ID
     * @param agentId Agent ID
     */
    void removeAgentModelOverride(String tenantId, String agentId);

    /**
     * 获取所有 Agent 模型覆盖配置
     * @param tenantId 租户ID
     * @return 覆盖配置列表
     */
    List<AgentModelOverrideForm> listAgentModelOverrides(String tenantId);

    // ========== 状态检查 ==========

    /**
     * 检查租户是否已配置任意 LLM 模型
     * @param tenantId 租户ID
     * @return true 如果至少配置了一个
     */
    boolean hasAnyModel(String tenantId);

    // ========== 连通性测试 ==========

    /**
     * 测试 LLM 模型连通性
     * 发送一条简单 prompt 验证 apiKey/baseUrl/modelName 是否可用
     *
     * @param baseUrl API Base URL
     * @param apiKey API Key（明文）
     * @param modelName 模型名称
     * @return 成功时返回模型回复片段，失败时抛出异常
     */
    String testConnection(String baseUrl, String apiKey, String modelName);
}
