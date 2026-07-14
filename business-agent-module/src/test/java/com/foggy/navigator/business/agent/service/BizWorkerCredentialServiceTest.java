package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.BizWorkerPrincipal;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BizWorkerCredentialServiceTest {

    private BizWorkerIdentityRepository repository;
    private BizWorkerCredentialService service;

    @BeforeEach
    void setUp() {
        repository = mock(BizWorkerIdentityRepository.class);
        service = new BizWorkerCredentialService(repository);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void rotatePlatformCredentialIssuesOneTimeModernSecretAndPersistsOnlyHash() {
        BizWorkerIdentityEntity identity = identity(ResourceOwnerType.PLATFORM, "platform");
        identity.setCredentialVersion(0);
        identity.setTokenHash(SecretTokenSupport.sha256("legacy-token"));
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.PLATFORM, "platform"))
                .thenReturn(Optional.of(identity));
        BizWorkerCredentialDTO result = service.rotatePlatformCredential("worker-1", null);

        assertTrue(result.getSecret().startsWith("bwc_"));
        assertEquals(1, result.getCredentialVersion());
        assertEquals(SecretTokenSupport.sha256(result.getSecret()), identity.getTokenHash());
        assertNotEquals(result.getSecret(), identity.getTokenHash());
        assertEquals(BizWorkerCredentialService.DEFAULT_TTL_SECONDS,
                ChronoUnit.SECONDS.between(result.getIssuedAt(), result.getExpiresAt()));
        assertNotNull(result.getIssuedAt());
        assertNotNull(result.getRotatedAt());
        assertNull(result.getRevokedAt());
        assertFalse(result.toString().contains(result.getSecret()));
        verify(repository).findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.PLATFORM, "platform");
        verify(repository).saveAndFlush(identity);
    }

    @Test
    void rotateCredentialIncrementsVersionAndClearsRevocationForSameOwner() {
        BizWorkerIdentityEntity identity = identity(ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1");
        identity.setCredentialVersion(7);
        identity.setCredentialRevokedAt(LocalDateTime.now().minusDays(1));
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1"))
                .thenReturn(Optional.of(identity));
        LocalDateTime before = LocalDateTime.now();

        BizWorkerCredentialDTO result = service.rotateCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-1", "worker-1", 120L);

        assertEquals(8, result.getCredentialVersion());
        assertNull(identity.getCredentialRevokedAt());
        assertFalse(result.getExpiresAt().isBefore(before.plusSeconds(120)));
        assertFalse(result.getExpiresAt().isAfter(LocalDateTime.now().plusSeconds(120)));
    }

    @Test
    void rotateCredentialUsesOwnerScopedLookupAndHidesForeignIdentity() {
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2"))
                .thenReturn(Optional.empty());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> service.rotateCredential(
                ResourceOwnerType.UPSTREAM_SYSTEM, "ups-2", "worker-1", 120L));

        assertEquals("worker identity not found", failure.getMessage());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void rotateCredentialRejectsUnboundedLifetime() {
        BizWorkerIdentityEntity identity = identity(ResourceOwnerType.PLATFORM, "platform");
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.PLATFORM, "platform"))
                .thenReturn(Optional.of(identity));

        assertThrows(IllegalArgumentException.class,
                () -> service.rotatePlatformCredential("worker-1", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> service.rotatePlatformCredential(
                        "worker-1", BizWorkerCredentialService.MAX_TTL_SECONDS + 1));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void revokeCredentialMarksModernCredentialAndNeverReturnsSecret() {
        BizWorkerIdentityEntity identity = modernIdentity("worker-secret");
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.PLATFORM, "platform"))
                .thenReturn(Optional.of(identity));

        BizWorkerCredentialDTO result = service.revokePlatformCredential("worker-1");

        assertNotNull(identity.getCredentialRevokedAt());
        assertNull(result.getSecret());
        assertEquals(1, result.getCredentialVersion());
        verify(repository).saveAndFlush(identity);
    }

    @Test
    void revokeCredentialRejectsLegacyVersionZero() {
        BizWorkerIdentityEntity identity = identity(ResourceOwnerType.PLATFORM, "platform");
        identity.setCredentialVersion(0);
        when(repository.findByWorkerIdAndOwnerTypeAndOwnerIdForUpdate(
                "worker-1", ResourceOwnerType.PLATFORM, "platform"))
                .thenReturn(Optional.of(identity));

        assertThrows(IllegalStateException.class,
                () -> service.revokePlatformCredential("worker-1"));

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void requireStrictCredentialReturnsBoundPrincipalForValidModernSecret() {
        BizWorkerIdentityEntity identity = modernIdentity("worker-secret");
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(identity));

        BizWorkerPrincipal result = service.requireStrictCredential("worker-1", "worker-secret");

        assertEquals("worker-1", result.getWorkerId());
        assertEquals(ResourceOwnerType.PLATFORM, result.getOwnerType());
        assertEquals("platform", result.getOwnerId());
        assertEquals("LANGGRAPH_BIZ", result.getWorkerBackend());
        assertEquals(1, result.getCredentialVersion());
    }

    @Test
    void requireStrictCredentialRejectsWrongSecret() {
        when(repository.findByWorkerId("worker-1"))
                .thenReturn(Optional.of(modernIdentity("worker-secret")));

        SecurityException failure = assertThrows(SecurityException.class,
                () -> service.requireStrictCredential("worker-1", "wrong-secret"));

        assertEquals("invalid worker credential", failure.getMessage());
    }

    @Test
    void requireStrictCredentialRejectsDisabledExpiredRevokedAndLegacyStates() {
        BizWorkerIdentityEntity disabled = modernIdentity("worker-secret");
        disabled.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        assertStrictFailure(disabled);

        BizWorkerIdentityEntity expired = modernIdentity("worker-secret");
        expired.setCredentialExpiresAt(LocalDateTime.now().minusNanos(1));
        assertStrictFailure(expired);

        BizWorkerIdentityEntity revoked = modernIdentity("worker-secret");
        revoked.setCredentialRevokedAt(LocalDateTime.now());
        assertStrictFailure(revoked);

        BizWorkerIdentityEntity legacy = modernIdentity("worker-secret");
        legacy.setCredentialVersion(0);
        legacy.setCredentialIssuedAt(null);
        legacy.setCredentialExpiresAt(null);
        assertStrictFailure(legacy);
    }

    @Test
    void requireStrictCredentialRejectsModernRecordWithoutBoundLifetime() {
        BizWorkerIdentityEntity identity = modernIdentity("worker-secret");
        identity.setCredentialExpiresAt(null);

        assertStrictFailure(identity);

        identity = modernIdentity("worker-secret");
        identity.setCredentialIssuedAt(null);

        assertStrictFailure(identity);
    }

    @Test
    void requireStrictCredentialUsesSameExternalErrorForUnknownAndInvalidStates() {
        when(repository.findByWorkerId("unknown-worker")).thenReturn(Optional.empty());
        SecurityException unknown = assertThrows(SecurityException.class,
                () -> service.requireStrictCredential("unknown-worker", "worker-secret"));
        assertEquals("invalid worker credential", unknown.getMessage());

        BizWorkerIdentityEntity revoked = modernIdentity("worker-secret");
        revoked.setCredentialRevokedAt(LocalDateTime.now());
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(revoked));
        SecurityException invalidState = assertThrows(SecurityException.class,
                () -> service.requireStrictCredential("worker-1", "worker-secret"));
        assertEquals(unknown.getMessage(), invalidState.getMessage());
    }

    private void assertStrictFailure(BizWorkerIdentityEntity identity) {
        when(repository.findByWorkerId("worker-1")).thenReturn(Optional.of(identity));
        SecurityException failure = assertThrows(SecurityException.class,
                () -> service.requireStrictCredential("worker-1", "worker-secret"));
        assertEquals("invalid worker credential", failure.getMessage());
    }

    private BizWorkerIdentityEntity modernIdentity(String secret) {
        BizWorkerIdentityEntity identity = identity(ResourceOwnerType.PLATFORM, "platform");
        identity.setCredentialVersion(1);
        identity.setTokenHash(SecretTokenSupport.sha256(secret));
        identity.setCredentialIssuedAt(LocalDateTime.now().minusMinutes(1));
        identity.setCredentialExpiresAt(LocalDateTime.now().plusMinutes(5));
        identity.setCredentialRotatedAt(LocalDateTime.now().minusMinutes(1));
        return identity;
    }

    private BizWorkerIdentityEntity identity(ResourceOwnerType ownerType, String ownerId) {
        BizWorkerIdentityEntity identity = new BizWorkerIdentityEntity();
        identity.setWorkerId("worker-1");
        identity.setOwnerType(ownerType);
        identity.setOwnerId(ownerId);
        identity.setWorkerBackend("LANGGRAPH_BIZ");
        identity.setBaseUrl("http://127.0.0.1:3061");
        identity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        identity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return identity;
    }
}
