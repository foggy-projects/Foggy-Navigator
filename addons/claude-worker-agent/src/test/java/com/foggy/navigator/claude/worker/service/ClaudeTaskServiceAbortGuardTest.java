package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.service.TerminationOperationService;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Tests for abort/cancel guard logic in ClaudeTaskService:
 * <ul>
 *   <li>failTask() treats a verified Worker failure as terminal even with a legacy abort marker</li>
 *   <li>failTask() skips when task already in terminal state</li>
 *   <li>doAbortWorkerTask() signs and records a remote-cancel request without fabricating a terminal state</li>
 *   <li>doAbortWorkerTask() retains cancellation for later reconciliation when dispatch is unconfirmed</li>
 * </ul>
 */
class ClaudeTaskServiceAbortGuardTest {

    private ClaudeTaskService service;
    private ClaudeTaskRepository taskRepository;
    private WorkerStreamRelay streamRelay;
    private ClaudeWorkerService workerService;
    private TransactionTemplate txTemplate;
    private ApplicationEventPublisher publisher;
    private SessionEntityRepository sessionEntityRepository;
    private SessionTaskRepository sessionTaskRepository;

    private static final String TASK_ID = "task-abort-001";
    private static final String SESSION_ID = "session-abort-001";
    private static final String WORKER_ID = "worker-abort-001";
    private static final String USER_ID = "user-abort-001";
    private static final String TENANT_ID = "tenant-abort-001";

    @BeforeEach
    void setUp() {
        taskRepository = mock(ClaudeTaskRepository.class);
        streamRelay = mock(WorkerStreamRelay.class);
        workerService = mock(ClaudeWorkerService.class);
        txTemplate = mock(TransactionTemplate.class);
        publisher = mock(ApplicationEventPublisher.class);
        sessionEntityRepository = mock(SessionEntityRepository.class);
        sessionTaskRepository = mock(SessionTaskRepository.class);

        var sessionManager = mock(SessionManager.class);
        var agentTeamsConfigService = mock(AgentTeamsConfigService.class);
        var directoryService = mock(WorkingDirectoryService.class);
        var workingDirectoryRepository = mock(WorkingDirectoryRepository.class);
        var llmModelManager = mock(LlmModelManager.class);
        var userAuthService = mock(UserAuthService.class);
        var credentialEncryptor = mock(com.foggy.navigator.common.security.CredentialEncryptor.class);
        var codingAgentRepository = mock(com.foggy.navigator.claude.worker.repository.CodingAgentRepository.class);

        service = new ClaudeTaskService(
                taskRepository,
                workerService,
                agentTeamsConfigService,
                codingAgentRepository,
                directoryService,
                workingDirectoryRepository,
                sessionManager,
                publisher,
                llmModelManager,
                userAuthService,
                credentialEncryptor,
                txTemplate
        );

        // Inject the lazy streamRelay via reflection
        try {
            var field = ClaudeTaskService.class.getDeclaredField("streamRelay");
            field.setAccessible(true);
            field.set(service, streamRelay);
            var sessionRepositoryField = ClaudeTaskService.class.getDeclaredField("sessionEntityRepository");
            sessionRepositoryField.setAccessible(true);
            sessionRepositoryField.set(service, sessionEntityRepository);
            var sessionTaskRepositoryField = ClaudeTaskService.class.getDeclaredField("sessionTaskRepository");
            sessionTaskRepositoryField.setAccessible(true);
            sessionTaskRepositoryField.set(service, sessionTaskRepository);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject streamRelay", e);
        }
    }

    @Test
    void capabilitiesAdvertiseNormalAndOwnerForceCancellation() {
        assertTrue(service.supports(TaskQueryCapability.CANCEL_TASK));
        assertTrue(service.supports(TaskQueryCapability.FORCE_CANCEL_TASK));
    }

