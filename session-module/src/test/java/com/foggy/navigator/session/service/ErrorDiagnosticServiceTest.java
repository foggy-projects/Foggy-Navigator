package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.diagnostic.ErrorCategory;
import com.foggy.navigator.agent.framework.diagnostic.ErrorDiagnosticInput;
import com.foggy.navigator.agent.framework.diagnostic.ErrorEnvelope;
import com.foggy.navigator.agent.framework.diagnostic.ErrorRuntimePhase;
import com.foggy.navigator.common.entity.ErrorDiagnosticEntity;
import com.foggy.navigator.common.entity.ErrorDiagnosticShareEntity;
import com.foggy.navigator.session.config.ErrorDiagnosticProperties;
import com.foggy.navigator.session.dto.ErrorDiagnosticShareDTO;
import com.foggy.navigator.session.repository.ErrorDiagnosticRepository;
import com.foggy.navigator.session.repository.ErrorDiagnosticShareRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErrorDiagnosticServiceTest {

    @Mock ErrorDiagnosticRepository diagnosticRepository;
    @Mock ErrorDiagnosticShareRepository shareRepository;

    private ErrorDiagnosticProperties properties;
    private ErrorDiagnosticService service;

    @BeforeEach
    void setUp() {
        properties = new ErrorDiagnosticProperties();
        service = new ErrorDiagnosticService(diagnosticRepository, shareRepository, properties);
    }

    @Test
    void snapshotStoresOnlySanitizedAllowlistedFieldsAndNinetyDayExpiry() {
        ErrorEnvelope envelope = ErrorEnvelope.builder()
                .taskId("task-1")
                .errorCode("CODEX_WORKER_REMOTE_ERROR")
                .message("Failed in /home/sa/secret with Bearer sk-secret")
                .category(ErrorCategory.RUNTIME)
                .runtimePhase(ErrorRuntimePhase.TURN_EXECUTION)
                .recoverable(true)
                .providerType("codex")
                .occurredAt(Instant.now())
                .build();
        ErrorDiagnosticInput input = ErrorDiagnosticInput.builder()
                .diagnosticText("https://private.example/a user@example.com")
                .exceptionType("java.lang.IllegalStateException")
                .build();

        String ref = service.createSnapshot(envelope, input, "session-1", "user-1", "tenant-1");

        ArgumentCaptor<ErrorDiagnosticEntity> captor = ArgumentCaptor.forClass(ErrorDiagnosticEntity.class);
        verify(diagnosticRepository).save(captor.capture());
        ErrorDiagnosticEntity saved = captor.getValue();
        assertTrue(ref.startsWith("diagnostic://dg_"));
        assertFalse(saved.getSafeMessage().contains("/home/sa"));
        assertFalse(saved.getSafeMessage().contains("sk-secret"));
        assertFalse(saved.getDiagnosticText().contains("private.example"));
        assertFalse(saved.getDiagnosticText().contains("user@example.com"));
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusDays(89)));
        assertTrue(saved.getExpiresAt().isBefore(LocalDateTime.now().plusDays(91)));
    }

    @Test
    void shareReturnsTokenOnceButPersistsOnlyItsHashAndCapsTtl() {
        properties.setPublicSharingEnabled(true);
        ErrorDiagnosticEntity diagnostic = ownedDiagnostic();
        when(diagnosticRepository.findByDiagnosticIdAndOwnerUserIdAndTenantId(
                diagnostic.getDiagnosticId(), "user-1", "tenant-1"))
                .thenReturn(Optional.of(diagnostic));

        ErrorDiagnosticShareDTO result = service.createShare(
                diagnostic.getDiagnosticId(), "user-1", "tenant-1", 7);

        ArgumentCaptor<ErrorDiagnosticShareEntity> captor =
                ArgumentCaptor.forClass(ErrorDiagnosticShareEntity.class);
        verify(shareRepository).save(captor.capture());
        String token = result.getShareUrl().substring("/diagnostic-share/".length());
        ErrorDiagnosticShareEntity saved = captor.getValue();
        assertEquals(43, token.length());
        assertEquals(64, saved.getTokenHash().length());
        assertNotEquals(token, saved.getTokenHash());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        assertTrue(saved.getExpiresAt().isBefore(diagnostic.getExpiresAt()));
    }

    @Test
    void publicLookupFailsClosedWhenSharingIsDisabled() {
        properties.setPublicSharingEnabled(false);
        assertThrows(IllegalArgumentException.class,
                () -> service.getPublic("A".repeat(43)));
        org.mockito.Mockito.verifyNoInteractions(shareRepository);
    }

    @Test
    void ownershipMismatchUsesTheSameUnavailableFailureAsMissingResource() {
        String diagnosticId = "dg_" + "b".repeat(32);
        when(diagnosticRepository.findByDiagnosticIdAndOwnerUserIdAndTenantId(
                diagnosticId, "other-user", "tenant-1"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getOwned(diagnosticId, "other-user", "tenant-1"));

        assertEquals("Diagnostic not available", error.getMessage());
    }

    @Test
    void revokedShareFailsClosedWithoutReadingTheDiagnostic() {
        properties.setPublicSharingEnabled(true);
        ErrorDiagnosticShareEntity share = new ErrorDiagnosticShareEntity();
        share.setShareId("ds_1");
        share.setDiagnosticId("dg_" + "c".repeat(32));
        share.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        share.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(shareRepository.findByTokenHash(any())).thenReturn(Optional.of(share));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.getPublic("A".repeat(43)));

        assertEquals("Diagnostic not available", error.getMessage());
        verify(diagnosticRepository, never()).findById(any());
    }

    @Test
    void revokeMarksOnlyTheOwnedShareAndCleanupIsIdempotent() {
        ErrorDiagnosticEntity diagnostic = ownedDiagnostic();
        ErrorDiagnosticShareEntity share = new ErrorDiagnosticShareEntity();
        share.setShareId("ds_1");
        share.setDiagnosticId(diagnostic.getDiagnosticId());
        when(diagnosticRepository.findByDiagnosticIdAndOwnerUserIdAndTenantId(
                diagnostic.getDiagnosticId(), "user-1", "tenant-1"))
                .thenReturn(Optional.of(diagnostic));
        when(shareRepository.findById("ds_1")).thenReturn(Optional.of(share));
        when(shareRepository.deleteByExpiresAtBefore(any())).thenReturn(1L, 0L);
        when(diagnosticRepository.deleteByExpiresAtBefore(any())).thenReturn(1L, 0L);

        service.revokeShare(diagnostic.getDiagnosticId(), "ds_1", "user-1", "tenant-1");
        service.cleanupExpired();
        service.cleanupExpired();

        assertNotNull(share.getRevokedAt());
        verify(shareRepository).save(share);
        verify(shareRepository, org.mockito.Mockito.times(2)).deleteByExpiresAtBefore(any());
        verify(diagnosticRepository, org.mockito.Mockito.times(2)).deleteByExpiresAtBefore(any());
    }

    private ErrorDiagnosticEntity ownedDiagnostic() {
        ErrorDiagnosticEntity value = new ErrorDiagnosticEntity();
        value.setDiagnosticId("dg_" + "a".repeat(32));
        value.setOwnerUserId("user-1");
        value.setTenantId("tenant-1");
        value.setExpiresAt(LocalDateTime.now().plusDays(30));
        return value;
    }
}
