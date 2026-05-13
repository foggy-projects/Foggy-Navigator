package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeAccessTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeAccessTokenRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientAppRuntimeCredentialResolver {

    private static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_ACCESS_TOKEN_TTL = Duration.ofMinutes(30);

    private final ClientAppRuntimeCredentialRepository runtimeCredentialRepository;
    private final ClientAppRuntimeAccessTokenRepository accessTokenRepository;
    private final ClientAppService clientAppService;
    private final SkillRegistryService skillRegistryService;

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolve(String tenantId, String appKey, String appSecret) {
        if (!StringUtils.hasText(appKey) && !StringUtils.hasText(appSecret)) {
            return Optional.empty();
        }
        requireText(tenantId, "tenantId is required");
        requireText(appKey, "client app key is required");
        requireText(appSecret, "client app secret is required");

        ClientAppRuntimeCredentialEntity credential = runtimeCredentialRepository.findByAppKey(appKey)
                .orElseThrow(() -> new IllegalArgumentException("invalid client app credential"));
        validateCredential(tenantId, credential, appSecret);
        clientAppService.requireActiveClientApp(tenantId, credential.getClientAppId());

        return Optional.of(ResolvedClientAppCredentialDTO.builder()
                .credentialId(credential.getCredentialId())
                .tenantId(credential.getTenantId())
                .clientAppId(credential.getClientAppId())
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolveForSkill(
            String tenantId,
            String appKey,
            String appSecret,
            String skillId) {
        Optional<ResolvedClientAppCredentialDTO> resolved = resolve(tenantId, appKey, appSecret);
        if (resolved.isPresent() && StringUtils.hasText(skillId)) {
            skillRegistryService.checkClientAppSkillAccess(
                    tenantId,
                    resolved.get().getClientAppId(),
                    skillId);
        }
        return resolved;
    }

    @Transactional
    public ClientAppRuntimeAccessTokenDTO issueAccessToken(String tenantId, String appKey, String appSecret) {
        return issueAccessToken(tenantId, appKey, appSecret, DEFAULT_ACCESS_TOKEN_TTL);
    }

    @Transactional
    public ClientAppRuntimeAccessTokenDTO issueAccessToken(String appKey, String appSecret) {
        return issueAccessToken(appKey, appSecret, DEFAULT_ACCESS_TOKEN_TTL);
    }

    @Transactional
    public ClientAppRuntimeAccessTokenDTO issueAccessToken(
            String appKey,
            String appSecret,
            Duration requestedTtl) {
        requireText(appKey, "client app key is required");
        requireText(appSecret, "client app secret is required");

        ClientAppRuntimeCredentialEntity credential = runtimeCredentialRepository.findByAppKey(appKey)
                .orElseThrow(() -> new IllegalArgumentException("invalid client app credential"));
        return issueAccessToken(credential.getTenantId(), appKey, appSecret, requestedTtl);
    }

    @Transactional
    public ClientAppRuntimeAccessTokenDTO issueAccessToken(
            String tenantId,
            String appKey,
            String appSecret,
            Duration requestedTtl) {
        requireText(tenantId, "tenantId is required");
        requireText(appKey, "client app key is required");
        requireText(appSecret, "client app secret is required");

        ClientAppRuntimeCredentialEntity credential = runtimeCredentialRepository.findByAppKey(appKey)
                .orElseThrow(() -> new IllegalArgumentException("invalid client app credential"));
        validateCredential(tenantId, credential, appSecret);
        clientAppService.requireActiveClientApp(tenantId, credential.getClientAppId());

        Duration ttl = normalizeAccessTokenTtl(requestedTtl);
        String plainToken = SecretTokenSupport.randomToken("cat_");
        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(ttl.toSeconds());

        ClientAppRuntimeAccessTokenEntity entity = new ClientAppRuntimeAccessTokenEntity();
        entity.setTokenId("carat_" + UUID.randomUUID());
        entity.setTokenHash(SecretTokenSupport.sha256(plainToken));
        entity.setTenantId(credential.getTenantId());
        entity.setClientAppId(credential.getClientAppId());
        entity.setCredentialId(credential.getCredentialId());
        entity.setAppKey(credential.getAppKey());
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setExpiresAt(expiresAt);
        ClientAppRuntimeAccessTokenEntity saved = accessTokenRepository.save(entity);

        ClientAppRuntimeAccessTokenDTO dto = new ClientAppRuntimeAccessTokenDTO();
        dto.setTokenId(saved.getTokenId());
        dto.setTenantId(saved.getTenantId());
        dto.setClientAppId(saved.getClientAppId());
        dto.setCredentialId(saved.getCredentialId());
        dto.setAppKey(saved.getAppKey());
        dto.setAccessToken(plainToken);
        dto.setTokenType("Bearer");
        dto.setExpiresInSeconds(ttl.toSeconds());
        dto.setExpiresAt(saved.getExpiresAt());
        return dto;
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolveAccessToken(
            String tenantId,
            String appKey,
            String accessToken) {
        if (!StringUtils.hasText(appKey) && !StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }
        requireText(tenantId, "tenantId is required");
        requireText(appKey, "client app key is required");
        requireText(accessToken, "client app access token is required");

        ClientAppRuntimeAccessTokenEntity token = accessTokenRepository
                .findByTokenHash(SecretTokenSupport.sha256(accessToken))
                .orElseThrow(() -> new IllegalArgumentException("invalid client app access token"));
        validateAccessToken(tenantId, appKey, token);
        clientAppService.requireActiveClientApp(tenantId, token.getClientAppId());

        return Optional.of(ResolvedClientAppCredentialDTO.builder()
                .credentialId(token.getCredentialId())
                .tenantId(token.getTenantId())
                .clientAppId(token.getClientAppId())
                .build());
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolveAccessTokenForSkill(
            String tenantId,
            String appKey,
            String accessToken,
            String skillId) {
        Optional<ResolvedClientAppCredentialDTO> resolved = resolveAccessToken(tenantId, appKey, accessToken);
        if (resolved.isPresent() && StringUtils.hasText(skillId)) {
            skillRegistryService.checkClientAppSkillAccess(
                    tenantId,
                    resolved.get().getClientAppId(),
                    skillId);
        }
        return resolved;
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolveAccessTokenForSkill(
            String appKey,
            String accessToken,
            String skillId) {
        Optional<ResolvedClientAppCredentialDTO> resolved = resolveAccessToken(appKey, accessToken);
        if (resolved.isPresent() && StringUtils.hasText(skillId)) {
            skillRegistryService.checkClientAppSkillAccess(
                    resolved.get().getTenantId(),
                    resolved.get().getClientAppId(),
                    skillId);
        }
        return resolved;
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedClientAppCredentialDTO> resolveAccessToken(String appKey, String accessToken) {
        if (!StringUtils.hasText(appKey) && !StringUtils.hasText(accessToken)) {
            return Optional.empty();
        }
        requireText(appKey, "client app key is required");
        requireText(accessToken, "client app access token is required");

        ClientAppRuntimeAccessTokenEntity token = accessTokenRepository
                .findByTokenHash(SecretTokenSupport.sha256(accessToken))
                .orElseThrow(() -> new IllegalArgumentException("invalid client app access token"));
        return resolveAccessToken(token.getTenantId(), appKey, accessToken);
    }

    private void validateCredential(String tenantId, ClientAppRuntimeCredentialEntity credential, String appSecret) {
        if (!tenantId.equals(credential.getTenantId())) {
            throw new IllegalArgumentException("invalid client app credential");
        }
        if (!ClientAppService.STATUS_ACTIVE.equals(credential.getStatus())) {
            throw new IllegalArgumentException("client app credential is not active");
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("client app credential expired");
        }
        String expectedHash = credential.getSecretHash();
        String actualHash = SecretTokenSupport.sha256(appSecret);
        if (!expectedHash.equals(actualHash)) {
            throw new IllegalArgumentException("invalid client app credential");
        }
    }

    private void validateAccessToken(String tenantId, String appKey, ClientAppRuntimeAccessTokenEntity token) {
        if (!tenantId.equals(token.getTenantId()) || !appKey.equals(token.getAppKey())) {
            throw new IllegalArgumentException("invalid client app access token");
        }
        if (!ClientAppService.STATUS_ACTIVE.equals(token.getStatus()) || token.getRevokedAt() != null) {
            throw new IllegalArgumentException("client app access token is not active");
        }
        if (token.getExpiresAt() == null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("client app access token expired");
        }
    }

    private Duration normalizeAccessTokenTtl(Duration requestedTtl) {
        Duration ttl = requestedTtl == null || requestedTtl.isNegative() || requestedTtl.isZero()
                ? DEFAULT_ACCESS_TOKEN_TTL
                : requestedTtl;
        return ttl.compareTo(MAX_ACCESS_TOKEN_TTL) > 0 ? MAX_ACCESS_TOKEN_TTL : ttl;
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
