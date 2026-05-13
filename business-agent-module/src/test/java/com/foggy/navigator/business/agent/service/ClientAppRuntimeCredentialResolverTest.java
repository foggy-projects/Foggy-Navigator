package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppRuntimeAccessTokenDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeAccessTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeAccessTokenRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientAppRuntimeCredentialResolverTest {

    private ClientAppRuntimeCredentialRepository credentialRepository;
    private ClientAppRuntimeAccessTokenRepository accessTokenRepository;
    private ClientAppService clientAppService;
    private SkillRegistryService skillRegistryService;
    private ClientAppRuntimeCredentialResolver resolver;

    @BeforeEach
    void setUp() {
        credentialRepository = mock(ClientAppRuntimeCredentialRepository.class);
        accessTokenRepository = mock(ClientAppRuntimeAccessTokenRepository.class);
        clientAppService = mock(ClientAppService.class);
        skillRegistryService = mock(SkillRegistryService.class);
        resolver = new ClientAppRuntimeCredentialResolver(
                credentialRepository,
                accessTokenRepository,
                clientAppService,
                skillRegistryService);
    }

    @Test
    void resolve_returnsEmptyWhenNoRuntimeCredentialHeaders() {
        Optional<ResolvedClientAppCredentialDTO> resolved = resolver.resolve("tenant_1", null, null);

        assertTrue(resolved.isEmpty());
        verifyNoInteractions(credentialRepository, clientAppService, skillRegistryService);
    }

    @Test
    void resolveForSkill_validatesSecretAppAndSkillGrant() {
        ClientAppRuntimeCredentialEntity credential = credential("tenant_1", "tms_app", "app_secret");
        when(credentialRepository.findByAppKey("app_key")).thenReturn(Optional.of(credential));
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());

        ResolvedClientAppCredentialDTO resolved = resolver
                .resolveForSkill("tenant_1", "app_key", "app_secret", "tms-agent-v305")
                .orElseThrow();

        assertEquals("tms_app", resolved.getClientAppId());
        assertEquals("tenant_1", resolved.getTenantId());
        assertEquals("cred_1", resolved.getCredentialId());
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "tms_app", "tms-agent-v305");
    }

    @Test
    void resolve_rejectsWrongSecret() {
        ClientAppRuntimeCredentialEntity credential = credential("tenant_1", "tms_app", "app_secret");
        when(credentialRepository.findByAppKey("app_key")).thenReturn(Optional.of(credential));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("tenant_1", "app_key", "wrong_secret"));

        assertEquals("invalid client app credential", error.getMessage());
        verifyNoInteractions(clientAppService, skillRegistryService);
    }

    @Test
    void resolve_rejectsExpiredCredential() {
        ClientAppRuntimeCredentialEntity credential = credential("tenant_1", "tms_app", "app_secret");
        credential.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(credentialRepository.findByAppKey("app_key")).thenReturn(Optional.of(credential));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("tenant_1", "app_key", "app_secret"));

        assertEquals("client app credential expired", error.getMessage());
        verifyNoInteractions(clientAppService, skillRegistryService);
    }

    @Test
    void issueAccessToken_validatesSecretAndStoresOnlyTokenHash() {
        ClientAppRuntimeCredentialEntity credential = credential("tenant_1", "tms_app", "app_secret");
        when(credentialRepository.findByAppKey("app_key")).thenReturn(Optional.of(credential));
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());
        when(accessTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ClientAppRuntimeAccessTokenDTO dto = resolver.issueAccessToken(
                "tenant_1", "app_key", "app_secret");

        assertEquals("tms_app", dto.getClientAppId());
        assertEquals("cred_1", dto.getCredentialId());
        assertEquals("app_key", dto.getAppKey());
        assertEquals("Bearer", dto.getTokenType());
        assertEquals(30 * 60, dto.getExpiresInSeconds());
        assertNotNull(dto.getAccessToken());

        ArgumentCaptor<ClientAppRuntimeAccessTokenEntity> captor =
                ArgumentCaptor.forClass(ClientAppRuntimeAccessTokenEntity.class);
        verify(accessTokenRepository).save(captor.capture());
        ClientAppRuntimeAccessTokenEntity saved = captor.getValue();
        assertEquals("tms_app", saved.getClientAppId());
        assertEquals("cred_1", saved.getCredentialId());
        assertEquals(SecretTokenSupport.sha256(dto.getAccessToken()), saved.getTokenHash());
        assertNotEquals(dto.getAccessToken(), saved.getTokenHash());
    }

    @Test
    void issueAccessTokenWithoutTenantDerivesTenantFromCredential() {
        ClientAppRuntimeCredentialEntity credential = credential("tenant_1", "tms_app", "app_secret");
        when(credentialRepository.findByAppKey("app_key")).thenReturn(Optional.of(credential));
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());
        when(accessTokenRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ClientAppRuntimeAccessTokenDTO dto = resolver.issueAccessToken("app_key", "app_secret");

        assertEquals("tenant_1", dto.getTenantId());
        assertEquals("tms_app", dto.getClientAppId());
        verify(clientAppService).requireActiveClientApp("tenant_1", "tms_app");
    }

    @Test
    void resolveAccessTokenForSkill_validatesTokenAppAndSkillGrant() {
        ClientAppRuntimeAccessTokenEntity token = accessToken(
                "tenant_1", "tms_app", "cred_1", "app_key", "access_token");
        when(accessTokenRepository.findByTokenHash(SecretTokenSupport.sha256("access_token")))
                .thenReturn(Optional.of(token));
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());

        ResolvedClientAppCredentialDTO resolved = resolver
                .resolveAccessTokenForSkill("tenant_1", "app_key", "access_token", "tms-agent-v305")
                .orElseThrow();

        assertEquals("tms_app", resolved.getClientAppId());
        assertEquals("tenant_1", resolved.getTenantId());
        assertEquals("cred_1", resolved.getCredentialId());
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "tms_app", "tms-agent-v305");
    }

    @Test
    void resolveAccessTokenForSkillWithoutTenantDerivesTenantFromToken() {
        ClientAppRuntimeAccessTokenEntity token = accessToken(
                "tenant_1", "tms_app", "cred_1", "app_key", "access_token");
        when(accessTokenRepository.findByTokenHash(SecretTokenSupport.sha256("access_token")))
                .thenReturn(Optional.of(token));
        when(clientAppService.requireActiveClientApp("tenant_1", "tms_app")).thenReturn(new ClientAppEntity());

        ResolvedClientAppCredentialDTO resolved = resolver
                .resolveAccessTokenForSkill("app_key", "access_token", "tms-agent-v305")
                .orElseThrow();

        assertEquals("tenant_1", resolved.getTenantId());
        assertEquals("tms_app", resolved.getClientAppId());
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "tms_app", "tms-agent-v305");
    }

    @Test
    void resolveAccessToken_rejectsWrongAppKey() {
        ClientAppRuntimeAccessTokenEntity token = accessToken(
                "tenant_1", "tms_app", "cred_1", "app_key", "access_token");
        when(accessTokenRepository.findByTokenHash(SecretTokenSupport.sha256("access_token")))
                .thenReturn(Optional.of(token));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveAccessToken("tenant_1", "other_key", "access_token"));

        assertEquals("invalid client app access token", error.getMessage());
        verifyNoInteractions(clientAppService, skillRegistryService);
    }

    @Test
    void resolveAccessToken_rejectsExpiredToken() {
        ClientAppRuntimeAccessTokenEntity token = accessToken(
                "tenant_1", "tms_app", "cred_1", "app_key", "access_token");
        token.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(accessTokenRepository.findByTokenHash(SecretTokenSupport.sha256("access_token")))
                .thenReturn(Optional.of(token));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveAccessToken("tenant_1", "app_key", "access_token"));

        assertEquals("client app access token expired", error.getMessage());
        verifyNoInteractions(clientAppService, skillRegistryService);
    }

    private ClientAppRuntimeCredentialEntity credential(String tenantId, String clientAppId, String secret) {
        ClientAppRuntimeCredentialEntity entity = new ClientAppRuntimeCredentialEntity();
        entity.setCredentialId("cred_1");
        entity.setTenantId(tenantId);
        entity.setClientAppId(clientAppId);
        entity.setAppKey("app_key");
        entity.setSecretHash(SecretTokenSupport.sha256(secret));
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }

    private ClientAppRuntimeAccessTokenEntity accessToken(
            String tenantId,
            String clientAppId,
            String credentialId,
            String appKey,
            String plainToken) {
        ClientAppRuntimeAccessTokenEntity entity = new ClientAppRuntimeAccessTokenEntity();
        entity.setTokenId("token_1");
        entity.setTenantId(tenantId);
        entity.setClientAppId(clientAppId);
        entity.setCredentialId(credentialId);
        entity.setAppKey(appKey);
        entity.setTokenHash(SecretTokenSupport.sha256(plainToken));
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
