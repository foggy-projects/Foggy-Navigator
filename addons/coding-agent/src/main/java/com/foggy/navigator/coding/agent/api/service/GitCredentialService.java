package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.CreateGitCredentialRequest;
import com.foggy.navigator.coding.agent.api.model.GitCredentialResponse;
import com.foggy.navigator.coding.agent.api.model.UpdateGitCredentialRequest;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import com.foggy.navigator.coding.agent.api.repository.GitCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GitCredentialService {

    @Autowired
    private GitCredentialRepository gitCredentialRepository;

    @Transactional
    public GitCredentialResponse createCredential(String userId, CreateGitCredentialRequest request) {
        log.info("创建 Git 凭证: userId={}, provider={}, serverUrl={}",
                userId, request.getProvider(), request.getServerUrl());

        // 规范化 serverUrl（移除尾部斜杠）
        String normalizedUrl = normalizeServerUrl(request.getServerUrl());

        // 检查是否已存在
        if (gitCredentialRepository.existsByUserIdAndProviderAndServerUrl(
                userId, request.getProvider(), normalizedUrl)) {
            throw new IllegalArgumentException("该 Git 服务凭证已存在");
        }

        GitCredentialEntity entity = GitCredentialEntity.builder()
                .credentialId(UUID.randomUUID().toString())
                .userId(userId)
                .provider(request.getProvider())
                .serverUrl(normalizedUrl)
                .displayName(request.getDisplayName())
                .accessToken(request.getAccessToken())
                .refreshToken(request.getRefreshToken())
                .build();

        entity = gitCredentialRepository.save(entity);
        log.info("Git 凭证创建成功: credentialId={}", entity.getCredentialId());

        return GitCredentialResponse.from(entity);
    }

    public List<GitCredentialResponse> listCredentials(String userId) {
        return gitCredentialRepository.findByUserId(userId).stream()
                .map(GitCredentialResponse::from)
                .collect(Collectors.toList());
    }

    public List<GitCredentialResponse> listCredentialsByProvider(String userId, GitProvider provider) {
        return gitCredentialRepository.findByUserIdAndProvider(userId, provider).stream()
                .map(GitCredentialResponse::from)
                .collect(Collectors.toList());
    }

    public GitCredentialResponse getCredential(String userId, String credentialId) {
        GitCredentialEntity entity = getCredentialEntity(userId, credentialId);
        return GitCredentialResponse.from(entity);
    }

    public GitCredentialEntity getCredentialEntity(String userId, String credentialId) {
        GitCredentialEntity entity = gitCredentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new IllegalArgumentException("Git 凭证不存在: " + credentialId));

        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该凭证");
        }

        return entity;
    }

    @Transactional
    public GitCredentialResponse updateCredential(String userId, String credentialId, UpdateGitCredentialRequest request) {
        log.info("更新 Git 凭证: userId={}, credentialId={}", userId, credentialId);

        GitCredentialEntity entity = getCredentialEntity(userId, credentialId);

        if (request.getDisplayName() != null) {
            entity.setDisplayName(request.getDisplayName());
        }
        if (request.getAccessToken() != null && !request.getAccessToken().isEmpty()) {
            entity.setAccessToken(request.getAccessToken());
        }
        if (request.getRefreshToken() != null) {
            entity.setRefreshToken(request.getRefreshToken());
        }

        entity = gitCredentialRepository.save(entity);
        log.info("Git 凭证更新成功: credentialId={}", credentialId);

        return GitCredentialResponse.from(entity);
    }

    @Transactional
    public void deleteCredential(String userId, String credentialId) {
        log.info("删除 Git 凭证: userId={}, credentialId={}", userId, credentialId);

        GitCredentialEntity entity = getCredentialEntity(userId, credentialId);
        gitCredentialRepository.delete(entity);

        log.info("Git 凭证删除成功: credentialId={}", credentialId);
    }

    private String normalizeServerUrl(String serverUrl) {
        if (serverUrl == null) {
            return null;
        }
        // 移除尾部斜杠
        String normalized = serverUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
