package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodexTaskRuntimeStateServiceTest {

    private CodexTaskRepository repository;
    private CredentialEncryptor encryptor;
    private CodexTaskEntity task;
    private CodexTaskRuntimeStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodexTaskRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        service = new CodexTaskRuntimeStateService(repository, encryptor, new ObjectMapper());
        task = new CodexTaskEntity();
        task.setTaskId("task-1");
        task.setRuntimeType("APP_SERVER");
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(1);
        when(repository.findByTaskIdForUpdate("task-1")).thenReturn(Optional.of(task));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(task));
        when(repository.findByTaskIdAndUserIdForUpdate("task-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(repository.saveAndFlush(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(encryptor.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(encryptor.decrypt(any())).thenAnswer(invocation -> {
            String value = invocation.getArgument(0);
            return value.startsWith("enc:") ? value.substring(4) : value;
        });
    }

    @Test
    void prepareAcceptancePersistsEncryptedExactEnvelopeAndHash() {
        service.prepareAcceptance("task-1", Map.of("prompt", "change file", "model", "codex-ultra"));

        assertEquals("ACCEPTING", task.getRuntimeAcceptanceState());
        assertNotNull(task.getRuntimeRequestHash());
        assertNotNull(task.getRuntimeRequestCiphertext());
        verify(repository).saveAndFlush(task);
    }

    @Test
    void preparedEnvelopeCanBeRecoveredAfterRestart() {
        Map<String, Object> request = Map.of("prompt", "change file", "model", "codex-ultra");
        service.prepareAcceptance("task-1", request);

        Map<String, Object> recovered = service.loadPreparedRequest("task-1");

        assertEquals(request, recovered);
    }

    @Test
    void envelopeLargerThanMediumTextBoundaryRoundTrips() {
        String payload = "x".repeat(17 * 1024 * 1024);
        service.prepareAcceptance("task-1", Map.of("prompt", "large attachment", "images", payload));

        Map<String, Object> recovered = service.loadPreparedRequest("task-1");

        assertEquals(payload.length(), ((String) recovered.get("images")).length());
    }

    @Test
    void changedPayloadForSameNavigatorTaskIsRejected() {
        service.prepareAcceptance("task-1", Map.of("prompt", "first"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.prepareAcceptance("task-1", Map.of("prompt", "changed")));

        assertEquals(true, error.getMessage().contains("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void workerTaskIdIsPersistedBeforeSubscriptionState() {
        service.recordAccepted("task-1", "task-1");

        assertEquals("task-1", task.getWorkerTaskId());
        assertEquals("ACCEPTED", task.getRuntimeAcceptanceState());
        verify(repository).saveAndFlush(task);

        service.markSubscribed("task-1");
        assertEquals("SUBSCRIBED", task.getRuntimeAcceptanceState());
    }

    @Test
    void preparedAbortIsAuthoritativeWithoutRemoteAcceptance() {
        task.setStatus("RUNNING");
        task.setRuntimeAcceptanceState("PREPARED");

        assertEquals(CodexTaskRuntimeStateService.AbortClaim.LOCAL_UNACCEPTED,
                service.claimAbort("task-1"));
        assertEquals("ABORTED_BEFORE_ACCEPT", task.getRuntimeAcceptanceState());
        assertThrows(CodexTaskRuntimeStateService.AcceptanceCancelledException.class,
                () -> service.prepareAcceptance("task-1", Map.of("prompt", "late")));
    }

    @Test
    void abortDuringAcceptancePreservesAbortClaimAfterAcceptedResponse() {
        task.setStatus("RUNNING");
        task.setRuntimeAcceptanceState("ACCEPTING");

        assertEquals(CodexTaskRuntimeStateService.AbortClaim.REMOTE_REQUIRED,
                service.claimAbort("task-1"));
        service.recordAccepted("task-1", "task-1");

        assertEquals("ABORT_REQUESTED", task.getRuntimeAcceptanceState());
        assertEquals(false, service.markSubscribed("task-1"));
    }

    @Test
    void terminalDeleteClaimIsPersistedBeforeRemoteCleanup() {
        task.setUserId("user-1");
        task.setStatus("COMPLETED");
        task.setRuntimeAcceptanceState("TERMINAL");

        CodexTaskEntity claimed = service.claimTerminalDeletion("task-1", "user-1");

        assertEquals(task, claimed);
        assertEquals("DELETE_REQUESTED", task.getRuntimeAcceptanceState());
        verify(repository).saveAndFlush(task);
    }

    @Test
    void runningTaskCannotBeClaimedForDeletion() {
        task.setUserId("user-1");
        task.setStatus("RUNNING");

        assertThrows(IllegalStateException.class,
                () -> service.claimTerminalDeletion("task-1", "user-1"));
    }
}
