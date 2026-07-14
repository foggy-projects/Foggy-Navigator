package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskTerminalStateEntity;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskTerminalStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private BusinessTaskTerminalStateRepository terminalStateRepository;
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

    @Test
    void revokeTaskScopedTokensForWorkerTask_locksAndRevokesOnlyActiveTokens() {
        BusinessTaskScopedTokenEntity active = activeToken();
        active.setWorkerTaskId("worker_task_01");
        active.setWorkerSessionId("worker_session_01");
        BusinessTaskScopedTokenEntity alreadyRevoked = activeToken();
        alreadyRevoked.setTokenId("tst_02");
        alreadyRevoked.setWorkerTaskId("worker_task_01");
        alreadyRevoked.setStatus(BusinessAgentTaskService.STATUS_REVOKED);
        alreadyRevoked.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01"))
                .thenReturn(List.of(active, alreadyRevoked));

        int count = service().revokeTaskScopedTokensForWorkerTask(
                "tenant_01", "worker_task_01",
                "system:task-lifecycle", "worker task terminal");

        assertEquals(1, count);
        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, active.getStatus());
        assertEquals("system:task-lifecycle", active.getRevokedBy());
        assertEquals("worker task terminal", active.getRevokeReason());
        verify(tokenRepository).saveAll(List.of(active));
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", active.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "worker_task_01", active.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "worker_session_01", "worker_task_01", active.getTokenHash());
    }

    @Test
    void recordTerminalStateDerivesTaskAndActorFromExactBoundCapability() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        token.setNavigatorEffectiveUserId("client_app_actor");
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(List.of(token));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.empty());
        when(tokenPolicyService.maximumCapabilityLifetime()).thenReturn(Duration.ofHours(1));

        boolean recorded = service().recordTerminalState(
                "tenant_01", "worker_task_01", "agent_owner", "langgraph-biz", "failed");

        assertTrue(recorded);
        ArgumentCaptor<BusinessTaskTerminalStateEntity> captor =
                ArgumentCaptor.forClass(BusinessTaskTerminalStateEntity.class);
        verify(terminalStateRepository).saveAndFlush(captor.capture());
        BusinessTaskTerminalStateEntity terminal = captor.getValue();
        assertEquals("tenant_01", terminal.getTenantId());
        assertEquals("worker_task_01", terminal.getWorkerTaskId());
        assertEquals("bt_01", terminal.getBusinessTaskId());
        assertEquals("client_app_actor", terminal.getNavigatorEffectiveUserId());
        assertEquals("agent_owner", terminal.getProviderTaskUserId());
        assertEquals("langgraph-biz", terminal.getSourceAgentId());
        assertEquals("FAILED", terminal.getTerminalStatus());
        assertNotNull(terminal.getTerminalAt());
        assertNotNull(terminal.getExpiresAt());
    }

    @Test
    void recordTerminalStateEventBeforeBindingCreatesUnboundProviderMarker() {
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_unbound")).thenReturn(List.of());
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_unbound")).thenReturn(Optional.empty());
        when(tokenPolicyService.maximumCapabilityLifetime()).thenReturn(Duration.ofHours(1));

        boolean recorded = service().recordTerminalState(
                "tenant_01", "worker_task_unbound", "actor_01", "langgraph-biz", "COMPLETED");

        assertTrue(recorded);
        ArgumentCaptor<BusinessTaskTerminalStateEntity> captor =
                ArgumentCaptor.forClass(BusinessTaskTerminalStateEntity.class);
        verify(terminalStateRepository).saveAndFlush(captor.capture());
        assertNull(captor.getValue().getBusinessTaskId());
        assertNull(captor.getValue().getNavigatorEffectiveUserId());
        assertEquals("actor_01", captor.getValue().getProviderTaskUserId());
    }

    @Test
    void recordTerminalStateRejectsCrossTaskWorkerCorrelation() {
        BusinessTaskScopedTokenEntity first = activeToken();
        first.setWorkerTaskId("worker_task_shared");
        BusinessTaskScopedTokenEntity second = activeToken();
        second.setTokenId("tst_02");
        second.setTaskId("bt_other");
        second.setWorkerTaskId("worker_task_shared");
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_shared")).thenReturn(List.of(first, second));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().recordTerminalState(
                        "tenant_01", "worker_task_shared", "actor_01", "codex-biz", "COMPLETED"));

        assertEquals("worker task is bound to inconsistent task capabilities", error.getMessage());
        verifyNoInteractions(terminalStateRepository);
    }

    @Test
    void recordTerminalStateRejectsCrossUserWorkerCorrelation() {
        BusinessTaskScopedTokenEntity first = activeToken();
        first.setWorkerTaskId("worker_task_shared");
        BusinessTaskScopedTokenEntity second = activeToken();
        second.setTokenId("tst_02");
        second.setNavigatorEffectiveUserId("actor_other");
        second.setWorkerTaskId("worker_task_shared");
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_shared")).thenReturn(List.of(first, second));

        assertThrows(SecurityException.class, () -> service().recordTerminalState(
                "tenant_01", "worker_task_shared", "actor_01", "claude-worker", "COMPLETED"));

        verifyNoInteractions(terminalStateRepository);
    }

    @Test
    void recordTerminalStateRejectsRepositoryResultFromAnotherTenant() {
        BusinessTaskScopedTokenEntity crossTenant = activeToken();
        crossTenant.setTenantId("tenant_other");
        crossTenant.setWorkerTaskId("worker_task_01");
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(List.of(crossTenant));

        assertThrows(SecurityException.class, () -> service().recordTerminalState(
                "tenant_01", "worker_task_01", "actor_01", "langgraph-biz", "COMPLETED"));

        verifyNoInteractions(terminalStateRepository);
    }

    @Test
    void recordTerminalStateRejectsExistingTombstoneActorMismatch() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(List.of(token));
        BusinessTaskTerminalStateEntity existing = new BusinessTaskTerminalStateEntity();
        existing.setId(1L);
        existing.setTenantId("tenant_01");
        existing.setWorkerTaskId("worker_task_01");
        existing.setBusinessTaskId("bt_01");
        existing.setNavigatorEffectiveUserId("actor_other");
        existing.setProviderTaskUserId("provider_owner");
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.of(existing));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().recordTerminalState(
                        "tenant_01", "worker_task_01", "provider_owner", "langgraph-biz", "COMPLETED"));

        assertEquals("worker task terminal capability actor mismatch", error.getMessage());
        verify(terminalStateRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordTerminalStateRejectsProviderOwnerChangeWithoutTreatingOwnerAsCapabilityActor() {
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(List.of());
        BusinessTaskTerminalStateEntity existing = new BusinessTaskTerminalStateEntity();
        existing.setId(1L);
        existing.setTenantId("tenant_01");
        existing.setWorkerTaskId("worker_task_01");
        existing.setProviderTaskUserId("provider_owner");
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.of(existing));

        SecurityException error = assertThrows(SecurityException.class, () ->
                service().recordTerminalState(
                        "tenant_01", "worker_task_01", "other_provider_owner",
                        "langgraph-biz", "COMPLETED"));

        assertEquals("worker task terminal provider owner mismatch", error.getMessage());
        verify(terminalStateRepository, never()).saveAndFlush(any());
    }

    @Test
    void materializeTerminalRevocationEnrichesUnboundMarkerFromExactTokenOnly() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        token.setNavigatorEffectiveUserId("client_app_actor");
        BusinessTaskTerminalStateEntity terminal = new BusinessTaskTerminalStateEntity();
        terminal.setId(1L);
        terminal.setTenantId("tenant_01");
        terminal.setWorkerTaskId("worker_task_01");
        terminal.setProviderTaskUserId("agent_owner");
        terminal.setTerminalStatus("COMPLETED");
        terminal.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(List.of(token));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.of(terminal));

        int revoked = service().materializeTerminalRevocation(
                "tenant_01", "worker_task_01", "system:task-lifecycle");

        assertEquals(1, revoked);
        assertEquals("bt_01", terminal.getBusinessTaskId());
        assertEquals("client_app_actor", terminal.getNavigatorEffectiveUserId());
        assertEquals("agent_owner", terminal.getProviderTaskUserId());
        assertNotNull(terminal.getRevocationCompletedAt());
        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, token.getStatus());
        org.mockito.InOrder locks = org.mockito.Mockito.inOrder(
                tokenRepository, terminalStateRepository);
        locks.verify(tokenRepository).findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01");
        locks.verify(terminalStateRepository).findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01");
        verify(terminalStateRepository).save(terminal);
    }

    @Test
    void materializeTerminalRevocationWithoutBoundTokenDoesNotMarkCompleted() {
        BusinessTaskTerminalStateEntity terminal = new BusinessTaskTerminalStateEntity();
        terminal.setTenantId("tenant_01");
        terminal.setWorkerTaskId("worker_task_unbound");
        terminal.setProviderTaskUserId("agent_owner");
        terminal.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(tokenRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_unbound")).thenReturn(List.of());

        int revoked = service().materializeTerminalRevocation(
                "tenant_01", "worker_task_unbound", "system:task-lifecycle");

        assertEquals(0, revoked);
        assertNull(terminal.getRevocationCompletedAt());
        verifyNoInteractions(terminalStateRepository);
    }

    @Test
    void bindIssuedToken_terminalBusinessTaskFailsClosedBeforeWorkerAliasRegistration() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));
        when(terminalStateRepository.existsByTenantIdAndBusinessTaskIdAndExpiresAtAfter(
                eq("tenant_01"), eq("bt_01"), any(LocalDateTime.class))).thenReturn(true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () ->
                service().bindIssuedTokenToWorkerTask(
                        "tenant_01", "tst_01", "btt_plain",
                        "worker_task_01", "worker_session_01", "worker_01"));

        assertEquals("cannot bind task token to a terminal worker task", error.getMessage());
        assertNull(token.getWorkerTaskId());
        verify(tokenRepository, never()).save(any());
        verifyNoInteractions(tokenRuntimeStore);
    }

    @Test
    void bindIssuedToken_terminalWorkerMarkerPersistsRevokedBindingAndEnrichesMarker() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));
        BusinessTaskTerminalStateEntity marker = new BusinessTaskTerminalStateEntity();
        marker.setId(1L);
        marker.setTenantId("tenant_01");
        marker.setWorkerTaskId("worker_task_01");
        marker.setProviderTaskUserId("provider_owner");
        marker.setTerminalStatus("COMPLETED");
        marker.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.of(marker));

        TerminalTaskBindingException error = assertThrows(
                TerminalTaskBindingException.class, () ->
                        service().bindIssuedTokenToWorkerTask(
                                "tenant_01", "tst_01", "btt_plain",
                                "worker_task_01", "worker_session_01", "worker_01"));

        assertEquals("cannot bind task token to a terminal worker task", error.getMessage());
        assertEquals("worker_task_01", token.getWorkerTaskId());
        assertEquals("worker_session_01", token.getWorkerSessionId());
        assertEquals("worker_01", token.getWorkerId());
        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, token.getStatus());
        assertNotNull(token.getRevokedAt());
        assertEquals("bt_01", marker.getBusinessTaskId());
        assertEquals("actor_01", marker.getNavigatorEffectiveUserId());
        assertNotNull(marker.getRevocationCompletedAt());
        verify(tokenRepository).save(token);
        verify(terminalStateRepository).saveAndFlush(marker);
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "worker_task_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "worker_session_01", "worker_task_01", token.getTokenHash());
    }

    @Test
    void bindIssuedToken_terminalMarkerCorrelationMismatchStillCommitsTokenSafetyState() {
        BusinessTaskScopedTokenEntity token = activeToken();
        when(tokenRepository.findByTokenIdAndTenantIdForUpdate("tst_01", "tenant_01"))
                .thenReturn(Optional.of(token));
        BusinessTaskTerminalStateEntity marker = new BusinessTaskTerminalStateEntity();
        marker.setId(1L);
        marker.setTenantId("tenant_01");
        marker.setWorkerTaskId("worker_task_01");
        marker.setBusinessTaskId("foreign_business_task");
        marker.setNavigatorEffectiveUserId("foreign_actor");
        marker.setProviderTaskUserId("provider_owner");
        marker.setTerminalStatus("COMPLETED");
        marker.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        when(terminalStateRepository.findByTenantIdAndWorkerTaskIdForUpdate(
                "tenant_01", "worker_task_01")).thenReturn(Optional.of(marker));

        TerminalTaskBindingException error = assertThrows(
                TerminalTaskBindingException.class, () ->
                        service().bindIssuedTokenToWorkerTask(
                                "tenant_01", "tst_01", "btt_plain",
                                "worker_task_01", "worker_session_01", "worker_01"));

        assertEquals("terminal tombstone capability correlation mismatch", error.getMessage());
        assertEquals("worker_task_01", token.getWorkerTaskId());
        assertEquals("worker_session_01", token.getWorkerSessionId());
        assertEquals("worker_01", token.getWorkerId());
        assertEquals(BusinessAgentTaskService.STATUS_REVOKED, token.getStatus());
        assertNotNull(token.getRevokedAt());
        assertEquals("foreign_business_task", marker.getBusinessTaskId());
        assertEquals("foreign_actor", marker.getNavigatorEffectiveUserId());
        assertNull(marker.getRevocationCompletedAt());
        verify(tokenRepository).save(token);
        verify(terminalStateRepository, never()).saveAndFlush(any());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "bt_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "session_01", "worker_task_01", token.getTokenHash());
        verify(tokenRuntimeStore).removeTokenIfMatches(
                "tenant_01", "worker_session_01", "worker_task_01", token.getTokenHash());
    }

    @Test
    void requireNotTerminal_checksTenantScopedBusinessAndWorkerTombstones() {
        BusinessTaskScopedTokenEntity token = activeToken();
        token.setWorkerTaskId("worker_task_01");
        when(terminalStateRepository.existsByTenantIdAndBusinessTaskIdAndExpiresAtAfter(
                eq("tenant_01"), eq("bt_01"), any(LocalDateTime.class))).thenReturn(false);
        when(terminalStateRepository.existsByTenantIdAndWorkerTaskIdAndExpiresAtAfter(
                eq("tenant_01"), eq("worker_task_01"), any(LocalDateTime.class))).thenReturn(true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class, () -> service().requireNotTerminal(token));

        assertEquals("task token belongs to a terminal task", error.getMessage());
    }

    private BusinessTaskScopedTokenLifecycleService service() {
        return new BusinessTaskScopedTokenLifecycleService(
                tokenRepository, terminalStateRepository, tokenPolicyService, tokenRuntimeStore);
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
