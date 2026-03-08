package com.foggy.navigator.spi.config;

import com.foggy.navigator.common.dto.GitProviderConfigDTO;
import com.foggy.navigator.common.enums.GitProviderType;
import com.foggy.navigator.common.form.GitProviderConfigForm;

import java.util.List;
import java.util.Optional;

/**
 * Git 提供者管理接口（SPI）
 * 提供 Git 提供者配置的增删改查能力
 */
public interface GitProviderManager {

    /**
     * 保存 Git 提供者配置
     * @param tenantId 租户ID
     * @param form 配置表单
     * @return 保存后的配置ID
     */
    String saveGitProvider(String tenantId, GitProviderConfigForm form);

    /**
     * 更新 Git 提供者配置
     * @param id 配置ID
     * @param form 配置表单（仅更新非 null 字段）
     */
    void updateGitProvider(String id, GitProviderConfigForm form);

    /**
     * 删除 Git 提供者配置
     * @param id 配置ID
     */
    void deleteGitProvider(String id);

    /**
     * 获取租户的所有 Git 提供者
     * @param tenantId 租户ID
     * @return 配置列表（不含 token）
     */
    List<GitProviderConfigDTO> listGitProviders(String tenantId);

    /**
     * 获取单个 Git 提供者配置
     * @param id 配置ID
     * @return 配置（不含 token）
     */
    Optional<GitProviderConfigDTO> getGitProvider(String id);

    /**
     * 获取指定类型的活跃 Git 提供者
     * @param tenantId 租户ID
     * @param providerType 提供者类型
     * @return 配置（不含 token）
     */
    Optional<GitProviderConfigDTO> getActiveProvider(String tenantId, GitProviderType providerType);

    /**
     * 获取 Git 访问令牌（解密后）
     * 仅供内部服务调用，不对外暴露
     * @param id 配置ID
     * @return 解密后的访问令牌
     */
    String getDecryptedToken(String id);

    /**
     * 检查租户是否已配置任意 Git 提供者
     * @param tenantId 租户ID
     * @return true 如果至少配置了一个
     */
    boolean hasAnyProvider(String tenantId);
}