    // -----------------------------------------------------------------------
    // failTask — abortRequested guard
    // -----------------------------------------------------------------------

    @Nested
    class FailTaskGuardTests {

        @Test
        void failTask_recordsVerifiedFailureWhenLegacyAbortRequested() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(true);
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "Task was cancelled");

            assertEquals("FAILED", entity.getStatus());
            assertEquals(false, entity.getAbortRequested());
            verify(taskRepository).save(entity);
        }

        @Test
        void failTask_skipsWhenAlreadyAborted() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("ABORTED");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "Task was cancelled");

            verify(taskRepository, never()).save(any());
            org.junit.jupiter.api.Assertions.assertEquals("ABORTED", entity.getStatus());
        }

        @Test
        void failTask_skipsWhenAlreadyCompleted() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("COMPLETED");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "some error");

            verify(taskRepository, never()).save(any());
            org.junit.jupiter.api.Assertions.assertEquals("COMPLETED", entity.getStatus());
        }

        @Test
        void failTask_proceedsNormally_whenNoGuardTriggered() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(false);
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "real error");

            org.junit.jupiter.api.Assertions.assertEquals("FAILED", entity.getStatus());
            org.junit.jupiter.api.Assertions.assertEquals("CLAUDE_RUNTIME_REMOTE_ERROR", entity.getErrorMessage());
            verify(taskRepository).save(entity);
            verify(publisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                    TASK_ID.equals(event.getTaskId())
                            && "RUNNING".equals(event.getPreviousStatus())
                            && "FAILED".equals(event.getStatus())
                            && TENANT_ID.equals(event.getTenantId())
                            && Boolean.TRUE.equals(event.getRecoverable())));
        }

        @Test
        void failTask_proceedsNormally_whenAbortRequestedIsNull() {
            // null = field not set (e.g., legacy task) → treat as false
            ClaudeTaskEntity entity = createRunningTask();
            entity.setAbortRequested(null);
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.failTask(TASK_ID, "worker-task-1", "claude-session-1", "real error");

            org.junit.jupiter.api.Assertions.assertEquals("FAILED", entity.getStatus());
            verify(taskRepository).save(entity);
        }
    }

    @Test
    void resetToRunningPublishesNonTerminalRecoveryTransition() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setStatus("FAILED");
        entity.setErrorMessage("temporary stream failure");
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetToRunning(TASK_ID);

        org.junit.jupiter.api.Assertions.assertEquals("RUNNING", entity.getStatus());
        org.junit.jupiter.api.Assertions.assertNull(entity.getErrorMessage());
        verify(publisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                TASK_ID.equals(event.getTaskId())
                        && "FAILED".equals(event.getPreviousStatus())
                        && "RUNNING".equals(event.getStatus())
                        && event.getRecoverable() == null));
    }

    @Test
    void absentUntrackedCancellationConvergesOnlyExplicitCancellationWithoutProviderProgress() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setStatus("CANCEL_REQUESTED");
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setInteractionState("PROCESSING");
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        TerminationOperationService terminationOperationService = installTerminationOperationService();

        boolean converged = service.reconcileAbsentUntrackedCancellation(TASK_ID);

        assertTrue(converged);
        assertEquals("ABORTED", entity.getStatus());
        assertEquals(false, entity.getAbortRequested());
        assertEquals("AWAITING_REPLY", session.getInteractionState());
        verify(terminationOperationService).markTargetAbsentForTask(TASK_ID);
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity sessionTask) ->
                TASK_ID.equals(sessionTask.getTaskId())
                        && "ABORTED".equals(sessionTask.getStatus())));
        verify(publisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                TASK_ID.equals(event.getTaskId())
                        && "CANCEL_REQUESTED".equals(event.getPreviousStatus())
                        && "ABORTED".equals(event.getStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())));
    }

    @Test
    void absentUntrackedCancellationRetainsTaskWhenProviderProgressExists() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setStatus("CANCEL_REQUESTED");
        entity.setLastAckedSeq(1);
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        TerminationOperationService terminationOperationService = installTerminationOperationService();

        boolean converged = service.reconcileAbsentUntrackedCancellation(TASK_ID);

        org.junit.jupiter.api.Assertions.assertFalse(converged);
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(taskRepository, never()).save(any());
        verify(terminationOperationService, never()).markTargetAbsentForTask(anyString());
        verify(publisher, never()).publishEvent(any(TaskStatusChangeEvent.class));
    }

    @Test
    void definitiveTerminalEventFallsBackToSessionTenantForLegacyTask() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setTenantId(null);
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setTenantId("tenant-from-session");
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeTask(TASK_ID, "worker-task-1", "claude-session-1",
                "done", null, null, null, null, null, null);

        assertEquals("tenant-from-session", entity.getTenantId());
        verify(publisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                TASK_ID.equals(event.getTaskId())
                        && "tenant-from-session".equals(event.getTenantId())
                        && Boolean.FALSE.equals(event.getRecoverable())));
    }

    @Test
    void tenantlessPlatformTerminalCommitsWithoutClaimingTenantAuthority() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setTenantId(null);
        SessionEntity session = new SessionEntity();
        session.setId(SESSION_ID);
        session.setInteractionState("PROCESSING");
        when(sessionEntityRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(null);

        service.completeTask(TASK_ID, "worker-task-1", "claude-session-1",
                "done", null, null, null, null, null, null);

        assertEquals("COMPLETED", entity.getStatus());
        assertEquals("AWAITING_REPLY", session.getInteractionState());
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity sessionTask) ->
                TASK_ID.equals(sessionTask.getTaskId())
                        && "COMPLETED".equals(sessionTask.getStatus())));
        verify(publisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                TASK_ID.equals(event.getTaskId())
                        && event.getTenantId() == null
                        && event.getRecoverable() == null));
    }

    @Test
    void manualPidKillPersistsAndSignsFreshProcessIdentity() throws Exception {
        ClaudeTaskEntity entity = createRunningTask();
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(ClaudeTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TerminationOperationService terminationOperationService = mock(TerminationOperationService.class);
        when(terminationOperationService.hasActiveOperationForTask(TASK_ID)).thenReturn(false);
        TerminationOperationService.CreateCommand[] accepted = new TerminationOperationService.CreateCommand[1];
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            accepted[0] = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to-manual-pid");
            operation.setSchemaVersion(1);
            operation.setProviderTaskId(accepted[0].providerTaskId());
            operation.setWorkerId(accepted[0].workerId());
            operation.setKind(accepted[0].kind());
            operation.setOrigin(accepted[0].origin());
            operation.setActorId(accepted[0].actorId());
            operation.setActorType(accepted[0].actorType());
            operation.setAuthorizationDecisionId(accepted[0].authorizationDecisionId());
            operation.setReasonCode(accepted[0].reasonCode());
            operation.setCorrelationId(accepted[0].correlationId());
            operation.setExpectedPid(accepted[0].expectedPid());
            operation.setExpectedProcessIdentity(accepted[0].expectedProcessIdentity());
            return operation;
        });
        var field = ClaudeTaskService.class.getDeclaredField("terminationOperationService");
        field.setAccessible(true);
        field.set(service, terminationOperationService);

        String processIdentity = "claude-cli:321:2026-07-16T03:40:13.655Z";
        ClaudeTaskService.ManualPidKillRequest request = service.prepareManualPidKill(
                TASK_ID, WORKER_ID, USER_ID, "TENANT_ADMIN_MANUAL", TENANT_ID, true,
                321, processIdentity, "worker-token");

        assertEquals(processIdentity, accepted[0].expectedProcessIdentity());
        assertTrue(accepted[0].authorizationDecisionId().startsWith("authz-v1:tenant_admin_manual:"));
        assertNotEquals(accepted[0].authorizationDecisionId(), accepted[0].correlationId());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        String payload = new String(Base64.getUrlDecoder().decode(request.capability().encodedOperation()),
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"expected_process_identity\":\"" + processIdentity + "\""));
        verify(terminationOperationService).markDispatchStarted("to-manual-pid");
    }

    @Test
    void manualPidKillRejectsOrdinaryTaskOwnerDespiteFreshProcessBinding() throws Exception {
        ClaudeTaskEntity entity = createRunningTask();
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        TerminationOperationService terminationOperationService = mock(TerminationOperationService.class);
        var field = ClaudeTaskService.class.getDeclaredField("terminationOperationService");
        field.setAccessible(true);
        field.set(service, terminationOperationService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill(TASK_ID, WORKER_ID, USER_ID, "USER_MANUAL", TENANT_ID, false,
                        321, "claude-cli:321:2026-07-16T03:40:13.655Z", "worker-token"));

        assertEquals("TERMINATION_TASK_ACCESS_DENIED", error.getMessage());
        verify(terminationOperationService, never()).accept(any());
    }

    @Test
    void manualPidKillRejectsMissingOrWrongPidProcessIdentityBeforeDispatch() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill(TASK_ID, WORKER_ID, USER_ID, "TENANT_ADMIN_MANUAL",
                        TENANT_ID, true, 321, "", "worker-token"));
        assertEquals("TERMINATION_PROCESS_IDENTITY_REQUIRED", missing.getMessage());

        ClaudeTaskEntity entity = createRunningTask();
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        TerminationOperationService terminationOperationService = mock(TerminationOperationService.class);
        when(terminationOperationService.hasActiveOperationForTask(TASK_ID)).thenReturn(false);
        try {
            var field = ClaudeTaskService.class.getDeclaredField("terminationOperationService");
            field.setAccessible(true);
            field.set(service, terminationOperationService);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill(TASK_ID, WORKER_ID, USER_ID, "TENANT_ADMIN_MANUAL",
                        TENANT_ID, true, 321, "claude-cli:322:2026-07-16T03:40:13.655Z", "worker-token"));
        assertEquals("TERMINATION_PROCESS_IDENTITY_MISMATCH", mismatch.getMessage());
        verify(terminationOperationService, never()).accept(any());
    }

    @Test
    void manualPidKillResultRequiresObservedExitCorrelatedToOperationTaskAndWorker() {
        ClaudeTaskEntity entity = createRunningTask();
        entity.setStatus("CANCEL_REQUESTED");
        when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(ClaudeTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TerminationOperationService terminationOperationService = installTerminationOperationService();
        ClaudeTaskService.ManualPidKillRequest request = new ClaudeTaskService.ManualPidKillRequest(
                "to-manual-result", TASK_ID, "RUNNING", false, null);

        // A bare true flag is not a correlated Worker observation.
        service.recordManualPidKillResult(request, Map.of("observed_exit", true));

        // The task, operation, and Worker bindings must all match this request.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "other-task",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", "other-task", WORKER_ID)));
        service.recordManualPidKillResult(request, Map.of(
                "task_id", TASK_ID,
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("other-operation", TASK_ID, WORKER_ID)));
        service.recordManualPidKillResult(request, Map.of(
                "task_id", TASK_ID,
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", TASK_ID, "other-worker")));
        service.recordManualPidKillResult(request, Map.of(
                "task_id", TASK_ID,
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("TO-MANUAL-RESULT", TASK_ID, WORKER_ID)));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService, times(5)).markAwaitingObservation(
                "to-manual-result", "TERMINATION_UNCONFIRMED");
        verify(terminationOperationService, never()).markObservedTerminal(anyString(), anyString());

        service.recordManualPidKillResult(request, Map.of(
                "task_id", TASK_ID,
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", TASK_ID, WORKER_ID)));

        assertEquals("ABORTED", entity.getStatus());
        verify(terminationOperationService).markObservedTerminal("to-manual-result", "ABORTED");
    }

    // -----------------------------------------------------------------------
    // doAbortWorkerTask — explicit remote termination lifecycle
    // -----------------------------------------------------------------------

    @Nested
    class DoAbortWorkerTaskTests {

        @Test
        void doAbortWorkerTaskSignedAcknowledgementRemainsCancelRequested() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setWorkerTaskId("remote-task-1");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

            TerminationOperationService terminationOperationService = installTerminationOperationService();
            when(terminationOperationService.hasActiveOperationForTask(TASK_ID)).thenReturn(false);
            TerminationOperationService.CreateCommand[] accepted = new TerminationOperationService.CreateCommand[1];
            when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
                accepted[0] = invocation.getArgument(0);
                return operationFor(accepted[0], "to-remote-cancel");
            });

            var mockClient = mock(com.foggy.navigator.claude.worker.client.ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(mockClient);
            when(mockClient.terminationSigningSecret()).thenReturn("worker-token");
            com.foggy.navigator.common.termination.TerminationOperationCapability[] dispatched =
                    new com.foggy.navigator.common.termination.TerminationOperationCapability[1];
            when(mockClient.abortTask(eq("remote-task-1"),
                    any(com.foggy.navigator.common.termination.TerminationOperationCapability.class)))
                    .thenAnswer(invocation -> {
                        dispatched[0] = invocation.getArgument(1);
                        return reactor.core.publisher.Mono.just(java.util.Map.of("status", "CANCEL_REQUESTED"));
                    });

            service.doAbortWorkerTask(TASK_ID, "remote-task-1");

            verify(mockClient).abortTask(eq("remote-task-1"),
                    any(com.foggy.navigator.common.termination.TerminationOperationCapability.class));
            verify(terminationOperationService).markDispatchStarted("to-remote-cancel");
            verify(terminationOperationService).markCancelRequested("to-remote-cancel");
            verify(streamRelay, never()).abortStream(TASK_ID);
            verify(taskRepository, never()).updateAbortRequestedByTaskId(any(), anyBoolean());
            assertEquals("CANCEL_REQUESTED", entity.getStatus());
            assertEquals(false, entity.getAbortRequested());
            assertTrue(accepted[0].correlationId().startsWith("remote-cancel:"));
            String payload = new String(Base64.getUrlDecoder().decode(dispatched[0].encodedOperation()),
                    StandardCharsets.UTF_8);
            assertTrue(payload.contains("\"correlation_id\":\"remote-cancel:"));
        }

        @Test
        void doAbortWorkerTaskRejectsMismatchedRemoteTaskBeforeOperationDispatch() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setWorkerTaskId("remote-task-1");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.doAbortWorkerTask(TASK_ID, "different-remote-task"));

            assertEquals("TERMINATION_REMOTE_TASK_MISMATCH", error.getMessage());
            assertEquals("RUNNING", entity.getStatus());
            verify(taskRepository, never()).save(any());
        }

        @Test
        void doAbortWorkerTaskKeepsTaskPendingWhenWorkerDispatchIsUnconfirmed() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setWorkerTaskId("remote-task-1");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID))
                    .thenThrow(new IllegalStateException("raw remote diagnostic must not change lifecycle"));

            TerminationOperationService terminationOperationService = installTerminationOperationService();
            when(terminationOperationService.hasActiveOperationForTask(TASK_ID)).thenReturn(false);
            when(terminationOperationService.accept(any())).thenAnswer(invocation ->
                    operationFor(invocation.getArgument(0), "to-remote-unconfirmed"));

            service.doAbortWorkerTask(TASK_ID, "remote-task-1");

            verify(terminationOperationService).markDispatchStarted("to-remote-unconfirmed");
            verify(terminationOperationService).markUnconfirmed(eq("to-remote-unconfirmed"), any());
            verify(streamRelay, never()).abortStream(TASK_ID);
            assertEquals("CANCEL_REQUESTED", entity.getStatus());
            assertEquals("TERMINATION_UNCONFIRMED", entity.getErrorMessage());
        }
    }

    @Nested
    class OwnerForceCancelTests {

        @Test
        void ownerForceCancelRequiresOwnershipBeforeCreatingOperation() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("CANCEL_REQUESTED");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            TerminationOperationService terminationOperationService = installTerminationOperationService();

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> service.cancelTaskDirect(TASK_ID, "different-user", true));

            assertEquals("Task not found: " + TASK_ID, error.getMessage());
            verifyNoInteractions(terminationOperationService);
            verifyNoInteractions(workerService);
        }

        @Test
        void ownerForceCancelRecordsOnlyCorrelatedWorkerTerminalReceipt() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("CANCEL_REQUESTED");
            entity.setErrorMessage("TERMINATION_OPERATION_PENDING");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

            TerminationOperationService terminationOperationService = installTerminationOperationService();
            TerminationOperationService.CreateCommand[] accepted =
                    new TerminationOperationService.CreateCommand[1];
            when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
                accepted[0] = invocation.getArgument(0);
                return operationFor(accepted[0], "to-owner-force");
            });
            var client = mock(com.foggy.navigator.claude.worker.client.ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(client);
            when(client.terminationSigningSecret()).thenReturn("worker-token");
            when(client.forceAbortTask(eq(TASK_ID), any())).thenReturn(reactor.core.publisher.Mono.just(
                    ownerForceReceipt("to-owner-force", TASK_ID, WORKER_ID, USER_ID)));

            service.cancelTaskDirect(TASK_ID, USER_ID, true);

            assertEquals("ABORTED", entity.getStatus());
            assertEquals(null, entity.getErrorMessage());
            assertEquals("OWNER_FORCE_CANCEL", accepted[0].kind());
            assertEquals(TASK_ID, accepted[0].providerTaskId());
            assertEquals("TASK_OWNER_FORCE_CANCEL", accepted[0].actorType());
            assertTrue(accepted[0].authorizationDecisionId()
                    .startsWith("authz-v1:task_owner_force_cancel:"));
            verify(terminationOperationService).supersedeActiveOperationsForTask(
                    TASK_ID, "TERMINATION_OPERATION_SUPERSEDED_BY_OWNER_FORCE");
            verify(terminationOperationService).markDispatchStarted("to-owner-force");
            verify(terminationOperationService).markObservedTerminal("to-owner-force", "ABORTED");
        }

        @Test
        void ownerForceCancelKeepsPendingWhenWorkerReceiptIsUnavailable() {
            ClaudeTaskEntity entity = createRunningTask();
            entity.setStatus("CANCEL_REQUESTED");
            when(taskRepository.findByTaskIdForUpdate(TASK_ID)).thenReturn(Optional.of(entity));
            when(taskRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(workerService.getWorkerEntity(WORKER_ID)).thenReturn(createWorkerEntity());

            TerminationOperationService terminationOperationService = installTerminationOperationService();
            when(terminationOperationService.accept(any())).thenAnswer(invocation ->
                    operationFor(invocation.getArgument(0), "to-owner-unconfirmed"));
            var client = mock(com.foggy.navigator.claude.worker.client.ClaudeWorkerClient.class);
            when(workerService.createClient(any())).thenReturn(client);
            when(client.terminationSigningSecret()).thenReturn("worker-token");
            when(client.forceAbortTask(eq(TASK_ID), any()))
                    .thenReturn(reactor.core.publisher.Mono.error(
                            new IllegalStateException("sensitive remote detail")));

            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> service.cancelTaskDirect(TASK_ID, USER_ID, true));

            assertEquals("TERMINATION_OWNER_FORCE_UNCONFIRMED", error.getMessage());
            assertEquals("CANCEL_REQUESTED", entity.getStatus());
            assertEquals("TERMINATION_OWNER_FORCE_UNCONFIRMED", entity.getErrorMessage());
            verify(terminationOperationService).markUnconfirmed(
                    eq("to-owner-unconfirmed"), anyString());
            verify(terminationOperationService, never()).markObservedTerminal(anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ClaudeTaskEntity createRunningTask() {
        ClaudeTaskEntity entity = new ClaudeTaskEntity();
        entity.setTaskId(TASK_ID);
        entity.setSessionId(SESSION_ID);
        entity.setWorkerId(WORKER_ID);
        entity.setUserId(USER_ID);
        entity.setTenantId(TENANT_ID);
        entity.setStatus("RUNNING");
        entity.setCwd("D:\\projects");
        return entity;
    }

    private ClaudeWorkerEntity createWorkerEntity() {
        ClaudeWorkerEntity worker = new ClaudeWorkerEntity();
        worker.setWorkerId(WORKER_ID);
        worker.setUserId(USER_ID);
        worker.setTenantId(TENANT_ID);
        worker.setStatus("ONLINE");
        return worker;
    }

    private TerminationOperationService installTerminationOperationService() {
        TerminationOperationService terminationOperationService = mock(TerminationOperationService.class);
        try {
            var field = ClaudeTaskService.class.getDeclaredField("terminationOperationService");
            field.setAccessible(true);
            field.set(service, terminationOperationService);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        return terminationOperationService;
    }

    private TerminationOperationEntity operationFor(TerminationOperationService.CreateCommand command,
                                                    String operationId) {
        TerminationOperationEntity operation = new TerminationOperationEntity();
        operation.setOperationId(operationId);
        operation.setSchemaVersion(1);
        operation.setTaskId(command.taskId());
        operation.setProviderTaskId(command.providerTaskId());
        operation.setSessionId(command.sessionId());
        operation.setOwnerUserId(command.ownerUserId());
        operation.setTenantId(command.tenantId());
        operation.setProviderType(command.providerType());
        operation.setWorkerId(command.workerId());
        operation.setKind(command.kind());
        operation.setOrigin(command.origin());
        operation.setActorId(command.actorId());
        operation.setActorType(command.actorType());
        operation.setAuthorizationDecisionId(command.authorizationDecisionId());
        operation.setReasonCode(command.reasonCode());
        operation.setCorrelationId(command.correlationId());
        operation.setExpectedPid(command.expectedPid());
        operation.setExpectedProcessIdentity(command.expectedProcessIdentity());
        return operation;
    }

    private Map<String, Object> observedManualPidOperation(String operationId, String taskId, String workerId) {
        return Map.of(
                "operation_id", operationId,
                "task_id", taskId,
                "worker_id", workerId,
                "kind", "MANUAL_PID_KILL",
                "origin", "ADMIN_MANUAL",
                "status", "OBSERVED_EXIT",
                "observed_exit", true,
                "observed_at", "2026-07-16T03:40:13.655Z");
    }

    private Map<String, Object> ownerForceReceipt(
            String operationId, String taskId, String workerId, String ownerUserId) {
        return Map.of(
                "task_id", taskId,
                "status", "ABORTED",
                "terminal_observed", true,
                "terminal_status", "ABORTED",
                "terminal_source", "OWNER_FORCE_CANCEL",
                "termination_operation", Map.of(
                        "operation_id", operationId,
                        "task_id", taskId,
                        "worker_id", workerId,
                        "kind", "OWNER_FORCE_CANCEL",
                        "origin", "UPSTREAM_USER",
                        "actor_id", ownerUserId,
                        "actor_type", "TASK_OWNER_FORCE_CANCEL",
                        "status", "OBSERVED_TERMINAL",
                        "observed_at", "2026-07-28T09:00:00Z"));
    }
}
