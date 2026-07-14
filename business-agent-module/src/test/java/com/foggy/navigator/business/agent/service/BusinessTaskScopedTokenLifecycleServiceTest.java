package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessTaskScopedTokenLifecycleServiceTest {

    @Mock
    private BusinessTaskScopedTokenRepository tokenRepository;
    @Mock
    private BusinessTaskScopedTokenPolicyService tokenPolicyService;
    @Mock
    private BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void issueNewToken_initializesPersistsAndRegistersExactTaskAlias() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setTokenHash("stale_hash");
        when(tokenRepository.save(token)).thenReturn(token);

        BusinessTaskScopedTokenEntity result = service().issueNewToken(token, "btt_plain");

        assertEquals(token, result);
        assertEquals(SecretTokenSupport.sha256("btt_plain"), result.getTokenHash());
        verify(tokenPolicyService).initializeNewToken(token);
        verify(tokenRepository).save(token);
        verify(tokenRuntimeStore).registerToken(
                "tenant_01", "session_01", "bt_01", "btt_plain", token.getExpiresAt());
    }

    @Test
    void issueNewToken_withSynchronization_registersOnlyAfterCommit() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.save(token)).thenReturn(token);
        TransactionSynchronizationManager.initSynchronization();

        service().issueNewToken(token, "btt_plain");

        verifyNoInteractions(tokenRuntimeStore);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(tokenRuntimeStore).registerToken(
                "tenant_01", "session_01", "bt_01", "btt_plain", token.getExpiresAt());
    }

    @Test
    void issueNewToken_whenTransactionRollsBack_doesNotRegisterRuntimeToken() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.save(token)).thenReturn(token);
        TransactionSynchronizationManager.initSynchronization();

        service().issueNewToken(token, "btt_plain");
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindOpenApiToken_locksPersistsAndRegistersWorkerTaskForBothSessions() {
        String plainToken = "btt_plain";
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenHashForUpdate(SecretTokenSupport.sha256(plainToken)))
                .thenReturn(Optional.of(token));

        service().bindOpenApiTokenToWorkerTask(
                "tenant_01", plainToken, "worker_task_01", "worker_session_01");

        assertEquals("worker_task_01", token.getWorkerTaskId());
        assertEquals("worker_session_01", token.getWorkerSessionId());
        verify(tokenRepository).save(token);
        verify(tokenRuntimeStore).registerToken(
                "tenant_01", "session_01", "worker_task_01", plainToken, token.getExpiresAt());
        verify(tokenRuntimeStore).registerToken(
                "tenant_01", "worker_session_01", "worker_task_01", plainToken, token.getExpiresAt());
    }

    @Test
    void bindIssuedToken_recordsWorkerIdentityAndRejectsRebindToAnotherTask() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        BusinessTaskScopedTokenLifecycleService service = service();
        service.bindIssuedTokenToWorkerTask(
                "tenant_01", "tst_01", "btt_plain", "worker_task_01", null, "worker_01");

        assertEquals("worker_task_01", token.getWorkerTaskId());
        assertEquals("session_01", token.getWorkerSessionId());
        assertEquals("worker_01", token.getWorkerId());
        verify(tokenRuntimeStore).registerToken(
                "tenant_01", "session_01", "worker_task_01", "btt_plain", token.getExpiresAt());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service.bindIssuedTokenToWorkerTask(
                        "tenant_01", "tst_01", "btt_plain", "worker_task_02", null, "worker_02"));

        assertEquals("token already bound to another worker task", error.getMessage());
        verify(tokenRepository, times(1)).save(token);
    }

    @Test
    void bindIssuedToken_rejectsRebindToAnotherSessionForSameTask() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        token.setWorkerSessionId("worker_session_01");
        token.setWorkerId("worker_01");
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service().bindIssuedTokenToWorkerTask(
                        "tenant_01", "tst_01", "btt_plain",
                        "worker_task_01", "worker_session_02", "worker_01"));

        assertEquals("token already bound to another worker session", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindIssuedToken_rejectsRebindToAnotherWorkerForSameTaskAndSession() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        token.setWorkerSessionId("worker_session_01");
        token.setWorkerId("worker_01");
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service().bindIssuedTokenToWorkerTask(
                        "tenant_01", "tst_01", "btt_plain",
                        "worker_task_01", "worker_session_01", "worker_02"));

        assertEquals("token already bound to another worker", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindToken_rejectsTenantMismatchBeforePersistingOrRegistering() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenHashForUpdate(SecretTokenSupport.sha256("btt_plain")))
                .thenReturn(Optional.of(token));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().bindOpenApiTokenToWorkerTask(
                        "other_tenant", "btt_plain", "worker_task_01", null));

        assertEquals("token tenant mismatch", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindIssuedToken_rejectsPlainTokenHashMismatch() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().bindIssuedTokenToWorkerTask(
                        "tenant_01",
                        "tst_01",
                        "btt_different",
                        "worker_task_01",
                        "worker_session_01",
                        "worker_01"));

        assertEquals("task token secret mismatch", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindIssuedToken_rejectsExpiredToken() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                service().bindIssuedTokenToWorkerTask(
                        "tenant_01",
                        "tst_01",
                        "btt_plain",
                        "worker_task_01",
                        null,
                        "worker_01"));

        assertEquals("token is expired", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void revokeTaskScopedToken_marksMetadataRemovesAllAliasesAndIsIdempotent() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        token.setWorkerSessionId("worker_session_01");
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));

        BusinessTaskScopedTokenLifecycleService service = service();
        LocalDateTime before = LocalDateTime.now();
        service.revokeTaskScopedToken("tenant_01", "tst_01", " operator_01 ", " manual revoke ");

        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, token.getStatus());
        assertNotNull(token.getRevokedAt());
        assertFalse(token.getRevokedAt().isBefore(before));
        assertEquals("operator_01", token.getRevokedBy());
        assertEquals("manual revoke", token.getRevokeReason());
        verify(tokenRepository).save(token);
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "worker_task_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "worker_session_01", "worker_task_01", token.getTokenHash());

        LocalDateTime revokedAt = token.getRevokedAt();
        service.revokeTaskScopedToken("tenant_01", "tst_01", "operator_02", "other reason");

        assertEquals(revokedAt, token.getRevokedAt());
        assertEquals("operator_01", token.getRevokedBy());
        verify(tokenRepository, times(1)).save(token);
        verify(tokenRuntimeStore, times(3)).removeTokenIfMatches(
                org.mockito.ArgumentMatchers.eq("tenant_01"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(token.getTokenHash()));
    }

    @Test
    void revokeTaskScopedToken_withSynchronization_removesRuntimeAliasOnlyAfterCommit() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));
        TransactionSynchronizationManager.initSynchronization();

        service().revokeTaskScopedToken("tenant_01", "tst_01", "system", "task terminal");

        verifyNoInteractions(tokenRuntimeStore);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertEquals(1, synchronizations.size());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", token.getTokenHash());
    }

    @Test
    void revokeTaskScopedTokenByPlainToken_locksValidatesTenantAndRemovesAliases() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenHashForUpdate(SecretTokenSupport.sha256("btt_plain")))
                .thenReturn(Optional.of(token));

        service().revokeTaskScopedTokenByPlainToken(
                "tenant_01", "btt_plain", "system", "open api submit failed");

        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, token.getStatus());
        assertEquals("system", token.getRevokedBy());
        assertEquals("open api submit failed", token.getRevokeReason());
        verify(tokenRepository).save(token);
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", token.getTokenHash());
    }

    @Test
    void revokeTaskScopedTokenByPlainToken_rejectsTenantMismatch() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenHashForUpdate(SecretTokenSupport.sha256("btt_plain")))
                .thenReturn(Optional.of(token));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().revokeTaskScopedTokenByPlainToken(
                        "other_tenant", "btt_plain", "system", "open api submit failed"));

        assertEquals("token tenant mismatch", error.getMessage());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void revokeTaskScopedTokensForTask_revokesOnlyActiveTokensAsOneBatch() {
        BusinessTaskScopedTokenEntity active = activeToken();
        BusinessTaskScopedTokenEntity alreadyRevoked = activeToken();
        alreadyRevoked.setTokenId("tst_02");
        alreadyRevoked.setStatus(BusinessAgentTaskService.STATUS_REVOKED);
        alreadyRevoked.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByTaskIdAndTenantIdForUpdate("bt_01", "tenant_01"))
                .thenReturn(List.of(active, alreadyRevoked));

        int count = service().revokeTaskScopedTokensForTask(
                "tenant_01", "bt_01", "system", "task terminal");

        assertEquals(1, count);
        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, active.getStatus());
        assertEquals("task terminal", active.getRevokeReason());
        assertNotNull(active.getRevokedAt());
        verify(tokenRepository).saveAll(List.of(active));
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", active.getTokenHash());
    }

    private BusinessTaskScopedTokenLifecycleService service() {
        return new BusinessTaskScopedTokenLifecycleService(
                tokenRepository, tokenPolicyService, tokenRuntimeStore);
    }

    private BusinessTaskScopedTokenEntity activeToken() {
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_01");
        token.setTokenHash(SecretTokenSupport.sha256("btt_plain"));
        token.setTenantId("tenant_01");
        token.setClientAppId("app_01");
        token.setTaskId("bt_01");
        token.setSessionId("session_01");
        token.setNavigatorEffectiveUserId("actor_01");
        token.setWorkerPoolId("pool_01");
        token.setModelConfigId("model_01");
        token.setStatus(BusinessAgentTaskService.STATUS_ACTIVE);
        token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        return token;
    }
}
