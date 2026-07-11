package com.foggy.navigator.codex.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.util.ProviderStateCodec;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.test.util.ReflectionTestUtils;

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
    private SessionTaskRepository sessionTaskRepository;
    private SessionTaskEntity sessionTask;
    private CodexTaskEntity task;
    private CodexTaskRuntimeStateService service;

    @BeforeEach
    void setUp() {
        repository = mock(CodexTaskRepository.class);
        encryptor = mock(CredentialEncryptor.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);
        service = new CodexTaskRuntimeStateService(repository, encryptor, new ObjectMapper());
        ReflectionTestUtils.setField(service, "sessionTaskRepository", sessionTaskRepository);
        task = new CodexTaskEntity();
        task.setTaskId("task-1");
        task.setRuntimeType("APP_SERVER");
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(1);
        task.setProviderType("codex-worker");
        sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-1");
        when(repository.findByTaskIdForUpdate("task-1")).thenReturn(Optional.of(task));
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(task));
        when(repository.findByTaskIdAndUserIdForUpdate("task-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(repository.saveAndFlush(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskIdForUpdate("task-1")).thenReturn(Optional.of(sessionTask));
        when(sessionTaskRepository.save(any(SessionTaskEntity.class)))
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
        assertEquals("ACCEPTING", projectedAcceptanceState());
        assertNotNull(task.getRuntimeRequestHash());
        assertNotNull(task.getRuntimeRequestCiphertext());
        verify(repository).saveAndFlush(task);
        verify(sessionTaskRepository).findByTaskIdForUpdate("task-1");
    }

    @Test
    void unifiedTaskStateWriterUsesPessimisticWriteLock() throws NoSuchMethodException {
        Lock lock = SessionTaskRepository.class
                .getMethod("findByTaskIdForUpdate", String.class)
                .getAnnotation(Lock.class);

        assertNotNull(lock);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, lock.value());
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
        assertEquals("ACCEPTED", projectedAcceptanceState());
        verify(repository).saveAndFlush(task);

        service.markSubscribed("task-1");
        assertEquals("SUBSCRIBED", task.getRuntimeAcceptanceState());
        assertEquals("SUBSCRIBED", projectedAcceptanceState());
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
        assertEquals("DELETE_REQUESTED", projectedAcceptanceState());
        verify(repository).saveAndFlush(task);
    }

    @Test
    void runningTaskCannotBeClaimedForDeletion() {
        task.setUserId("user-1");
        task.setStatus("RUNNING");

        assertThrows(IllegalStateException.class,
                () -> service.claimTerminalDeletion("task-1", "user-1"));
    }

    private Object projectedAcceptanceState() {
        return ProviderStateCodec.parseObject(sessionTask.getTaskStateJson())
                .get(ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE);
    }
}
