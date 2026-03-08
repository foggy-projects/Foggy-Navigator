package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.dto.GitProviderConfigDTO;
import com.foggy.navigator.common.entity.GitProviderConfigEntity;
import com.foggy.navigator.common.enums.GitProviderType;
import com.foggy.navigator.common.form.GitProviderConfigForm;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.metadata.query.config.repository.GitProviderConfigRepository;
import com.foggy.navigator.spi.config.GitProviderManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Git 提供者配置管理实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitProviderManagerImpl implements GitProviderManager {

    private final GitProviderConfigRepository gitProviderRepo;
    private final CredentialEncryptor credentialEncryptor;

    @Override
    @Transactional
    public String saveGitProvider(String tenantId, GitProviderConfigForm form) {
        log.info("Saving git provider: tenantId={}, type={}", tenantId, form.getProviderType());

        // 同一租户同一类型只允许一个配置，存在则更新
        Optional<GitProviderConfigEntity> existing = gitProviderRepo
                .findByTenantIdAndProviderType(tenantId, form.getProviderType());
        if (existing.isPresent()) {
            String existingId = existing.get().getId();
            log.info("Git provider already exists, updating: id={}", existingId);
            updateGitProvider(existingId, form);
            return existingId;
        }

        GitProviderConfigEntity entity = new GitProviderConfigEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(tenantId);
        entity.setProviderType(form.getProviderType());
        entity.setBaseUrl(form.getBaseUrl());
        entity.setAccessToken(credentialEncryptor.encrypt(form.getAccessToken()));
        entity.setUsername(form.getUsername());
        entity.setIsActive(true);

        gitProviderRepo.save(entity);
        log.info("Git provider saved: id={}", entity.getId());
        return entity.getId();
    }

    @Override
    @Transactional
    public void updateGitProvider(String id, GitProviderConfigForm form) {
        log.info("Updating git provider: id={}", id);

        GitProviderConfigEntity entity = gitProviderRepo.findById(id)
                .orElseThrow(() -> RX.throwB("Git provider not found: " + id));

        if (form.getProviderType() != null) entity.setProviderType(form.getProviderType());
        if (form.getBaseUrl() != null) entity.setBaseUrl(form.getBaseUrl());
        if (form.getAccessToken() != null) {
            entity.setAccessToken(credentialEncryptor.encrypt(form.getAccessToken()));
        }
        if (form.getUsername() != null) entity.setUsername(form.getUsername());

        gitProviderRepo.save(entity);
        log.info("Git provider updated: id={}", id);
    }

    @Override
    @Transactional
    public void deleteGitProvider(String id) {
        log.info("Deleting git provider: id={}", id);
        gitProviderRepo.deleteById(id);
        log.info("Git provider deleted: id={}", id);
    }

    @Override
    public List<GitProviderConfigDTO> listGitProviders(String tenantId) {
        log.debug("Listing git providers: tenantId={}", tenantId);
        return gitProviderRepo.findByTenantIdOrderByCreatedAtAsc(tenantId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<GitProviderConfigDTO> getGitProvider(String id) {
        log.debug("Getting git provider: id={}", id);
        return gitProviderRepo.findById(id).map(this::toDTO);
    }

    @Override
    public Optional<GitProviderConfigDTO> getActiveProvider(String tenantId, GitProviderType providerType) {
        log.debug("Getting active git provider: tenantId={}, type={}", tenantId, providerType);
        return gitProviderRepo.findByTenantIdAndProviderTypeAndIsActiveTrue(tenantId, providerType)
                .map(this::toDTO);
    }

    @Override
    public String getDecryptedToken(String id) {
        GitProviderConfigEntity entity = gitProviderRepo.findById(id)
                .orElseThrow(() -> RX.throwB("Git provider not found: " + id));
        return credentialEncryptor.decrypt(entity.getAccessToken());
    }

    @Override
    public boolean hasAnyProvider(String tenantId) {
        return gitProviderRepo.existsByTenantId(tenantId);
    }

    // ===== 转换方法 =====

    private GitProviderConfigDTO toDTO(GitProviderConfigEntity entity) {
        GitProviderConfigDTO dto = new GitProviderConfigDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setProviderType(entity.getProviderType());
        dto.setBaseUrl(entity.getBaseUrl());
        dto.setUsername(entity.getUsername());
        dto.setIsActive(entity.getIsActive());
        dto.setHasToken(entity.getAccessToken() != null && !entity.getAccessToken().isEmpty());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
