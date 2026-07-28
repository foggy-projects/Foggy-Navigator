package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.event.WorkerTaskStartEvent;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.command.CodexTaskCreateCommand;
import com.foggy.navigator.codex.worker.model.CodexRuntimeBinding;
import com.foggy.navigator.codex.worker.model.CodexRuntimeType;
import com.foggy.navigator.codex.worker.repository.CodexCodingAgentRepository;
import com.foggy.navigator.codex.worker.model.entity.CodexTaskEntity;
import com.foggy.navigator.codex.worker.repository.CodexTaskRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.TerminationOperationEntity;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.service.SessionForwardService;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.InternalTaskDispatchMarkers;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import com.foggy.navigator.spi.config.LlmModelManager;
import com.foggy.navigator.spi.worker.WorkerManagementFacade;
import com.foggy.navigator.session.service.TerminationOperationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CodexTaskServiceTest {

    @Test
    void createTaskDirectDeclaresTransactionBoundaryForSessionTaskLocking() throws Exception {
        var method = CodexTaskService.class.getMethod(
                "createTaskDirect", Map.class, String.class, String.class);

        assertNotNull(method.getAnnotation(Transactional.class));
    }

    @Test
    void resumeTaskCommitsStaleRepairExceptionButRollsBackOtherRuntimeFailures() throws Exception {
        var attributeSource = new AnnotationTransactionAttributeSource();
        var methods = List.of(
                CodexTaskService.class.getMethod(
                        "resumeTask", String.class, String.class, Map.class),
                CodexTaskService.class.getMethod(
                        "resumeTaskForProvider", String.class, String.class, String.class, Map.class));

        for (var method : methods) {
            var transaction = attributeSource.getTransactionAttribute(method, CodexTaskService.class);
            assertNotNull(transaction, method.getName());
            assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                    transaction.getIsolationLevel(), method.getName());
            assertFalse(transaction.rollbackOn(new CodexStaleTaskRepairedException()), method.getName());
            assertTrue(transaction.rollbackOn(new IllegalStateException("other failure")), method.getName());
        }
    }

    @Test
    void sessionForwardCommitsTaskStateRepairAtReadCommittedIsolation() throws Exception {
        var method = SessionForwardService.class.getMethod(
                "forwardToNewSession", SessionForwardCreateRequest.class, String.class, String.class);
        var transaction = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(method, SessionForwardService.class);

        assertNotNull(transaction);
        assertEquals(TransactionDefinition.ISOLATION_READ_COMMITTED,
                transaction.getIsolationLevel());
        assertFalse(transaction.rollbackOn(new CodexStaleTaskRepairedException()));
        assertTrue(transaction.rollbackOn(new IllegalStateException("other failure")));
    }

    @Mock
    private CodexTaskRepository taskRepository;
    @Mock
    private WorkerManagementFacade workerManagementFacade;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private SessionEntityRepository sessionEntityRepository;
    @Mock
    private EntityManager entityManager;
    @Mock
    private NativeSubtaskStateRepository nativeSubtaskStateRepository;
    @Mock
    private CodexCodingAgentRepository codingAgentRepository;
    @Mock
    private CodexStreamRelay streamRelay;
    @Mock
    private CodexRuntimeRegistryService runtimeRegistryService;
    @Mock
    private CodexWorkerClientFactory clientFactory;
    @Mock
    private CodexWorkerClient workerClient;
    @Mock
    private CodexTaskRuntimeStateService taskRuntimeStateService;
    @Mock
    private TerminationOperationService terminationOperationService;

    private CodexTaskService service;

    @BeforeEach
    void setUp() {
        service = new CodexTaskService(
                taskRepository, workerManagementFacade, eventPublisher, clientFactory,
                taskRuntimeStateService);
        ReflectionTestUtils.setField(service, "llmModelManager", llmModelManager);
        ReflectionTestUtils.setField(service, "sessionManager", sessionManager);
        ReflectionTestUtils.setField(service, "sessionTaskRepository", sessionTaskRepository);
        ReflectionTestUtils.setField(service, "sessionEntityRepository", sessionEntityRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "nativeSubtaskStateRepository", nativeSubtaskStateRepository);
        ReflectionTestUtils.setField(service, "codingAgentRepository", codingAgentRepository);
        ReflectionTestUtils.setField(service, "streamRelay", streamRelay);
        ReflectionTestUtils.setField(service, "runtimeRegistryService", runtimeRegistryService);

        lenient().when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.empty());
        lenient().when(sessionTaskRepository.findByTaskIdForUpdate(anyString()))
                .thenAnswer(invocation -> sessionTaskRepository.findByTaskId(invocation.getArgument(0)));
        lenient().when(taskRepository.findByTaskIdForUpdate(anyString()))
                .thenAnswer(invocation -> taskRepository.findByTaskId(invocation.getArgument(0)));
        lenient().when(sessionTaskRepository.save(any(SessionTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(sessionEntityRepository.findById(anyString())).thenAnswer(invocation -> {
            SessionEntity session = new SessionEntity();
            session.setId(invocation.getArgument(0));
            return Optional.of(session);
        });
        lenient().when(sessionEntityRepository.findResumeStateByIdAndUserId(anyString(), anyString()))
                .thenAnswer(invocation -> sessionEntityRepository.findById(invocation.getArgument(0))
                        .filter(session -> Objects.equals(session.getUserId(), invocation.getArgument(1)))
                        .map(this::resumeStateView));
        lenient().when(sessionEntityRepository.findResumeStateByIdAndUserIdForUpdate(
                        anyString(), anyString()))
                .thenAnswer(invocation -> sessionEntityRepository.findById(invocation.getArgument(0))
                        .filter(session -> Objects.equals(session.getUserId(), invocation.getArgument(1)))
                        .map(this::resumeStateView));
        lenient().when(sessionEntityRepository.findByIdAndUserIdForUpdate(anyString(), anyString()))
                .thenAnswer(invocation -> sessionEntityRepository.findById(invocation.getArgument(0))
                        .filter(session -> Objects.equals(session.getUserId(), invocation.getArgument(1))));
        lenient().when(sessionEntityRepository.save(any(SessionEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(runtimeRegistryService.selectForNewTask(
                        anyString(), any(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    String workerId = invocation.getArgument(0);
                    String model = invocation.getArgument(1);
                    if (model != null && (model.equalsIgnoreCase("codex-ultra")
                            || model.toLowerCase().endsWith(":ultra"))) {
                        return CodexRuntimeBinding.builder()
                                .runtimeId("app-main")
                                .runtimeRevision(1)
                                .runtimeType(CodexRuntimeType.APP_SERVER)
                                .workerId(workerId)
                                .endpointUrl("http://127.0.0.1:3062")
                                .instanceId("instance-a")
                                .routingEpoch(1L)
                                .build();
                    }
                    return CodexRuntimeBinding.legacySdk(workerId);
                });
    }

    @Test
    void exposesOnlySupportedTaskProviderPorts() {
        assertInstanceOf(TaskLookupProvider.class, service);
        assertInstanceOf(TaskCommandProvider.class, service);
        assertInstanceOf(TaskListingProvider.class, service);
        assertFalse(service instanceof WorkerSessionQueryProvider);
        assertTrue(service.getCapabilities().contains(TaskQueryCapability.RESPOND_TO_TASK));
    }

    @Test
    void completionReadinessAcceptsOnlyExactWorkerTaskAndDispatchBoundReceipt() {
        CodexTaskEntity task = completionReadinessTask("task-complete", "worker-task-complete");
        when(workerClient.getTaskCompletionReadiness("worker-task-complete"))
                .thenReturn(Mono.just(completionObservation(
                        "worker-1", "worker-task-complete", 1)));

        var observed = service.inspectRuntimeCompletionReadiness(
                "task-complete", "worker-1", 1);

        assertEquals(true, observed.workerReachable());
        assertEquals(true, observed.identityVerified());
        assertEquals("CODEX_COMPLETION_RECEIPT_V2", observed.evidenceSchema());
        assertEquals("worker-task-complete", observed.providerTaskId());
        assertEquals(1, observed.receiptDispatchCount());
        assertEquals(true, observed.resultRecoverable());
        assertNull(observed.sanitizedErrorCode());
        verify(workerClient).getTaskCompletionReadiness("worker-task-complete");
    }

    @Test
    void completionReadinessFailsClosedForDispatchOrWorkerIdentityMismatch() {
        completionReadinessTask("task-mismatch", "worker-task-mismatch");
        when(workerClient.getTaskCompletionReadiness("worker-task-mismatch"))
                .thenReturn(Mono.just(completionObservation(
                        "worker-other", "worker-task-mismatch", 2)));

        var observed = service.inspectRuntimeCompletionReadiness(
                "task-mismatch", "worker-1", 1);

        assertEquals(true, observed.workerReachable());
        assertEquals(false, observed.identityVerified());
        assertEquals("WORKER_COMPLETION_EVIDENCE_IDENTITY_MISMATCH",
                observed.sanitizedErrorCode());
    }

    @Test
    void completionReadinessReportsUnsupportedWithoutCallingAppServerWorker() {
        CodexTaskEntity task = completionReadinessTask("task-app", "provider-task-app");
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.APP_SERVER.name());

        var observed = service.inspectRuntimeCompletionReadiness(
                "task-app", "worker-1", 1);

        assertNull(observed.workerReachable());
        assertEquals("UNKNOWN", observed.providerProcessState());
        assertEquals("RUNTIME_COMPLETION_READINESS_UNSUPPORTED",
                observed.sanitizedErrorCode());
        verifyNoInteractions(workerClient);
    }

    @Test
    void sdkProviderParamsCannotOverrideRouteToAppServer() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE),
                        "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("CODEX_TASK_PROVIDER_MISMATCH"));
        verifyNoInteractions(workerManagementFacade, eventPublisher);
    }

    @Test
    void providerAliasesCannotHideAConflictingRoute() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirectForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        Map.of(
                                "workerId", "worker-1",
                                "prompt", "hello",
                                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                                "provider_type", CodexTaskService.CODEX_PROVIDER_TYPE),
                        "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("CODEX_TASK_PROVIDER_MISMATCH"));
        verifyNoInteractions(workerManagementFacade, eventPublisher);
    }

    @Test
    void sdkProviderCommandRejectsAppServerTask() {
        CodexTaskEntity task = createTask(
                "task-app", "session-app", "worker-1", "dir-1", "RUNNING", LocalDateTime.now());
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        when(taskRepository.findByTaskIdAndUserId("task-app", "user-1")).thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-app", "user-1"))
                .thenReturn(Optional.of(task));

        assertThrows(IllegalArgumentException.class,
                () -> service.cancelTaskDirect("task-app", "user-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.respondToTask("task-app", "user-1", Map.of(
                        "permissionId", "request-1", "answers", Map.of("choice", "one"))));

        verifyNoInteractions(streamRelay);
        verifyNoInteractions(workerClient);
    }

    @Test
    void cancelRequestedTaskCanEnterTheExactTaskCancellationFlowAgain() {
        CodexTaskEntity task = createTask(
                "task-cancel-retry", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskIdAndUserId("task-cancel-retry", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdForUpdate("task-cancel-retry"))
                .thenReturn(Optional.of(task));
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.cancelTaskDirectForProvider(
                        CodexTaskService.CODEX_PROVIDER_TYPE, "task-cancel-retry", "user-1"));

        assertEquals("CANCEL_REQUESTED", task.getStatus());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", task.getErrorMessage());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", error.getSafeCode());
        verify(taskRepository, times(2)).findByTaskIdForUpdate("task-cancel-retry");
    }

    @Test
    void repeatedCancelRepairsVerifiedAbsentSdkTaskWithoutMintingTerminationOperation() {
        CodexTaskEntity task = createTask(
                "task-cancel-stale", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setWorkerTaskId("worker-task-stale");
        task.setCodexThreadId("thread-stale");
        when(taskRepository.findByTaskIdAndUserId("task-cancel-stale", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdForUpdate("task-cancel-stale"))
                .thenReturn(Optional.of(task));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setLatestTaskId("task-cancel-stale");
        session.setInteractionState("PROCESSING");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        service.cancelTaskDirectForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-cancel-stale", "user-1");

        assertEquals("ABORTED", task.getStatus());
        assertNull(task.getErrorMessage());
        verify(workerClient).getTaskStatus("worker-task-stale");
        verify(workerClient).listCliProcesses();
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "task-cancel-stale".equals(entity.getTaskId())
                        && "ABORTED".equals(entity.getStatus())
                        && entity.getErrorMessage() == null));
        assertEquals("AWAITING_REPLY", session.getInteractionState());
        verify(sessionEntityRepository).save(session);
        verify(terminationOperationService).markObservedTerminalForTask(
                "task-cancel-stale", "ABORTED");
        verify(terminationOperationService, never()).accept(any());
        verify(workerClient, never()).abortTask(anyString(), any());
    }

    @Test
    void repeatedCancelKeepsExistingFlowWhenSdkProcessSnapshotIsInconclusive() {
        CodexTaskEntity task = createTask(
                "task-cancel-inconclusive", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setWorkerTaskId("worker-task-stale");
        task.setCodexThreadId("thread-stale");
        when(taskRepository.findByTaskIdAndUserId("task-cancel-inconclusive", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdForUpdate("task-cancel-inconclusive"))
                .thenReturn(Optional.of(task));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(Map.of(
                "processes", List.of(), "active_task_count", 0, "total", 1)));
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.cancelTaskDirectForProvider(
                        CodexTaskService.CODEX_PROVIDER_TYPE, "task-cancel-inconclusive", "user-1"));

        assertEquals("CANCEL_REQUESTED", task.getStatus());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", task.getErrorMessage());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", error.getSafeCode());
        verify(workerClient).getTaskStatus("worker-task-stale");
        verify(workerClient).listCliProcesses();
        verify(taskRepository, times(2)).findByTaskIdForUpdate("task-cancel-inconclusive");
    }

    @Test
    void repeatedCancelDoesNotOverwriteTerminalStateCommittedDuringAbsenceProbe() {
        CodexTaskEntity observed = createTask(
                "task-cancel-race", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        observed.setTenantId("tenant-1");
        observed.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        observed.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        observed.setWorkerTaskId("worker-task-stale");
        observed.setCodexThreadId("thread-stale");
        CodexTaskEntity terminal = createTask(
                "task-cancel-race", "session-1", "worker-1", "dir-1",
                "ABORTED", observed.getCreatedAt());
        terminal.setTenantId("tenant-1");
        terminal.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        terminal.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        terminal.setWorkerTaskId("worker-task-stale");
        terminal.setCodexThreadId("thread-stale");
        when(taskRepository.findByTaskIdAndUserId("task-cancel-race", "user-1"))
                .thenReturn(Optional.of(observed));
        lenient().when(taskRepository.findByTaskIdForUpdate("task-cancel-race"))
                .thenReturn(Optional.of(terminal));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));

        service.cancelTaskDirectForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-cancel-race", "user-1");

        assertEquals("CANCEL_REQUESTED", observed.getStatus());
        assertEquals("ABORTED", terminal.getStatus());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(terminationOperationService);
    }

    @Test
    void pendingUserInputAcceptsOpaquePoolInstanceAndProjectsAwaitingInput() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));

        CodexTaskService.UserInputRegistration registration = service.registerPendingUserInput(
                "task-input", pendingInputProjection(true));

        assertTrue(registration.shouldPublish());
        assertEquals("task:task-input:string:request-1", registration.requestId());
        assertEquals("AWAITING_INPUT", task.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> persisted = (Map<String, Object>) ProviderStateCodec
                .parseObject(sessionTask.getTaskStateJson()).get("codexPendingInteraction");
        assertEquals("pool-lease-7", persisted.get("runtime_instance_id"));
        assertEquals("PENDING", persisted.get("state"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> questions =
                (List<Map<String, Object>>) registration.confirmationPayload().get("questions");
        assertEquals(false, questions.get(0).get("multiSelect"));
        assertEquals(true, questions.get(0).get("isSecret"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options =
                (List<Map<String, Object>>) questions.get(0).get("options");
        assertEquals("", options.get(0).get("description"));
    }

    @Test
    void duplicateRequestIdCannotReplacePendingTurnMetadata() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));
        service.registerPendingUserInput("task-input", pendingInputProjection(false));
        Map<String, Object> conflictingReplay = pendingInputProjection(false);
        conflictingReplay.put("turn_id", "turn-2");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.registerPendingUserInput("task-input", conflictingReplay));

        assertEquals("CODEX_USER_INPUT_REPLAY_MISMATCH", error.getMessage());
        assertTrue(sessionTask.getTaskStateJson().contains("\"turn_id\":\"turn-1\""));
        assertFalse(sessionTask.getTaskStateJson().contains("\"turn_id\":\"turn-2\""));
    }

    @Test
    void respondToTaskUsesBoundPhysicalInstanceAndNeverPersistsSecretAnswer() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-input", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));
        service.registerPendingUserInput("task-input", pendingInputProjection(true));

        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(2)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .instanceId("worker-instance-a")
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "worker-instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://127.0.0.1:3062", null, "worker-instance-a"))
                .thenReturn(workerClient);
        when(workerClient.respondToTask(eq("worker-task-1"), any()))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-task-1",
                        "status", "running",
                        "request_id", "request-1")));

        service.respondToTaskForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                "task-input", "user-1", Map.of(
                "permissionId", "task:task-input:string:request-1",
                "answers", Map.of("choice", List.of("private answer"))));

        assertEquals("RUNNING", task.getStatus());
        assertFalse(sessionTask.getTaskStateJson().contains("private answer"));
        assertTrue(sessionTask.getTaskStateJson().contains("\"state\":\"RESOLVED\""));
        verify(workerClient).respondToTask(eq("worker-task-1"), argThat(body ->
                "request-1".equals(body.get("request_id"))
                        && Map.of("choice", List.of("private answer")).equals(body.get("answers"))));
        verify(streamRelay).publishUserInputResponse(
                "session-1", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-input",
                "task:task-input:string:request-1", "allow", null);

        CodexTaskService.UserInputResolution replay = service.resolvePendingUserInput(
                "task-input", Map.of("request_id", "request-1", "reason", "answered"));
        assertFalse(replay.shouldPublish());

        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> service.respondToTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "task-input", "user-1", Map.of(
                        "permissionId", "task:task-input:string:request-1",
                        "answers", Map.of("choice", "private answer"))));
        assertEquals("CODEX_USER_INPUT_NOT_PENDING", duplicate.getMessage());
    }

    @Test
    void answeredSseConvergesAfterWorkerAcceptedButHttpResponseWasLost() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-input", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));
        service.registerPendingUserInput("task-input", pendingInputProjection(false));

        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(2)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .instanceId("worker-instance-a")
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "worker-instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://127.0.0.1:3062", null, "worker-instance-a"))
                .thenReturn(workerClient);
        when(workerClient.respondToTask(eq("worker-task-1"), any()))
                .thenReturn(Mono.error(new RuntimeException("response lost after acceptance")));

        IllegalStateException responseError = assertThrows(IllegalStateException.class,
                () -> service.respondToTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "task-input", "user-1", Map.of(
                        "permissionId", "task:task-input:string:request-1",
                        "answers", Map.of("choice", "one"))));
        assertEquals("CODEX_USER_INPUT_RESPONSE_UNKNOWN", responseError.getMessage());
        assertEquals("AWAITING_INPUT", task.getStatus());
        assertTrue(sessionTask.getTaskStateJson().contains("\"state\":\"PENDING\""));

        CodexTaskService.UserInputResolution resolution = service.resolvePendingUserInput(
                "task-input", Map.of("request_id", "request-1", "reason", "answered"));

        assertTrue(resolution.shouldPublish());
        assertEquals("allow", resolution.decision());
        assertEquals("task:task-input:string:request-1", resolution.requestId());
        assertEquals("RUNNING", task.getStatus());
        assertTrue(sessionTask.getTaskStateJson().contains("\"state\":\"RESOLVED\""));
        assertTrue(sessionTask.getTaskStateJson().contains("\"resolved_reason\":\"answered\""));
    }

    @Test
    void respondToTaskRejectsPhysicalRuntimeAffinityMismatchBeforeCallingWorker() {
        CodexTaskEntity task = appServerInputTask("AWAITING_INPUT");
        SessionTaskEntity sessionTask = inputSessionTask();
        sessionTask.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                null, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "codexPendingInteraction",
                pendingState(pendingInputProjection(false), "PENDING")));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-input", "user-1"))
                .thenReturn(Optional.of(task));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "worker-instance-a"))
                .thenThrow(new CodexRuntimeUnavailableException(
                        "CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH", "physical instance changed"));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.respondToTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "task-input", "user-1", Map.of(
                        "permissionId", "task:task-input:string:request-1",
                        "answers", Map.of("choice", "one"))));

        assertEquals("CODEX_RUNTIME_INSTANCE_AFFINITY_MISMATCH", error.getCode());
        verifyNoInteractions(workerClient);
        assertEquals("AWAITING_INPUT", task.getStatus());
    }

    @Test
    void respondToTaskRejectsMultipleAnswersForCodexQuestion() {
        CodexTaskEntity task = appServerInputTask("AWAITING_INPUT");
        SessionTaskEntity sessionTask = inputSessionTask();
        sessionTask.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                null, CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "codexPendingInteraction",
                pendingState(pendingInputProjection(false), "PENDING")));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-input", "user-1"))
                .thenReturn(Optional.of(task));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.respondToTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "task-input", "user-1", Map.of(
                        "permissionId", "task:task-input:string:request-1",
                        "answers", Map.of("choice", List.of("one", "two")))));

        assertEquals("CODEX_USER_INPUT_ANSWER_CARDINALITY_INVALID", error.getMessage());
        verifyNoInteractions(workerClient);
    }

    @Test
    void numericRequestIdUsesTypedUiTokenAndPreservesNumericWireId() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-input", "user-1"))
                .thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));

        CodexTaskService.UserInputRegistration registration = service.registerPendingUserInput(
                "task-input", pendingInputProjection(false, 1));
        assertEquals("task:task-input:number:1", registration.requestId());
        IllegalStateException stringToken = assertThrows(IllegalStateException.class,
                () -> service.respondToTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "task-input", "user-1", Map.of(
                        "permissionId", "task:task-input:string:1",
                        "answers", Map.of("choice", "one"))));
        assertEquals("CODEX_USER_INPUT_REQUEST_MISMATCH", stringToken.getMessage());

        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main").runtimeRevision(2).runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1").endpointUrl("http://127.0.0.1:3062")
                .instanceId("worker-instance-a").routingEpoch(1L).build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 2, "worker-1", "worker-instance-a")).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://127.0.0.1:3062", null, "worker-instance-a"))
                .thenReturn(workerClient);
        when(workerClient.respondToTask(eq("worker-task-1"), any()))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-task-1", "status", "running", "request_id", 1)));

        service.respondToTaskForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                "task-input", "user-1", Map.of(
                "permissionId", "task:task-input:number:1",
                "answers", Map.of("choice", "one")));

        verify(workerClient).respondToTask(eq("worker-task-1"), argThat(body ->
                body.get("request_id") instanceof Number number && number.longValue() == 1L));
        assertEquals("RUNNING", task.getStatus());
    }

    @Test
    void pendingUserInputAcceptsLockedProtocolFieldLimits() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        SessionTaskEntity sessionTask = inputSessionTask();
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-input")).thenReturn(Optional.of(sessionTask));
        Map<String, Object> projection = pendingInputProjection(false);
        @SuppressWarnings("unchecked")
        Map<String, Object> question = ((List<Map<String, Object>>) projection.get("questions")).get(0);
        String questionText = "q".repeat(2_047) + "\n" + "q".repeat(2_048);
        String descriptionText = "d".repeat(1_023) + "\n" + "d".repeat(1_024);
        question.put("id", "i".repeat(256));
        question.put("question", questionText);
        question.put("options", List.of(Map.of(
                "label", "l".repeat(256), "description", descriptionText)));
        projection.put("auto_resolution_ms", 240_000L);

        CodexTaskService.UserInputRegistration registration =
                service.registerPendingUserInput("task-input", projection);

        assertTrue(registration.shouldPublish());
        @SuppressWarnings("unchecked")
        Map<String, Object> projectedQuestion = ((List<Map<String, Object>>)
                registration.confirmationPayload().get("questions")).get(0);
        assertEquals(questionText, projectedQuestion.get("question"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projectedOptions =
                (List<Map<String, Object>>) projectedQuestion.get("options");
        assertEquals(descriptionText, projectedOptions.get(0).get("description"));
        assertEquals(16_384, ((String) ReflectionTestUtils.invokeMethod(
                service, "singleUserInputAnswer", "a".repeat(16_384))).length());
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "singleUserInputAnswer", "a".repeat(16_385)));
    }

    @Test
    void pendingUserInputRejectsOverflowingAutoResolutionInteger() {
        CodexTaskEntity task = appServerInputTask("RUNNING");
        when(taskRepository.findByTaskIdForUpdate("task-input")).thenReturn(Optional.of(task));
        Map<String, Object> projection = pendingInputProjection(false);
        projection.put("auto_resolution_ms", Long.MAX_VALUE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.registerPendingUserInput("task-input", projection));

        assertEquals("CODEX_USER_INPUT_AUTO_RESOLUTION_INVALID", error.getMessage());
    }

    @Test
    void reconnectTaskRoutesOwnedRunningTaskToStreamRelay() {
        CodexTaskEntity task = createTask(
                "task-reconnect", "session-1", "worker-1", null, "RUNNING", LocalDateTime.now());
        when(taskRepository.findByTaskIdAndUserId("task-reconnect", "user-1"))
                .thenReturn(Optional.of(task));

        service.reconnectTask("task-reconnect", "user-1");

        verify(streamRelay).reconnectTask("task-reconnect", "session-1", "worker-1");
    }

    @Test
    void reconnectTaskDoesNotReconnectTerminalTask() {
        CodexTaskEntity task = createTask(
                "task-complete", "session-1", "worker-1", null, "COMPLETED", LocalDateTime.now());
        when(taskRepository.findByTaskIdAndUserId("task-complete", "user-1"))
                .thenReturn(Optional.of(task));

        service.reconnectTask("task-complete", "user-1");

        verifyNoInteractions(streamRelay);
    }

    @Test
    void getTaskExposesProviderTypeAndAuthoritativeCreatedAtEpoch() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 18, 30, 15);
        long createdAtEpochMs = 1_783_685_415_123L;
        CodexTaskEntity task = createTask(
                "task-biz", "session-1", "worker-1", null, "RUNNING", createdAt);
        task.setCreatedAtEpochMs(createdAtEpochMs);
        when(taskRepository.findByTaskIdAndUserId("task-biz", "user-1"))
                .thenReturn(Optional.of(task));

        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-biz");
        sessionTask.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        when(sessionTaskRepository.findByTaskId("task-biz")).thenReturn(Optional.of(sessionTask));

        TimeZone originalTimeZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            DispatchTaskDTO utcResult = service.getTask("user-1", "task-biz");
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            DispatchTaskDTO shanghaiResult = service.getTask("user-1", "task-biz");

            assertEquals(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, utcResult.getProviderType());
            assertEquals(createdAtEpochMs, utcResult.getCreatedAtEpochMs());
            assertEquals(createdAtEpochMs, shanghaiResult.getCreatedAtEpochMs());

            task.setCreatedAtEpochMs(null);
            assertNull(service.getTask("user-1", "task-biz").getCreatedAtEpochMs());
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void listTasksBatchLoadsProviderTypesWithoutPerTaskQueries() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 10, 18, 30);
        CodexTaskEntity taskProjection = createTask(
                "task-projection", "session-1", "worker-1", null, "COMPLETED", createdAt);
        CodexTaskEntity sessionFallback = createTask(
                "task-session", "session-2", "worker-1", null, "COMPLETED", createdAt.minusMinutes(1));
        CodexTaskEntity defaultFallback = createTask(
                "task-default", null, "worker-1", null, "COMPLETED", createdAt.minusMinutes(2));
        List.of(taskProjection, sessionFallback, defaultFallback).forEach(task -> {
            task.setResolvedAgentId("agent-1");
            task.setContextId("context-" + task.getTaskId());
        });
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(taskProjection, sessionFallback, defaultFallback));

        SessionTaskEntity projection = new SessionTaskEntity();
        projection.setTaskId("task-projection");
        projection.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        when(sessionTaskRepository.findByTaskIdIn(any())).thenReturn(List.of(projection));

        SessionEntity session = new SessionEntity();
        session.setId("session-2");
        session.setProviderType(CodexTaskService.CODEX_BIZ_PROVIDER_TYPE);
        when(sessionEntityRepository.findAllById(any())).thenReturn(List.of(session));

        List<DispatchTaskDTO> result = service.listTasks("user-1");

        assertEquals(List.of(
                        CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                        CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                        CodexTaskService.CODEX_PROVIDER_TYPE),
                result.stream().map(DispatchTaskDTO::getProviderType).toList());
        verify(sessionTaskRepository).findByTaskIdIn(any());
        verify(sessionEntityRepository).findAllById(any());
        verify(sessionTaskRepository, never()).findByTaskId(anyString());
        verify(sessionEntityRepository, never()).findById(anyString());
    }

    @Test
    void deleteTaskRemovesNativeSubtaskSnapshots() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("task-delete");
        entity.setUserId("user-1");
        entity.setStatus("COMPLETED");
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeId("app-main");
        entity.setRuntimeRevision(2);
        entity.setRuntimeInstanceId("instance-a");
        entity.setWorkerId("worker-1");
        entity.setWorkerTaskId("task-delete");
        when(taskRepository.findByTaskIdAndUserId("task-delete", "user-1"))
                .thenReturn(Optional.of(entity));
        when(taskRuntimeStateService.claimTerminalDeletion("task-delete", "user-1"))
                .thenReturn(entity);
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(2)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .authToken("runtime-token")
                .instanceId("instance-a")
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 2, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://127.0.0.1:3062", "runtime-token", "instance-a"))
                .thenReturn(workerClient);
        when(workerClient.deleteTask("task-delete")).thenReturn(Mono.just(true));

        service.deleteTask("user-1", "task-delete");

        verify(runtimeRegistryService).resolveBoundRuntime("app-main", 2, "worker-1", "instance-a");
        verify(workerClient).deleteTask("task-delete");
        verify(nativeSubtaskStateRepository).deleteByTaskId("task-delete");
        verify(taskRepository).delete(entity);
        verifyNoInteractions(streamRelay);
    }

    @Test
    void deleteTaskKeepsLocalProjectionsWhenAppServerDeleteFails() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("task-delete");
        entity.setUserId("user-1");
        entity.setStatus("FAILED");
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeId("app-main");
        entity.setRuntimeRevision(2);
        entity.setRuntimeInstanceId("instance-a");
        entity.setWorkerId("worker-1");
        entity.setWorkerTaskId("task-delete");
        when(taskRepository.findByTaskIdAndUserId("task-delete", "user-1"))
                .thenReturn(Optional.of(entity));
        when(taskRuntimeStateService.claimTerminalDeletion("task-delete", "user-1"))
                .thenReturn(entity);
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(2)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .instanceId("instance-a")
                .build();
        when(runtimeRegistryService.resolveBoundRuntime("app-main", 2, "worker-1", "instance-a"))
                .thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:app-main:2", "http://127.0.0.1:3062", null, "instance-a"))
                .thenReturn(workerClient);
        when(workerClient.deleteTask("task-delete"))
                .thenReturn(Mono.error(new IllegalStateException("runtime unavailable")));

        assertThrows(IllegalStateException.class,
                () -> service.deleteTask("user-1", "task-delete"));

        verify(nativeSubtaskStateRepository, never()).deleteByTaskId(anyString());
        verify(taskRepository, never()).delete(any());
    }

    @Test
    void resolveCodexAuth_returnsEmptyWhenNoApiKey() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl(null);

        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-1")).thenReturn(null);

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveCodexAuth", "cfg-1");

        assertNotNull(result);
        // CodexAuthResult record — access via reflection
        assertNull(ReflectionTestUtils.invokeMethod(result, "apiKey"));
        assertNull(ReflectionTestUtils.invokeMethod(result, "baseUrl"));
    }

    @Test
    void resolveCodexAuth_returnsApiKeyAndBaseUrl() {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl("https://api.openai.com/v1");

        when(llmModelManager.getModelConfig("cfg-2")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-2")).thenReturn("sk-live");

        Object result = ReflectionTestUtils.invokeMethod(service, "resolveCodexAuth", "cfg-2");

        assertNotNull(result);
        assertEquals("sk-live", ReflectionTestUtils.invokeMethod(result, "apiKey"));
        assertEquals("https://api.openai.com/v1", ReflectionTestUtils.invokeMethod(result, "baseUrl"));
    }

    @Test
    void listTasksPaged_groupsCodexTasksBySessionAndSupportsInteractionStateFilter() {
        CodexTaskEntity running = createTask(
                "task-running", "session-running", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 22, 0)
        );
        CodexTaskEntity completed = createTask(
                "task-completed", "session-completed", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 3, 24, 21, 0)
        );

        when(taskRepository.findByUserIdOrderByCreatedAtDesc("user-1"))
                .thenReturn(List.of(running, completed));

        TaskPageResult page = service.listTaskPage("user-1", 0, 20, "PROCESSING");
        assertEquals(1L, page.totalSessions());
        List<?> content = page.content();
        assertEquals(1, content.size());
        DispatchTaskDTO task = assertInstanceOf(DispatchTaskDTO.class, content.get(0));
        assertEquals("task-running", task.getTaskId());
        assertEquals("session-running", task.getSessionId());
    }

    @Test
    void codexBizProviderFiltersLookupListingAndSearchAwayFromPlainCodexTasks() {
        CodexTaskEntity plain = createTask(
                "task-plain", "session-plain", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 22, 0)
        );
        plain.setProviderType("codex-worker");
        plain.setResultText("plain result");

        CodexTaskEntity biz = createTask(
                "task-biz", "session-biz", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 24, 23, 0)
        );
        biz.setProviderType("codex-biz-worker");
        biz.setPrompt("actor decision");
        biz.setResultText("biz result");

        when(taskRepository.findByTaskId("task-plain")).thenReturn(Optional.of(plain));
        when(taskRepository.findByTaskId("task-biz")).thenReturn(Optional.of(biz));
        when(taskRepository.findBySessionId("session-mixed")).thenReturn(List.of(plain, biz));
        when(taskRepository.findByUserIdAndStatusInOrderByCreatedAtDesc("user-1",
                List.of("RUNNING", "AWAITING_PERMISSION", "AWAITING_INPUT", "CANCEL_REQUESTED")))
                .thenReturn(List.of(biz, plain));
        when(taskRepository.findByUserIdOrderByCreatedAtDesc("user-1")).thenReturn(List.of(biz, plain));
        when(taskRepository.findByDirectoryIdAndUserIdOrderByCreatedAtDesc("dir-1", "user-1"))
                .thenReturn(List.of(biz, plain));

        assertTrue(service.getTaskByIdForProvider("task-plain", "codex-biz-worker").isEmpty());
        DispatchTaskDTO bizLookup = service.getTaskByIdForProvider("task-biz", "codex-biz-worker").orElseThrow();
        assertEquals("codex-biz-worker", bizLookup.getProviderType());

        List<DispatchTaskDTO> sessionTasks = service.listTasksBySessionForProvider("session-mixed", "codex-biz-worker");
        assertEquals(1, sessionTasks.size());
        assertEquals("task-biz", sessionTasks.get(0).getTaskId());

        List<DispatchTaskDTO> activeTasks = service.listActiveDispatchTasksForProvider("user-1", "codex-biz-worker");
        assertEquals(1, activeTasks.size());
        assertEquals("task-biz", activeTasks.get(0).getTaskId());

        TaskPageResult page = service.listTasksPagedForProvider("user-1", 0, 20, null, "codex-biz-worker");
        assertEquals(1L, page.totalSessions());
        DispatchTaskDTO pagedTask = assertInstanceOf(DispatchTaskDTO.class, page.content().get(0));
        assertEquals("task-biz", pagedTask.getTaskId());

        TaskPageResult directoryPage = service.listTasksByDirectoryPagedForProvider(
                "user-1", "dir-1", 0, 20, null, "codex-biz-worker");
        assertEquals(1L, directoryPage.totalSessions());
        DispatchTaskDTO directoryTask = assertInstanceOf(DispatchTaskDTO.class, directoryPage.content().get(0));
        assertEquals("task-biz", directoryTask.getTaskId());

        TaskSearchResult search = service.searchSessionsForProvider(
                "user-1", "actor", null, null, 0, 20, "codex-biz-worker");
        assertEquals(1L, search.total());
        Map<?, ?> result = assertInstanceOf(Map.class, search.results().get(0));
        assertEquals("session-biz", result.get("sessionId"));
        assertEquals("task-biz", result.get("latestTaskId"));
    }

    @Test
    void canaryPageScopesTasksByUserTenantWorkerAndPreservesCreationEpoch() {
        CodexTaskEntity included = createTask(
                "task-included", "session-included", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 7, 10, 18, 30));
        included.setTenantId("tenant-1");
        included.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        included.setCreatedAtEpochMs(1_783_685_415_123L);

        CodexTaskEntity otherWorker = createTask(
                "task-other-worker", "session-other", "worker-2", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 7, 10, 18, 29));
        otherWorker.setTenantId("tenant-1");
        otherWorker.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);

        when(taskRepository.findByUserIdAndTenantIdOrderByCreatedAtDesc("user-1", "tenant-1"))
                .thenReturn(List.of(included, otherWorker));

        TaskPageResult page = service.listTasksPagedForProvider(
                "user-1", "tenant-1", 0, 20, null, "worker-1",
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);

        assertEquals(1L, page.totalSessions());
        DispatchTaskDTO task = assertInstanceOf(DispatchTaskDTO.class, page.content().get(0));
        assertEquals("task-included", task.getTaskId());
        assertEquals(1_783_685_415_123L, task.getCreatedAtEpochMs());
        verify(taskRepository).findByUserIdAndTenantIdOrderByCreatedAtDesc("user-1", "tenant-1");
    }

    @Test
    void resumeTask_reusesExistingPlatformSessionAndCodexThread() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        // providerStateJson 中存储 codexThreadId（resume 从此恢复）
        SessionEntity sessionWithState = new SessionEntity();
        sessionWithState.setId("session-1");
        sessionWithState.setUserId("user-1");
        sessionWithState.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        sessionWithState.setLatestTaskId("task-history");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(sessionWithState));
        stubHistoricalResumeTask(
                "task-history", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue please",
                "images", "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]",
                "directoryId", "dir-1",
                "cwd", "/repo"
        ));

        assertEquals("session-1", result.getSessionId());
        assertEquals("thread-1", result.getCodexThreadId());
        assertEquals("RUNNING", result.getStatus());

        verify(sessionManager).addMessage(eq("session-1"), any(Message.class));
        verify(taskRepository).findByTaskIdForUpdate("task-history");
        verify(sessionEntityRepository).findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");
        verify(taskRepository).save(argThat((CodexTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "thread-1".equals(entity.getCodexThreadId())
                        && "worker-1".equals(entity.getWorkerId())
                        && "continue please".equals(entity.getPrompt())
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) -> {
            Map<String, Object> state = ProviderStateCodec.parseObject(entity.getTaskStateJson());
            return "session-1".equals(entity.getSessionId())
                    && "codex-worker".equals(entity.getProviderType())
                    && Integer.valueOf(ProviderStateCodec.CURRENT_SCHEMA_VERSION).equals(state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION))
                    && "codex-worker".equals(state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE))
                    && "thread-1".equals(state.get(ProviderStateCodec.FIELD_CODEX_THREAD_ID));
        }));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-1".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && "worker-1".equals(entity.getCurrentWorkerId())
                        && entity.getProviderStateJson() != null
                        && entity.getProviderStateJson().contains("\"schemaVersion\":1")
                        && entity.getProviderStateJson().contains("\"providerType\":\"codex-worker\"")
                        && entity.getProviderStateJson().contains("\"codexThreadId\":\"thread-1\"")
        ));
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "session-1".equals(event.getSessionId())
                        && "worker-1".equals(event.getWorkerId())
                        && "continue please".equals(event.getPrompt())
                        && "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
                        .equals(event.getProviderConfigString("images"))
                        && "thread-1".equals(event.getProviderConfigString("codexThreadId"))
        ));
    }

    @Test
    void resumeTaskDoesNotAcceptThreadOwnedOnlyByAnotherProvider() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        session.setProviderStateJson("{\"codexThreadId\":\"thread-cross-provider\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(taskRepository.findLatestResumeThreadTaskIds(
                "thread-cross-provider", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE, PageRequest.of(0, 1)))
                .thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,
                () -> service.resumeTask("user-1", "tenant-1", Map.of(
                        "workerId", "worker-1",
                        "sessionId", "session-1",
                        "prompt", "continue")));

        verify(taskRepository).findLatestResumeThreadTaskIds(
                "thread-cross-provider", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE, PageRequest.of(0, 1));
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_normalizesWindowsBackslashCwd() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        DispatchTaskDTO result = service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "cwd", "D:\\projects\\my-app",
                "directoryId", "dir-1",
                "model", "gpt-5.4"
        ), "user-1", "tenant-1");

        // cwd 反斜杠应被转为正斜杠（Codex CLI 不接受 Windows 反斜杠）
        assertEquals("D:/projects/my-app", savedTask[0].getCwd());
        verify(workerManagementFacade).validateWorkerAccess("user-1", "tenant-1", "worker-1");
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "D:/projects/my-app".equals(event.getCwd())
        ));
    }

    @Test
    void createTaskDirect_persistsContextIdInUnifiedTaskState() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        SessionTaskEntity[] savedSessionTask = new SessionTaskEntity[1];
        SessionTaskEntity existingProjection = new SessionTaskEntity();
        existingProjection.setTaskStateJson("{\"originalTaskId\":\"task-original\"}");
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionTaskRepository.findByTaskId(anyString())).thenReturn(Optional.of(existingProjection));
        when(sessionManager.createSession(any())).thenReturn("session-ctx");
        when(sessionTaskRepository.save(any(SessionTaskEntity.class))).thenAnswer(invocation -> {
            savedSessionTask[0] = invocation.getArgument(0);
            return savedSessionTask[0];
        });

        DispatchTaskDTO result = service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "contextId", "bctx-1"
        ), "user-1", "tenant-1");

        assertEquals("bctx-1", result.getContextId());
        assertNotNull(savedSessionTask[0].getTaskStateJson());
        Map<String, Object> state = ProviderStateCodec.parseObject(savedSessionTask[0].getTaskStateJson());
        assertEquals(ProviderStateCodec.CURRENT_SCHEMA_VERSION, state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION));
        assertEquals("codex-worker", state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        assertEquals("bctx-1", state.get(ProviderStateCodec.FIELD_CONTEXT_ID));
        assertEquals("task-original", state.get("originalTaskId"));
    }

    @Test
    void createTaskDirect_forwardsImagesToProviderConfig() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "describe screenshot",
                "images", "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
        ), "user-1", "tenant-1");

        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "[{\"name\":\"screen.png\",\"data\":\"YmFzZTY0\",\"mime_type\":\"image/png\"}]"
                        .equals(event.getProviderConfigString("images"))
        ));
    }

    @Test
    void createTaskDirect_doesNotForwardCodexBizOptionsForPlainCodexWorker() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codexHomeKey", "tenant/world-sim/scenario-1/actor-1");
        params.put("developerInstructions", "Return valid JSON.");
        params.put("businessRuntimeContext", Map.of("task_scoped_token", "token-1"));
        params.put("outputSchema", outputSchema);
        params.put("codexConfig", codexConfig);
        params.put("sandboxMode", "workspace-write");
        params.put("approvalPolicy", "never");
        params.put("networkAccessEnabled", false);
        params.put("webSearchMode", "disabled");
        params.put("additionalDirectories", List.of("/home/sa/workspace/shared"));
        service.createTaskDirect(params, "user-1", "tenant-1");

        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-worker".equals(event.getProviderType())
                        && event.getProviderConfigString("codexHomeKey") == null
                        && event.getProviderConfigString("developerInstructions") == null
                        && event.getProviderConfigValue("businessRuntimeContext") == null
                        && event.getProviderConfigValue("outputSchema") == null
                        && event.getProviderConfigValue("codexConfig") == null
                        && event.getProviderConfigString("sandboxMode") == null
                        && event.getProviderConfigString("approvalPolicy") == null
                        && event.getProviderConfigValue("networkAccessEnabled") == null
                        && event.getProviderConfigString("webSearchMode") == null
                        && event.getProviderConfigValue("additionalDirectories") == null
        ));
    }

    @Test
    void createTaskDirect_forwardsCodexBizOptionsOnlyForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-biz-1");

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> businessRuntimeContext = Map.of("task_scoped_token", "token-1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codexHomeKey", "tenant/world-sim/scenario-1/actor-1");
        params.put("developerInstructions", "Return valid JSON.");
        params.put("businessRuntimeContext", businessRuntimeContext);
        params.put("outputSchema", outputSchema);
        params.put("codexConfig", codexConfig);
        params.put("sandboxMode", "workspace-write");
        params.put("approvalPolicy", "never");
        params.put("networkAccessEnabled", false);
        params.put("webSearchMode", "disabled");
        params.put("additionalDirectories", List.of("/home/sa/workspace/shared"));
        DispatchTaskDTO result = service.createTaskDirectForProvider(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-1".equals(event.getProviderConfigString("codexHomeKey"))
                        && "Return valid JSON.".equals(event.getProviderConfigString("developerInstructions"))
                        && businessRuntimeContext.equals(event.getProviderConfigValue("businessRuntimeContext"))
                        && outputSchema.equals(event.getProviderConfigValue("outputSchema"))
                        && codexConfig.equals(event.getProviderConfigValue("codexConfig"))
                        && "workspace-write".equals(event.getProviderConfigString("sandboxMode"))
                        && "never".equals(event.getProviderConfigString("approvalPolicy"))
                        && Boolean.FALSE.equals(event.getProviderConfigValue("networkAccessEnabled"))
                        && "disabled".equals(event.getProviderConfigString("webSearchMode"))
                        && List.of("/home/sa/workspace/shared").equals(event.getProviderConfigValue("additionalDirectories"))
        ));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-biz-1".equals(entity.getSessionId())
                        && "codex-biz-worker".equals(entity.getProviderType())
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
        {
            Map<String, Object> state = ProviderStateCodec.parseObject(entity.getProviderStateJson());
            return "session-biz-1".equals(entity.getId())
                    && "codex-biz-worker".equals(entity.getProviderType())
                    && "worker-1".equals(entity.getCurrentWorkerId())
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_HOME_KEY))
                    && "tenant/world-sim/scenario-1/actor-1".equals(
                    state.get(ProviderStateCodec.FIELD_CODEX_PRIVATE_ACCOUNT_ID));
        }
        ));
    }

    @Test
    void createTaskDirect_forwardsSnakeCaseCodexBizAliasesOnlyForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> outputSchema = Map.of("type", "object");
        Map<String, Object> codexConfig = Map.of("tool_output_token_limit", 4096);
        Map<String, Object> snakeCaseBusinessRuntimeContext = Map.of("task_scoped_token", "token-2");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("codex_home_key", "tenant/world-sim/scenario-1/actor-2");
        params.put("developer_instructions", "Return valid JSON.");
        params.put("business_runtime_context", snakeCaseBusinessRuntimeContext);
        params.put("output_schema", outputSchema);
        params.put("codex_config", codexConfig);
        params.put("sandbox_mode", "workspace-write");
        params.put("approval_policy", "never");
        params.put("network_access_enabled", "false");
        params.put("web_search_mode", "disabled");
        params.put("additional_directories", List.of("/home/sa/workspace/shared", " "));

        DispatchTaskDTO result = service.createTaskDirectForProvider(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-2".equals(event.getProviderConfigString("codexHomeKey"))
                        && "Return valid JSON.".equals(event.getProviderConfigString("developerInstructions"))
                        && snakeCaseBusinessRuntimeContext.equals(event.getProviderConfigValue("businessRuntimeContext"))
                        && outputSchema.equals(event.getProviderConfigValue("outputSchema"))
                        && codexConfig.equals(event.getProviderConfigValue("codexConfig"))
                        && "workspace-write".equals(event.getProviderConfigString("sandboxMode"))
                        && "never".equals(event.getProviderConfigString("approvalPolicy"))
                        && Boolean.FALSE.equals(event.getProviderConfigValue("networkAccessEnabled"))
                        && "disabled".equals(event.getProviderConfigString("webSearchMode"))
                        && List.of("/home/sa/workspace/shared").equals(event.getProviderConfigValue("additionalDirectories"))
        ));
    }

    @Test
    void createTaskDirect_acceptsPrivateAccountIdAliasForCodexBizProvider() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("providerType", "codex-biz-worker");
        params.put("workerId", "worker-1");
        params.put("prompt", "hello");
        params.put("privateAccountId", "tenant/world-sim/scenario-1/actor-3");

        DispatchTaskDTO result = service.createTaskDirectForProvider(
                CodexTaskService.CODEX_BIZ_PROVIDER_TYPE, params, "user-1", "tenant-1");

        assertEquals("codex-biz-worker", result.getProviderType());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "codex-biz-worker".equals(event.getProviderType())
                        && "tenant/world-sim/scenario-1/actor-3".equals(event.getProviderConfigString("codexHomeKey"))
        ));
    }

    @Test
    void createTask_rejectsLegacyCodexBizProviderWithoutScopedHomeKey() {
        CodexTaskCreateCommand form = new CodexTaskCreateCommand();
        form.setProviderType("codex-biz-worker");
        form.setWorkerId("worker-1");
        form.setPrompt("hello");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTask("user-1", "tenant-1", form));

        assertEquals("codex-biz-worker requires codexHomeKey or privateAccountId", error.getMessage());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_forwardSlashCwdUnchanged() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "cwd", "D:/tmp",
                "model", "gpt-5.4"
        ), "user-1", "tenant-1");

        assertEquals("D:/tmp", savedTask[0].getCwd());
    }

    @Test
    void createTaskDirect_doesNotTreatDirectoryIdAsLogicalAgentId() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-new");

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "directoryId", "dir-1"
        ), "user-1", "tenant-1");

        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-new".equals(entity.getSessionId())
                        && "codex-worker".equals(entity.getProviderType())
                        && entity.getAgentId() == null
                        && !"dir-1".equals(entity.getAgentId())
        ));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-new".equals(entity.getId())
                        && "codex-worker".equals(entity.getProviderType())
                        && entity.getAgentId() == null
                        && !"dir-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void createTask_usesAgentDefaultModelConfigAndDefaultModelWhenRequestOmitsBoth() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn("session-agent-default");

        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId("agent-codex-1");
        agent.setDefaultModelConfigId("cfg-codex");
        agent.setDefaultModel("gpt-5.4");
        when(codingAgentRepository.findByAgentId("agent-codex-1")).thenReturn(Optional.of(agent));

        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setBaseUrl("https://api.openai.com/v1");
        when(llmModelManager.getModelConfig("cfg-codex")).thenReturn(Optional.of(config));
        when(llmModelManager.getDecryptedApiKey("cfg-codex")).thenReturn("sk-codex");

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "agentId", "agent-codex-1"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.4", savedTask[0].getModel());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "gpt-5.4".equals(event.getModel())
                        && "sk-codex".equals(event.getApiKey())
                        && "https://api.openai.com/v1".equals(event.getProviderConfigString("baseUrl"))
        ));
    }

    @Test
    void createTaskDirect_rejectsLegacyWhitelistForGatedAliasBeforePersistence() {
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.4", "gpt-5.5"));
        when(llmModelManager.getModelConfig("cfg-legacy")).thenReturn(Optional.of(config));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-max",
                        "modelConfigId", "cfg-legacy"
                ), "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("explicit availableModels grant"));
        verify(llmModelManager).validateModelAccessForWorker("cfg-legacy", "worker-1");
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(WorkerTaskStartEvent.class));
    }

    @Test
    void createTaskDirect_allowsStableAliasGrantForKnownGpt56SolModel() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-max-alias");
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-max"));
        when(llmModelManager.getModelConfig("cfg-max-alias")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-sol:max",
                "modelConfigId", "cfg-max-alias"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.6-sol:max", savedTask[0].getModel());
        verify(llmModelManager).validateModelAccessForWorker("cfg-max-alias", "worker-1");
    }

    @Test
    void createTaskDirect_allowsKnownGpt56SolGrantForStableAlias() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-ultra-real-grant");
        LlmModelConfigDTO config = appServerModelConfig(List.of("gpt-5.6-sol:ultra"));
        when(llmModelManager.getModelConfig("cfg-ultra-real")).thenReturn(Optional.of(config));

        service.createTaskDirectForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-ultra",
                "modelConfigId", "cfg-ultra-real",
                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE
        ), "user-1", "tenant-1");

        assertEquals("codex-ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_allowsStableAliasGrantForCodexLatestSuffix() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-latest-ultra");
        LlmModelConfigDTO config = appServerModelConfig(List.of("codex-ultra"));
        when(llmModelManager.getModelConfig("cfg-latest-ultra")).thenReturn(Optional.of(config));

        service.createTaskDirectForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-latest:ultra",
                "modelConfigId", "cfg-latest-ultra",
                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE
        ), "user-1", "tenant-1");

        assertEquals("codex-latest:ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_allowsExactFutureGatedModelGrant() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-future-exact");
        LlmModelConfigDTO config = appServerModelConfig(List.of("gpt-5.7-sol:ultra"));
        when(llmModelManager.getModelConfig("cfg-future-exact")).thenReturn(Optional.of(config));

        service.createTaskDirectForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.7-sol:ultra",
                "modelConfigId", "cfg-future-exact",
                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.7-sol:ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_rejectsStableAliasGrantForFutureGatedModel() {
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-max"));
        when(llmModelManager.getModelConfig("cfg-future-alias")).thenReturn(Optional.of(config));

        assertThrows(IllegalArgumentException.class, () -> service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.7-sol:max",
                "modelConfigId", "cfg-future-alias"
        ), "user-1", "tenant-1"));

        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_allowsGatedModelWhenWhitelistIsUnrestricted() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-unrestricted");
        LlmModelConfigDTO config = appServerModelConfig(List.of());
        when(llmModelManager.getModelConfig("cfg-unrestricted")).thenReturn(Optional.of(config));

        service.createTaskDirectForProvider(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-ultra",
                "modelConfigId", "cfg-unrestricted",
                "providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE
        ), "user-1", "tenant-1");

        assertEquals("codex-ultra", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_rejectsKnownNonGatedModelOutsideRestrictedWhitelist() {
        LlmModelConfigDTO config = codexModelConfig(List.of("gpt-5.4"));
        when(llmModelManager.getModelConfig("cfg-non-gated")).thenReturn(Optional.of(config));

        assertThrows(IllegalArgumentException.class, () -> service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-sol:xhigh",
                "modelConfigId", "cfg-non-gated"
        ), "user-1", "tenant-1"));

        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_allowsLegacyTerraGrantOnlyForMedium() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-terra-medium");
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-terra"));
        when(llmModelManager.getModelConfig("cfg-terra-medium")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-terra:medium",
                "modelConfigId", "cfg-terra-medium"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.6-terra:medium", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_rejectsTerraHighWhenOnlyLegacyTerraMediumIsGranted() {
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-terra"));
        when(llmModelManager.getModelConfig("cfg-terra-medium")).thenReturn(Optional.of(config));

        assertThrows(IllegalArgumentException.class, () -> service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "codex-terra:high",
                "modelConfigId", "cfg-terra-medium"
        ), "user-1", "tenant-1"));

        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_rejectsKnownLunaUltraEvenWithoutRestrictedWhitelist() {
        assertThrows(IllegalArgumentException.class, () -> service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-luna:ultra"
        ), "user-1", "tenant-1"));

        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_allowsExactCanonicalTerraHighGrant() {
        CodexTaskEntity[] savedTask = stubSuccessfulTaskCreation("session-terra-high");
        LlmModelConfigDTO config = codexModelConfig(List.of("codex-terra:high"));
        when(llmModelManager.getModelConfig("cfg-terra-high")).thenReturn(Optional.of(config));

        service.createTaskDirect(Map.of(
                "workerId", "worker-1",
                "prompt", "hello",
                "model", "gpt-5.6-terra:high",
                "modelConfigId", "cfg-terra-high"
        ), "user-1", "tenant-1");

        assertEquals("gpt-5.6-terra:high", savedTask[0].getModel());
    }

    @Test
    void createTaskDirect_failsClosedWhenConfiguredModelDoesNotExist() {
        when(llmModelManager.getModelConfig("cfg-missing")).thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-ultra",
                        "modelConfigId", "cfg-missing"
                ), "user-1", "tenant-1"));

        assertEquals("LLM model config not found: cfg-missing", error.getMessage());
        verify(llmModelManager).validateModelAccessForWorker("cfg-missing", "worker-1");
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void createTaskDirect_failsClosedWhenModelManagerIsUnavailable() {
        ReflectionTestUtils.setField(service, "llmModelManager", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createTaskDirect(Map.of(
                        "workerId", "worker-1",
                        "prompt", "hello",
                        "model", "codex-ultra",
                        "modelConfigId", "cfg-ultra"
                ), "user-1", "tenant-1"));

        assertTrue(error.getMessage().contains("LLM model manager is unavailable"));
        verify(sessionManager, never()).createSession(any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void staleTurnCleanupUsesPersistedProviderTaskCapabilityAndLeavesTerminalTaskUntouched() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-cleanup", null);
        entity.setErrorMessage("terminal-error-kept");
        entity.setRuntimeAcceptanceState("TERMINAL");
        stubStaleCleanupTask(entity, "to-stale-cleaned");
        when(workerClient.staleTurnCleanup(eq("worker-native-task-stale-cleanup"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-native-task-stale-cleanup",
                        "operation_id", "to-stale-cleaned",
                        "status", "cleaned")));

        CodexTaskService.StaleTurnCleanupResult result = service.cleanupStaleTurn(
                "task-stale-cleanup", "user-1", null);

        assertEquals("task-stale-cleanup", result.taskId());
        assertEquals("to-stale-cleaned", result.operationId());
        assertEquals("cleaned", result.status());
        assertEquals("COMPLETED", entity.getStatus());
        assertEquals("terminal-error-kept", entity.getErrorMessage());
        assertEquals("TERMINAL", entity.getRuntimeAcceptanceState());
        ArgumentCaptor<TerminationOperationService.CreateCommand> command =
                ArgumentCaptor.forClass(TerminationOperationService.CreateCommand.class);
        verify(terminationOperationService).accept(command.capture());
        assertEquals("worker-native-task-stale-cleanup", command.getValue().providerTaskId());
        assertEquals("STALE_TURN_INTERRUPT", command.getValue().kind());
        assertEquals("UPSTREAM_USER", command.getValue().origin());
        assertNull(command.getValue().expectedPid());
        assertNull(command.getValue().expectedProcessIdentity());
        ArgumentCaptor<TerminationOperationCapability> capability =
                ArgumentCaptor.forClass(TerminationOperationCapability.class);
        verify(workerClient).staleTurnCleanup(eq("worker-native-task-stale-cleanup"), capability.capture());
        String payload = new String(Base64.getUrlDecoder().decode(capability.getValue().encodedOperation()),
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"task_id\":\"worker-native-task-stale-cleanup\""));
        assertFalse(payload.contains("\"task_id\":\"task-stale-cleanup\""));
        assertFalse(payload.contains("expected_pid"));
        assertFalse(payload.contains("expected_process_identity"));
        verify(terminationOperationService).markDispatchStarted("to-stale-cleaned");
        verify(terminationOperationService).markObservedTerminal("to-stale-cleaned", "COMPLETED");
    }

    @Test
    void appServerAbortRetrySupersedesOldOperationAndPersistsExactInterruptedOutcome() {
        CodexTaskEntity entity = pendingAppServerRetryTask("task-retry", "tenant-1");
        stubAppServerAbortRetryTask(entity, "to-retry");
        when(workerClient.retryAbortTask(eq("worker-native-task-retry"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-native-task-retry",
                        "retry_status", "observed_terminal",
                        "provider_state", "interrupted")));

        CodexTaskService.AppServerAbortRetryResult result = service.retryAppServerAbort(
                "task-retry", "user-1", "tenant-1");

        assertEquals("ABORTED", entity.getStatus());
        assertEquals("TERMINAL", entity.getRuntimeAcceptanceState());
        assertEquals("interrupted", result.providerState());
        assertEquals("ABORTED", result.status());
        verify(terminationOperationService).supersedeActiveOperationsForTask(
                "task-retry", "TERMINATION_OPERATION_SUPERSEDED_BY_RETRY");
        ArgumentCaptor<TerminationOperationService.CreateCommand> command =
                ArgumentCaptor.forClass(TerminationOperationService.CreateCommand.class);
        verify(terminationOperationService).accept(command.capture());
        assertEquals("REMOTE_CANCEL", command.getValue().kind());
        assertEquals("USER_CANCEL_RETRY", command.getValue().reasonCode());
        assertEquals("worker-native-task-retry", command.getValue().providerTaskId());
        verify(terminationOperationService).markDispatchStarted("to-retry");
        verify(terminationOperationService).markObservedTerminal("to-retry", "ABORTED");
    }

    @Test
    void sdkAbortRetrySupersedesOldOperationAndDispatchesFreshCapability() {
        CodexTaskEntity entity = createTask(
                "task-sdk-retry", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.of(2026, 7, 22, 9, 30));
        entity.setTenantId("tenant-1");
        entity.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        entity.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        entity.setWorkerTaskId("worker-native-sdk-retry");
        entity.setCodexThreadId("thread-sdk-retry");
        when(taskRepository.findByTaskId("task-sdk-retry")).thenReturn(Optional.of(entity));
        when(taskRepository.findByTaskIdForUpdate("task-sdk-retry")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://worker.example").authToken("worker-token").build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-native-sdk-retry"))
                .thenReturn(Mono.just(Map.of("task_id", "worker-native-sdk-retry", "status", "running")));
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to-sdk-retry");
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        when(workerClient.abortTask(eq("worker-native-sdk-retry"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-native-sdk-retry",
                        "status", "cancel_requested")));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.SdkAbortRetryResult result = service.retrySdkAbort(
                "task-sdk-retry", "user-1", "tenant-1");

        assertEquals("to-sdk-retry", result.operationId());
        assertEquals("cancel_requested", result.providerState());
        assertEquals("CANCEL_REQUESTED", result.status());
        verify(terminationOperationService).supersedeActiveOperationsForTask(
                "task-sdk-retry", "TERMINATION_OPERATION_SUPERSEDED_BY_RETRY");
        ArgumentCaptor<TerminationOperationService.CreateCommand> command =
                ArgumentCaptor.forClass(TerminationOperationService.CreateCommand.class);
        verify(terminationOperationService).accept(command.capture());
        assertEquals("USER_CANCEL_RETRY", command.getValue().reasonCode());
        verify(terminationOperationService).markDispatchStarted("to-sdk-retry");
        verify(terminationOperationService).markCancelRequested("to-sdk-retry");
        verify(workerClient, never()).retryAbortTask(anyString(), any());
    }

    @Test
    void appServerAbortRetryClosesDefinitiveWorkerConflictWithoutChangingTaskState() {
        CodexTaskEntity entity = pendingAppServerRetryTask("task-retry-conflict", "tenant-1");
        stubAppServerAbortRetryTask(entity, "to-retry-conflict");
        when(workerClient.retryAbortTask(eq("worker-native-task-retry-conflict"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(new CodexWorkerClient.WorkerQueryRejectedException(
                        409, "TERMINATION_RETRY_BINDING_MISMATCH")));

        CodexTaskService.AppServerAbortRetryException error = assertThrows(
                CodexTaskService.AppServerAbortRetryException.class,
                () -> service.retryAppServerAbort(
                        "task-retry-conflict", "user-1", "tenant-1"));

        assertEquals("TERMINATION_RETRY_BINDING_MISMATCH", error.getSafeCode());
        assertFalse(error.isRetryable());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService).markRejected(
                "to-retry-conflict", "TERMINATION_RETRY_BINDING_MISMATCH");
        verify(terminationOperationService, never()).markFailedUnconfirmed(anyString(), anyString());
    }

    @Test
    void appServerAbortRetryDoesNotMisattributeATerminalRaceToTheNewOperation() {
        CodexTaskEntity reserved = pendingAppServerRetryTask("task-retry-race", "tenant-1");
        CodexTaskEntity terminal = pendingAppServerRetryTask("task-retry-race", "tenant-1");
        terminal.setStatus("FAILED");
        terminal.setRuntimeAcceptanceState("TERMINAL");
        stubAppServerAbortRetryTask(reserved, terminal, "to-retry-race");

        CodexTaskService.AppServerAbortRetryResult result = service.retryAppServerAbort(
                "task-retry-race", "user-1", "tenant-1");

        assertEquals("failed", result.providerState());
        assertEquals("FAILED", result.status());
        verify(terminationOperationService).markRejected(
                "to-retry-race", "TERMINATION_RETRY_NOT_DISPATCHED_TASK_TERMINAL");
        verify(terminationOperationService, never()).markObservedTerminal(
                eq("to-retry-race"), anyString());
        verify(workerClient, never()).retryAbortTask(anyString(), any());
    }

    @Test
    void staleTurnCleanupRejectsTenantlessCallerForTenantBoundTaskBeforeAuditOrWorkerLookup() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-tenant", "tenant-1");
        when(taskRepository.findByTaskIdForUpdate("task-stale-tenant")).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-tenant", "user-1", null));

        assertEquals("STALE_TURN_CLEANUP_UNAVAILABLE", error.getSafeCode());
        assertFalse(error.isRetryable());
        verify(terminationOperationService, never()).accept(any());
        verifyNoInteractions(workerClient, clientFactory, runtimeRegistryService);
    }

    @Test
    void staleTurnCleanupRejectsTenantMismatchBeforeAuditOrWorkerLookup() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-tenant-mismatch", "tenant-1");
        when(taskRepository.findByTaskIdForUpdate("task-stale-tenant-mismatch"))
                .thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-tenant-mismatch", "user-1", "tenant-2"));

        assertEquals("STALE_TURN_CLEANUP_UNAVAILABLE", error.getSafeCode());
        assertFalse(error.isRetryable());
        verify(terminationOperationService, never()).accept(any());
        verifyNoInteractions(workerClient, clientFactory, runtimeRegistryService);
    }

    @Test
    void staleTurnCleanupTreatsInvalidWorkerReceiptAsRetryableUnconfirmedAndClosesOperation() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-invalid-receipt", "tenant-1");
        stubStaleCleanupTask(entity, "to-stale-invalid-receipt");
        when(workerClient.staleTurnCleanup(eq("worker-native-task-stale-invalid-receipt"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "worker-native-task-stale-invalid-receipt",
                        "operation_id", "other-operation",
                        "status", "cleaned")));

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-invalid-receipt", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_UNCONFIRMED", error.getSafeCode());
        assertTrue(error.isRetryable());
        verify(terminationOperationService).markFailedUnconfirmed(
                "to-stale-invalid-receipt", "STALE_TURN_CLEANUP_UNCONFIRMED");
        verify(terminationOperationService, never()).markObservedTerminal(anyString(), anyString());
    }

    @Test
    void staleTurnCleanupTreatsWorkerConflictAsDefinitiveRejectedOperation() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-conflict", "tenant-1");
        stubStaleCleanupTask(entity, "to-stale-conflict");
        when(workerClient.staleTurnCleanup(eq("worker-native-task-stale-conflict"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(new CodexWorkerClient.WorkerQueryRejectedException(
                        409, "STALE_TURN_CLEANUP_TURN_NOT_RUNNING")));

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-conflict", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_TURN_NOT_RUNNING", error.getSafeCode());
        assertFalse(error.isRetryable());
        verify(terminationOperationService).markRejected(
                "to-stale-conflict", "STALE_TURN_CLEANUP_TURN_NOT_RUNNING");
        verify(terminationOperationService, never()).markFailedUnconfirmed(anyString(), anyString());
    }

    @Test
    void staleTurnCleanupDoesNotExposeUntrustedWorkerConflictSuffixes() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-secret-conflict", "tenant-1");
        stubStaleCleanupTask(entity, "to-stale-secret-conflict");
        when(workerClient.staleTurnCleanup(eq("worker-native-task-stale-secret-conflict"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(new CodexWorkerClient.WorkerQueryRejectedException(
                        409, "STALE_TURN_CLEANUP_TURN_NOT_RUNNING:runtime-secret")));

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-secret-conflict", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_REJECTED", error.getSafeCode());
        assertFalse(error.getSafeCode().contains("runtime-secret"));
        verify(terminationOperationService).markRejected(
                "to-stale-secret-conflict", "STALE_TURN_CLEANUP_REJECTED");
    }

    @Test
    void staleTurnCleanupRejectsAffinityChangeBeforeNativeWorkerCall() {
        CodexTaskEntity reserved = terminalAppServerTask("task-stale-affinity", "tenant-1");
        CodexTaskEntity changed = terminalAppServerTask("task-stale-affinity", "tenant-1");
        changed.setRuntimeInstanceId("instance-b");
        stubStaleCleanupTask(reserved, changed, "to-stale-affinity");

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-affinity", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_AFFINITY_CHANGED", error.getSafeCode());
        assertFalse(error.isRetryable());
        verify(terminationOperationService).markRejected(
                "to-stale-affinity", "STALE_TURN_CLEANUP_AFFINITY_CHANGED");
        verifyNoInteractions(workerClient, clientFactory, runtimeRegistryService);
    }

    @Test
    void staleTurnCleanupRejectsCodexThreadRebindingBeforeNativeWorkerCall() {
        CodexTaskEntity reserved = terminalAppServerTask("task-stale-thread-rebinding", "tenant-1");
        CodexTaskEntity changed = terminalAppServerTask("task-stale-thread-rebinding", "tenant-1");
        changed.setCodexThreadId("thread-rebound");
        stubStaleCleanupTask(reserved, changed, "to-stale-thread-rebinding");

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-thread-rebinding", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_AFFINITY_CHANGED", error.getSafeCode());
        verify(terminationOperationService).markRejected(
                "to-stale-thread-rebinding", "STALE_TURN_CLEANUP_AFFINITY_CHANGED");
        verifyNoInteractions(workerClient, clientFactory, runtimeRegistryService);
    }

    @Test
    void staleTurnCleanupTreatsRuntimeFailureAsRetryableUnconfirmedAndClosesOperation() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-runtime", "tenant-1");
        stubStaleCleanupTask(entity, "to-stale-runtime");
        when(workerClient.staleTurnCleanup(eq("worker-native-task-stale-runtime"),
                any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(new IllegalStateException("runtime disconnected")));

        CodexTaskService.StaleTurnCleanupException error = assertThrows(
                CodexTaskService.StaleTurnCleanupException.class,
                () -> service.cleanupStaleTurn("task-stale-runtime", "user-1", "tenant-1"));

        assertEquals("STALE_TURN_CLEANUP_UNCONFIRMED", error.getSafeCode());
        assertTrue(error.isRetryable());
        verify(terminationOperationService).markFailedUnconfirmed(
                "to-stale-runtime", "STALE_TURN_CLEANUP_UNCONFIRMED");
    }

    @Test
    void staleTurnCleanupEligibilitySeparatesProviderRuntimeTerminalAndBindingGates() {
        CodexTaskEntity entity = terminalAppServerTask("task-stale-eligibility", "tenant-1");
        when(taskRepository.findByTaskId("task-stale-eligibility")).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        assertTrue(service.getStaleTurnCleanupEligibility(
                "task-stale-eligibility", "user-1", "tenant-1").eligible());

        entity.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        assertEquals("STALE_TURN_CLEANUP_PROVIDER_UNSUPPORTED", service.getStaleTurnCleanupEligibility(
                "task-stale-eligibility", "user-1", "tenant-1").reasonCode());
        entity.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        entity.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        assertEquals("STALE_TURN_CLEANUP_RUNTIME_UNSUPPORTED", service.getStaleTurnCleanupEligibility(
                "task-stale-eligibility", "user-1", "tenant-1").reasonCode());
        entity.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        entity.setStatus("RUNNING");
        assertEquals("STALE_TURN_CLEANUP_TASK_NOT_TERMINAL", service.getStaleTurnCleanupEligibility(
                "task-stale-eligibility", "user-1", "tenant-1").reasonCode());
        entity.setStatus("COMPLETED");
        entity.setCodexThreadId(null);
        assertEquals("STALE_TURN_CLEANUP_BINDING_INCOMPLETE", service.getStaleTurnCleanupEligibility(
                "task-stale-eligibility", "user-1", "tenant-1").reasonCode());
    }

    @Test
    void abortTaskFailsClosedWhenAuditStoreIsUnavailable() {
        CodexTaskEntity entity = createTask(
                "task-abort", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 4, 2, 10, 0)
        );
        entity.setWorkerTaskId("worker-task-1");

        when(taskRepository.findByTaskId("task-abort")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.abortTask("task-abort"));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", entity.getErrorMessage());
        assertEquals("TERMINATION_AUDIT_UNAVAILABLE", error.getSafeCode());
        verify(streamRelay, never()).abortAndReconcileTask(any());
        verifyNoInteractions(workerClient);
    }

    @Test
    void abortTaskReportsExistingOperationInsteadOfReturningSuccess() {
        CodexTaskEntity entity = createTask(
                "task-abort-pending", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 22, 9, 0));
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskId("task-abort-pending")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.hasActiveOperationForTask("task-abort-pending")).thenReturn(true);
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.abortTask("task-abort-pending"));

        assertEquals("TERMINATION_OPERATION_PENDING", error.getSafeCode());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        assertEquals("TERMINATION_OPERATION_PENDING", entity.getErrorMessage());
        verify(terminationOperationService, never()).accept(any());
        verifyNoInteractions(workerClient);
    }

    @Test
    void abortTaskSkipsAlreadyCompletedTask() {
        CodexTaskEntity entity = createTask(
                "task-abort", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 4, 2, 10, 0));
        entity.setStatus("COMPLETED");
        when(taskRepository.findByTaskId("task-abort")).thenReturn(Optional.of(entity));

        service.abortTask("task-abort");

        verify(streamRelay, never()).abortAndReconcileTask(any());
    }

    @Test
    void abortTaskSkipsAlreadyFailedTask() {
        CodexTaskEntity entity = createTask(
                "task-abort", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 4, 2, 10, 0));
        entity.setStatus("FAILED");
        when(taskRepository.findByTaskId("task-abort")).thenReturn(Optional.of(entity));

        service.abortTask("task-abort");

        verify(streamRelay, never()).abortAndReconcileTask(any());
    }

    @Test
    void abortTaskDispatchesSignedOperationButKeepsTaskNonterminalAfterAck() {
        CodexTaskEntity entity = createTask(
                "task-abort", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 4, 2, 10, 0));
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskId("task-abort")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TerminationOperationService.CreateCommand[] accepted = new TerminationOperationService.CreateCommand[1];
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            accepted[0] = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_test");
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
            return operation;
        });
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://worker.example").authToken("worker-token").build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        when(workerClient.abortTask(eq("worker-task-1"), any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of("status", "CANCEL_REQUESTED")));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        service.abortTask("task-abort");

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        assertTrue(accepted[0].correlationId().startsWith("remote-cancel:"));
        verify(terminationOperationService).markDispatchStarted("to_test");
        verify(terminationOperationService).markCancelRequested("to_test");
        ArgumentCaptor<TerminationOperationCapability> capability =
                ArgumentCaptor.forClass(TerminationOperationCapability.class);
        verify(workerClient).abortTask(eq("worker-task-1"), capability.capture());
        assertNotNull(capability.getValue().signature());
        String payload = new String(Base64.getUrlDecoder().decode(capability.getValue().encodedOperation()),
                StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"correlation_id\":\"remote-cancel:"));
        verify(streamRelay, never()).abortAndReconcileTask(any());
    }

    @Test
    void abortTaskKeepsCancelRequestedWhenWorkerNoLongerHasNativeTask() {
        CodexTaskEntity entity = createTask(
                "task-missing-native", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 17, 16, 0));
        entity.setWorkerTaskId("worker-task-missing");
        when(taskRepository.findByTaskId("task-missing-native")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_missing_native");
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://worker.example").authToken("worker-token").build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        WebClientResponseException notFound = WebClientResponseException.create(
                404, "Not Found", null,
                "{\"error\":\"TASK_NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(workerClient.abortTask(eq("worker-task-missing"), any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(notFound));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.abortTask("task-missing-native"));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        assertEquals("TERMINATION_DISPATCH_UNCONFIRMED", entity.getErrorMessage());
        assertEquals("TERMINATION_DISPATCH_UNCONFIRMED", error.getSafeCode());
        verify(terminationOperationService).markUnconfirmed(eq("to_missing_native"), anyString());
        verify(terminationOperationService, never()).markRejected(anyString(), anyString());
        verify(terminationOperationService, never()).markCancelRequested(anyString());
    }

    @Test
    void abortTaskRestoresRunningWhenWorkerDefinitivelyRejectsAuthorization() {
        CodexTaskEntity entity = createTask(
                "task-rejected", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 17, 16, 10));
        entity.setWorkerTaskId("worker-task-rejected");
        when(taskRepository.findByTaskId("task-rejected")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_rejected");
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://worker.example").authToken("worker-token").build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        WebClientResponseException forbidden = WebClientResponseException.create(
                403, "Forbidden", null,
                "{\"error\":\"TERMINATION_CAPABILITY_REJECTED\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        when(workerClient.abortTask(eq("worker-task-rejected"), any(TerminationOperationCapability.class)))
                .thenReturn(Mono.error(forbidden));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.TerminationDispatchException error = assertThrows(
                CodexTaskService.TerminationDispatchException.class,
                () -> service.abortTask("task-rejected"));

        assertEquals("RUNNING", entity.getStatus());
        assertEquals("TERMINATION_REJECTED", entity.getErrorMessage());
        assertEquals("TERMINATION_REJECTED", error.getSafeCode());
        verify(terminationOperationService).markRejected(eq("to_rejected"), anyString());
        verify(terminationOperationService, never()).markUnconfirmed(anyString(), anyString());
        verify(terminationOperationService, never()).markCancelRequested(anyString());
    }

    @Test
    void abortTaskAcceptsAppServerAuthoritativeAbortedResponse() {
        CodexTaskEntity entity = createTask(
                "task-abort-terminal", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 16, 22, 0));
        entity.setWorkerTaskId("worker-task-terminal");
        when(taskRepository.findByTaskId("task-abort-terminal")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_terminal");
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        when(workerManagementFacade.getCodexConfig("worker-1")).thenReturn(CodexConfig.builder()
                .baseUrl("http://worker.example").authToken("worker-token").build());
        when(clientFactory.getOrCreate("worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        when(workerClient.abortTask(eq("worker-task-terminal"), any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "status", "terminal",
                        "outcome", "aborted",
                        "abort_status", "aborted")));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        service.abortTask("task-abort-terminal");

        verify(terminationOperationService).markDispatchStarted("to_terminal");
        verify(terminationOperationService).markCancelRequested("to_terminal");
        verify(terminationOperationService, never()).markUnconfirmed(anyString(), anyString());
        assertNull(entity.getErrorMessage());
    }

    @Test
    void manualPidKillSupersedesPendingOperationAndSignsFreshProcessIdentity() {
        CodexTaskEntity entity = createTask(
                "task-manual-kill", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 16, 10, 0));
        entity.setTenantId("tenant-1");
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskIdForUpdate("task-manual-kill")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.hasActiveOperationForTask("task-manual-kill")).thenReturn(true);
        TerminationOperationService.CreateCommand[] accepted = new TerminationOperationService.CreateCommand[1];
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            accepted[0] = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_manual_pid");
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
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.ManualPidKillRequest request = service.prepareManualPidKill(
                "task-manual-kill", "worker-1", "user-1", "TENANT_ADMIN_MANUAL", "tenant-1", true,
                321, "codex-cli:321:2026-07-16T03:40:13.655Z", "worker-token");

        assertEquals("codex-cli:321:2026-07-16T03:40:13.655Z", accepted[0].expectedProcessIdentity());
        assertTrue(accepted[0].authorizationDecisionId().startsWith("authz-v1:tenant_admin_manual:"));
        assertNotEquals(accepted[0].authorizationDecisionId(), accepted[0].correlationId());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        String encoded = request.capability().encodedOperation();
        String payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"expected_process_identity\":\"codex-cli:321:2026-07-16T03:40:13.655Z\""));
        verify(terminationOperationService).supersedeActiveOperationsForTask(
                "task-manual-kill", "TERMINATION_OPERATION_SUPERSEDED_BY_MANUAL_PID_RETRY");
        verify(terminationOperationService).markDispatchStarted("to_manual_pid");
    }

    @Test
    void manualPidKillAllowsSuperAdminForPlatformScopedTask() {
        CodexTaskEntity entity = createTask(
                "task-platform-manual-kill", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 22, 9, 0));
        entity.setTenantId(null);
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskIdForUpdate("task-platform-manual-kill")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.hasActiveOperationForTask("task-platform-manual-kill")).thenReturn(false);
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId("to_platform_manual_pid");
            operation.setSchemaVersion(1);
            operation.setProviderTaskId(command.providerTaskId());
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
        });
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        CodexTaskService.ManualPidKillRequest request = service.prepareManualPidKill(
                "task-platform-manual-kill", "worker-1", "super-admin-1", "SUPER_ADMIN_MANUAL", null, true,
                321, "codex-cli:321:2026-07-22T01:00:00.000Z", "worker-token");

        assertEquals("to_platform_manual_pid", request.operationId());
        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService).accept(any());
    }

    @Test
    void manualPidKillRejectsTenantAdministratorForPlatformScopedTask() {
        CodexTaskEntity entity = createTask(
                "task-platform-tenant-admin", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 22, 9, 0));
        entity.setTenantId(null);
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskIdForUpdate("task-platform-tenant-admin")).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill("task-platform-tenant-admin", "worker-1", "tenant-admin-1",
                        "TENANT_ADMIN_MANUAL", null, true, 321,
                        "codex-cli:321:2026-07-22T01:00:00.000Z", "worker-token"));

        assertEquals("TERMINATION_TASK_ACCESS_DENIED", error.getMessage());
        verify(terminationOperationService, never()).accept(any());
    }

    @Test
    void manualPidKillRejectsOrdinaryTaskOwnerDespiteFreshProcessBinding() {
        CodexTaskEntity entity = createTask(
                "task-owner-cannot-kill", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 16, 10, 0));
        entity.setTenantId("tenant-1");
        entity.setWorkerTaskId("worker-task-1");
        when(taskRepository.findByTaskIdForUpdate("task-owner-cannot-kill")).thenReturn(Optional.of(entity));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill("task-owner-cannot-kill", "worker-1", "user-1",
                        "USER_MANUAL", "tenant-1", false, 321,
                        "codex-cli:321:2026-07-16T03:40:13.655Z", "worker-token"));

        assertEquals("TERMINATION_TASK_ACCESS_DENIED", error.getMessage());
        verify(terminationOperationService, never()).accept(any());
    }

    @Test
    void manualPidKillRejectsMissingOrMismatchedProcessIdentityBeforeDispatch() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill("task-1", "worker-1", "user-1", "TENANT_ADMIN_MANUAL",
                        "tenant-1", true, 321, "", "worker-token"));
        assertEquals("TERMINATION_PROCESS_IDENTITY_REQUIRED", missing.getMessage());

        CodexTaskEntity appServerTask = createTask(
                "task-app-server", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 16, 10, 0));
        appServerTask.setTenantId("tenant-1");
        appServerTask.setWorkerTaskId("worker-task-1");
        appServerTask.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        appServerTask.setRuntimeInstanceId("instance-1");
        when(taskRepository.findByTaskIdForUpdate("task-app-server")).thenReturn(Optional.of(appServerTask));
        when(terminationOperationService.hasActiveOperationForTask("task-app-server")).thenReturn(false);
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                () -> service.prepareManualPidKill("task-app-server", "worker-1", "user-1", "TENANT_ADMIN_MANUAL",
                        "tenant-1", true, 321, "app-server-instance:other-instance", "worker-token"));
        assertEquals("TERMINATION_PROCESS_IDENTITY_MISMATCH", mismatch.getMessage());
        verify(terminationOperationService, never()).accept(any());
    }

    @Test
    void manualPidKillResultRequiresObservedExitCorrelatedToOperationTaskAndWorker() {
        CodexTaskEntity entity = createTask(
                "task-manual-result", "session-1", "worker-1", "dir-1", "CANCEL_REQUESTED",
                LocalDateTime.of(2026, 7, 16, 10, 0));
        when(taskRepository.findByTaskIdForUpdate("task-manual-result")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        CodexTaskService.ManualPidKillRequest request = new CodexTaskService.ManualPidKillRequest(
                "to-manual-result", "task-manual-result", "RUNNING", null, null);

        // Regression: an ABORTED lifecycle label without observed process exit
        // must remain pending rather than terminalizing the task.
        service.recordManualPidKillResult(request, Map.of(
                "lifecycle_status", "ABORTED",
                "observed_exit", false));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService).markAwaitingObservation(
                "to-manual-result", "TERMINATION_UNCONFIRMED");
        verify(terminationOperationService, never()).markObservedTerminal(anyString(), anyString());

        // A true flag with no signed-operation evidence is insufficient.
        service.recordManualPidKillResult(request, Map.of(
                "lifecycle_status", "CANCEL_REQUESTED",
                "observed_exit", true));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService, times(2)).markAwaitingObservation(
                "to-manual-result", "TERMINATION_UNCONFIRMED");

        // A result for another task cannot settle this task's operation.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "other-task",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", "worker-1")));

        // A result for a different signed operation cannot be replayed here.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-manual-result",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("other-operation", "worker-1")));

        // Nor can a valid operation from another physical Worker terminalize it.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-manual-result",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", "other-worker")));

        // Bindings are exact rather than case-insensitive string comparisons.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-manual-result",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("TO-MANUAL-RESULT", "worker-1")));

        // A top-level task binding is not a substitute for the signed
        // operation's own task binding.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-manual-result",
                "observed_exit", true,
                "termination_operation", Map.of(
                        "operation_id", "to-manual-result",
                        "worker_id", "worker-1",
                        "kind", "MANUAL_PID_KILL",
                        "origin", "ADMIN_MANUAL",
                        "status", "OBSERVED_EXIT",
                        "observed_exit", true,
                        "observed_at", "2026-07-16T03:40:13.655Z")));

        assertEquals("CANCEL_REQUESTED", entity.getStatus());
        verify(terminationOperationService, times(7)).markAwaitingObservation(
                "to-manual-result", "TERMINATION_UNCONFIRMED");
        verify(terminationOperationService, never()).markObservedTerminal(anyString(), anyString());

        // The SDK Worker response carries an explicit observed operation
        // correlated to the reserved task and Worker.
        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-manual-result",
                "observed_exit", true,
                "termination_operation", observedManualPidOperation("to-manual-result", "worker-1")));

        assertEquals("ABORTED", entity.getStatus());
        verify(terminationOperationService).markObservedTerminal("to-manual-result", "ABORTED");
    }

    private Map<String, Object> observedManualPidOperation(String operationId, String workerId) {
        return Map.of(
                "operation_id", operationId,
                "task_id", "task-manual-result",
                "worker_id", workerId,
                "kind", "MANUAL_PID_KILL",
                "origin", "ADMIN_MANUAL",
                "status", "OBSERVED_EXIT",
                "observed_exit", true,
                "observed_at", "2026-07-16T03:40:13.655Z");
    }

    @Test
    void manualPidKillResultAcceptsAppServerObservedExitTimestampWhenBindingsMatch() {
        CodexTaskEntity entity = createTask(
                "task-app-server-manual-result", "session-1", "worker-1", "dir-1", "CANCEL_REQUESTED",
                LocalDateTime.of(2026, 7, 16, 10, 0));
        entity.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        when(taskRepository.findByTaskIdForUpdate("task-app-server-manual-result")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        CodexTaskService.ManualPidKillRequest request = new CodexTaskService.ManualPidKillRequest(
                "to-app-server-manual-result", "task-app-server-manual-result", "RUNNING", null, null);

        service.recordManualPidKillResult(request, Map.of(
                "task_id", "task-app-server-manual-result",
                "observed_exit", true,
                "termination_operation", Map.of(
                        "operation_id", "to-app-server-manual-result",
                        "task_id", "task-app-server-manual-result",
                        "worker_id", "worker-1",
                        "kind", "MANUAL_PID_KILL",
                        "origin", "ADMIN_MANUAL",
                        "status", "OBSERVED_EXIT",
                        "observed_exit_at", "2026-07-16T03:40:13.655Z")));

        assertEquals("ABORTED", entity.getStatus());
        verify(terminationOperationService).markObservedTerminal("to-app-server-manual-result", "ABORTED");
    }

    @Test
    void resumeTask_reusesLogicalAgentIdFromExistingSessionWhenRequestOmitsAgentId() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        SessionEntity existingSession = new SessionEntity();
        existingSession.setId("session-1");
        existingSession.setUserId("user-1");
        existingSession.setAgentId("agent-codex-1");
        existingSession.setProviderType("codex-worker");
        existingSession.setProviderStateJson("{\"schemaVersion\":1,\"providerType\":\"codex-worker\",\"codexThreadId\":\"thread-1\"}");
        existingSession.setLatestTaskId("task-history");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(existingSession));
        stubHistoricalResumeTask(
                "task-history", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue"
        ));

        assertEquals("agent-codex-1", result.getAgentId());
        assertEquals("thread-1", result.getCodexThreadId());
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "session-1".equals(entity.getSessionId())
                        && "agent-codex-1".equals(entity.getAgentId())
        ));
    }

    @Test
    void rewindTask_truncatesPlatformSessionAndClearsCodexThread() {
        CodexTaskEntity entity = createTask(
                "task-rewind", "session-1", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 5, 10, 10, 0)
        );
        entity.setCodexThreadId("thread-1");
        when(taskRepository.findByTaskId("task-rewind")).thenReturn(Optional.of(entity));
        when(sessionManager.getAllMessages("session-1")).thenReturn(List.of(
                Message.user("session-1", "first prompt"),
                Message.assistant("session-1", "first answer"),
                Message.user("session-1", "second prompt")
        ));
        when(sessionManager.truncateMessagesFromTurn("session-1", 2)).thenReturn(2);
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\",\"other\":\"keep\"}");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        Object result = service.rewindTask("task-rewind", "user-1", Map.of(
                "mode", "conversation_fork",
                "turnIndex", 2
        ));

        Map<?, ?> payload = assertInstanceOf(Map.class, result);
        assertEquals("rewound", payload.get("status"));
        assertEquals("second prompt", payload.get("userPrompt"));
        assertEquals(2, payload.get("turnIndex"));
        assertNull(payload.get("codexThreadId"));
        verify(sessionManager).truncateMessagesFromTurn("session-1", 2);
        verify(sessionEntityRepository).save(argThat((SessionEntity saved) ->
                saved.getProviderStateJson() != null
                        && !saved.getProviderStateJson().contains("codexThreadId")
                        && saved.getProviderStateJson().contains("other")
                        && saved.getProviderStateJson().contains("\"schemaVersion\":1")
                        && saved.getProviderStateJson().contains("\"providerType\":\"codex-worker\"")
        ));
    }

    @Test
    void rewindTask_rejectsCodexFileRewind() {
        CodexTaskEntity entity = createTask(
                "task-rewind-file", "session-1", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 5, 10, 10, 0)
        );
        when(taskRepository.findByTaskId("task-rewind-file")).thenReturn(Optional.of(entity));

        assertThrows(UnsupportedOperationException.class, () ->
                service.rewindTask("task-rewind-file", "user-1", Map.of("mode", "file_rewind")));
    }

    @Test
    void resumeTask_startsNewCodexThreadWhenSessionThreadWasClearedByRewind() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString())).thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        SessionEntity existingSession = new SessionEntity();
        existingSession.setId("session-1");
        existingSession.setUserId("user-1");
        existingSession.setAgentId("agent-codex-1");
        existingSession.setProviderType("codex-worker");
        existingSession.setProviderStateJson(null);
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(existingSession));

        DispatchTaskDTO result = service.resumeTask("user-1", "tenant-1", Map.of(
                "workerId", "worker-1",
                "sessionId", "session-1",
                "prompt", "continue after rewind"
        ));

        assertNotNull(result.getTaskId());
        assertNull(result.getCodexThreadId());
        verify(eventPublisher).publishEvent(argThat((WorkerTaskStartEvent event) ->
                "session-1".equals(event.getSessionId())
                        && "continue after rewind".equals(event.getPrompt())
                        && event.getProviderConfigString("codexThreadId") == null
        ));
    }

    @Test
    void getTaskById_recoversLogicalAgentIdFromUnifiedSessionStore() {
        CodexTaskEntity entity = createTask(
                "task-1", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 10, 0)
        );
        entity.setResolvedAgentId(null);
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(entity));

        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-1");
        sessionTask.setSessionId("session-1");
        sessionTask.setAgentId("agent-codex-1");
        when(sessionTaskRepository.findByTaskId("task-1")).thenReturn(Optional.of(sessionTask));

        DispatchTaskDTO dto = service.getTaskById("task-1").orElseThrow();

        assertEquals("agent-codex-1", dto.getAgentId());
        assertEquals("codex-worker", dto.getProviderType());
    }

    @Test
    void completeTask_publishesTaskStatusChangeEvent() {
        CodexTaskEntity entity = createTask(
                "task-1", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 10, 0)
        );
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.completeTask("task-1", "worker-task-1", "thread-1", "done",
                null, null, null, null, null, "gpt-5.4");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-1".equals(event.getTaskId())
                        && "session-1".equals(event.getSessionId())
                        && "user-1".equals(event.getUserId())
                        && "codex-worker".equals(event.getAgentId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "COMPLETED".equals(event.getStatus())
                        && "AWAITING_REPLY".equals(event.getInteractionState())
                        && Boolean.FALSE.equals(event.getRecoverable())
        ));
    }

    @Test
    void failTask_publishesTaskStatusChangeEventWithError() {
        CodexTaskEntity entity = createTask(
                "task-2", "session-2", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 3, 26, 11, 0)
        );
        when(taskRepository.findByTaskId("task-2")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.failTask("task-2", "worker-task-2", "thread-2", "worker timeout");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-2".equals(event.getTaskId())
                        && "FAILED".equals(event.getStatus())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "worker timeout".equals(event.getErrorMessage())
                        && "AWAITING_REPLY".equals(event.getInteractionState())
                        && Boolean.TRUE.equals(event.getRecoverable())
        ));
    }

    @Test
    void workingDirectoryRejectionPublishesDefinitiveTerminalEvent() {
        CodexTaskEntity entity = createTask(
                "task-cwd-rejected", "session-2", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 26, 10, 30)
        );
        when(taskRepository.findByTaskIdForUpdate("task-cwd-rejected"))
                .thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.failTask(
                "task-cwd-rejected",
                "task-cwd-rejected",
                null,
                "CODEX_WORKING_DIRECTORY_UNAVAILABLE");

        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-cwd-rejected".equals(event.getTaskId())
                        && "FAILED".equals(event.getStatus())
                        && "CODEX_WORKING_DIRECTORY_UNAVAILABLE".equals(event.getErrorMessage())
                        && Boolean.FALSE.equals(event.getRecoverable())
        ));
    }

    @Test
    void sdkStreamFailureBeforeAcceptancePublishesDefinitiveTerminalEvent() {
        CodexTaskEntity entity = createTask(
                "task-sdk-not-accepted", "session-2", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 28, 12, 0)
        );
        entity.setTenantId("tenant-1");
        entity.setWorkerTaskId(null);
        when(taskRepository.findByTaskIdForUpdate("task-sdk-not-accepted"))
                .thenReturn(Optional.of(entity));
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.failTask(
                "task-sdk-not-accepted",
                null,
                null,
                "CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE");

        assertEquals("FAILED", entity.getStatus());
        assertNull(entity.getWorkerTaskId());
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-sdk-not-accepted".equals(event.getTaskId())
                        && "tenant-1".equals(event.getTenantId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "FAILED".equals(event.getStatus())
                        && "CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE".equals(event.getErrorMessage())
                        && Boolean.FALSE.equals(event.getRecoverable())
        ));
    }

    @Test
    void preAcceptanceFailurePublishesDefinitiveTerminalEvent() {
        CodexTaskEntity entity = createTask(
                "task-not-accepted", "session-2", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 14, 12, 0)
        );
        entity.setTenantId("tenant-1");
        entity.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        entity.setRuntimeAcceptanceState("PREPARED");
        entity.setWorkerTaskId(null);
        when(taskRepository.findByTaskIdForUpdate("task-not-accepted"))
                .thenReturn(Optional.of(entity));
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertTrue(service.failTaskIfAcceptanceNotStarted(
                "task-not-accepted", "CODEX_RUNTIME_ACCEPT_FAILED"));

        assertEquals("FAILED", entity.getStatus());
        assertEquals("TERMINAL", entity.getRuntimeAcceptanceState());
        assertNull(entity.getWorkerTaskId(),
                "A task rejected before acceptance has no Worker task to reconnect");
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-not-accepted".equals(event.getTaskId())
                        && "tenant-1".equals(event.getTenantId())
                        && "RUNNING".equals(event.getPreviousStatus())
                        && "FAILED".equals(event.getStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())
        ));
    }

    @Test
    void resyncFailedTaskPublishesNonTerminalRecoveryTransition() {
        CodexTaskEntity entity = createTask(
                "task-resync", "session-resync", "worker-1", "dir-1", "FAILED",
                LocalDateTime.of(2026, 3, 26, 11, 30)
        );
        entity.setWorkerTaskId("worker-task-resync");
        when(taskRepository.findByTaskIdAndUserIdForUpdate("task-resync", "user-1"))
                .thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.resyncTaskForProvider(
                CodexTaskService.CODEX_PROVIDER_TYPE, "task-resync", "user-1");

        assertEquals("RUNNING", entity.getStatus());
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-resync".equals(event.getTaskId())
                        && "FAILED".equals(event.getPreviousStatus())
                        && "RUNNING".equals(event.getStatus())
                        && event.getRecoverable() == null
        ));
        verify(streamRelay).reconnectTask("task-resync", "session-resync", "worker-1");
    }

    @Test
    void lateFailureCannotReverseCompletedTask() {
        CodexTaskEntity entity = createTask(
                "task-terminal", "session-1", "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 7, 10, 12, 0));
        entity.setRuntimeType("APP_SERVER");
        entity.setWorkerTaskId("task-terminal");
        when(taskRepository.findByTaskIdForUpdate("task-terminal")).thenReturn(Optional.of(entity));

        service.failTask("task-terminal", "task-terminal", "thread-1", "CODEX_RUNTIME_REMOTE_FAILED");

        assertEquals("COMPLETED", entity.getStatus());
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void lateCompletionCannotReverseFailedOrAbortedTask() {
        for (String terminal : List.of("FAILED", "ABORTED")) {
            CodexTaskEntity entity = createTask(
                    "task-" + terminal, "session-1", "worker-1", "dir-1", terminal,
                    LocalDateTime.of(2026, 7, 10, 12, 0));
            entity.setRuntimeType("APP_SERVER");
            entity.setWorkerTaskId("task-" + terminal);
            when(taskRepository.findByTaskIdForUpdate("task-" + terminal)).thenReturn(Optional.of(entity));

            service.completeTask("task-" + terminal, "task-" + terminal, "thread-1", "late",
                    null, null, null, null, null, "gpt-5.6-sol");

            assertEquals(terminal, entity.getStatus());
        }
        verify(taskRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void existingSessionWithExplicitLegacyAffinityCanDrainUltraOnSdk() {
        SessionEntity session = new SessionEntity();
        session.setId("session-legacy");
        session.setProviderStateJson(ProviderStateCodec.writeObject(Map.of(
                ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, "legacy-sdk:worker-1",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, 1,
                ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, "SDK_EXEC",
                ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, 0)));
        when(sessionEntityRepository.findById("session-legacy")).thenReturn(Optional.of(session));

        CodexRuntimeBinding binding = ReflectionTestUtils.invokeMethod(
                service, "resolveRuntimeBinding", "worker-1", "codex-ultra",
                "codex-worker", "task-new", "session-legacy");

        assertNotNull(binding);
        assertEquals(CodexRuntimeType.SDK_EXEC, binding.getRuntimeType());
        assertEquals("legacy-sdk:worker-1", binding.getRuntimeId());
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void existingLegacySessionCannotMoveToAnotherPhysicalWorker() {
        SessionEntity session = new SessionEntity();
        session.setId("session-legacy");
        session.setProviderStateJson(ProviderStateCodec.writeObject(Map.of(
                ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, "legacy-sdk:worker-1",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, 1,
                ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, "SDK_EXEC",
                ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, 0)));
        when(sessionEntityRepository.findById("session-legacy")).thenReturn(Optional.of(session));

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "resolveRuntimeBinding", "worker-2", "codex-ultra",
                        "codex-worker", "task-new", "session-legacy"));

        assertEquals("CODEX_RUNTIME_AFFINITY_MISMATCH", error.getCode());
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void trackedSyncDoesNotReuseAnotherProviderThreadAffinity() {
        when(taskRepository.findFirstByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeOrderByCreatedAtDesc(
                "shared-thread", "worker-1", "user-1", CodexTaskService.CODEX_PROVIDER_TYPE))
                .thenReturn(Optional.empty());
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createTrackedSyncTask("user-1", "worker-1", null,
                "continue", "D:/repo", null, "shared-thread", "codex-latest");

        verify(taskRepository).findFirstByCodexThreadIdAndWorkerIdAndUserIdAndProviderTypeOrderByCreatedAtDesc(
                "shared-thread", "worker-1", "user-1", CodexTaskService.CODEX_PROVIDER_TYPE);
        verify(taskRepository).save(argThat(task ->
                CodexTaskService.CODEX_PROVIDER_TYPE.equals(task.getProviderType())
                        && CodexRuntimeType.SDK_EXEC.name().equals(task.getRuntimeType())
                        && "legacy-sdk:worker-1".equals(task.getRuntimeId())));
        verify(runtimeRegistryService, never()).resolveBoundRuntime(
                anyString(), any(), anyString(), any());
    }

    @Test
    void existingAppServerSessionValidatesModelAndFeaturesAgainstBoundRevision() {
        SessionEntity session = new SessionEntity();
        session.setId("session-app");
        session.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        session.setProviderStateJson(ProviderStateCodec.writeObject(Map.of(
                ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, "app-main",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, 1,
                ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, "APP_SERVER",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID, "instance-a",
                ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, 7)));
        when(sessionEntityRepository.findById("session-app")).thenReturn(Optional.of(session));
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId("app-main")
                .runtimeRevision(1)
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId("worker-1")
                .endpointUrl("http://127.0.0.1:3062")
                .instanceId("instance-a")
                .routingEpoch(7L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                "app-main", 1, "worker-1", "instance-a")).thenReturn(binding);

        CodexRuntimeBinding resolved = ReflectionTestUtils.invokeMethod(
                service, "resolveRuntimeBinding", "worker-1", "codex-terra:ultra",
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-new", "session-app",
                Set.of("attachments"));

        assertEquals(binding, resolved);
        verify(runtimeRegistryService).validateBoundRuntimeCapabilities(
                binding, "codex-terra:ultra", Set.of("attachments"));
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void preallocatedAppServerSessionInitializesRuntimeAffinityOnFirstProviderTask() {
        SessionEntity session = new SessionEntity();
        session.setId("session-preallocated");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        when(sessionEntityRepository.findById("session-preallocated")).thenReturn(Optional.of(session));
        when(sessionEntityRepository.findByIdAndUserIdForUpdate("session-preallocated", "user-1"))
                .thenReturn(Optional.of(session));
        when(taskRepository.findFirstBySessionIdOrderByCreatedAtDesc("session-preallocated"))
                .thenReturn(Optional.empty());
        when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc("session-preallocated"))
                .thenReturn(List.of());

        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("sessionId", "session-preallocated");
        params.put("prompt", "continue in a new branch");
        params.put("model", "codex-terra:ultra");
        params.put("providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        InternalTaskDispatchMarkers.markRuntimeAffinityInitialization(params);

        DispatchTaskDTO task = service.createTaskDirectForProvider(
                CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, params, "user-1", "tenant-1");

        assertEquals("session-preallocated", task.getSessionId());
        assertNotNull(savedTask[0]);
        assertEquals(CodexRuntimeType.APP_SERVER.name(), savedTask[0].getRuntimeType());
        assertEquals("app-main", savedTask[0].getRuntimeId());
        assertEquals(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, session.getProviderType());
        assertEquals("worker-1", session.getCurrentWorkerId());
        assertEquals(savedTask[0].getTaskId(), session.getLatestTaskId());
        verify(runtimeRegistryService).selectForNewTask(
                eq("worker-1"), eq("codex-terra:ultra"),
                eq(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE), anyString(), eq(Set.of()));
    }

    @Test
    void runtimeAffinityInitializationRejectsNonPristineSession() {
        SessionEntity session = new SessionEntity();
        session.setId("session-used");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        session.setCurrentWorkerId("worker-1");
        when(sessionEntityRepository.findById("session-used")).thenReturn(Optional.of(session));
        when(sessionEntityRepository.findByIdAndUserIdForUpdate("session-used", "user-1"))
                .thenReturn(Optional.of(session));
        when(taskRepository.findFirstBySessionIdOrderByCreatedAtDesc("session-used"))
                .thenReturn(Optional.empty());
        when(sessionTaskRepository.findBySessionIdOrderByCreatedAtDesc("session-used"))
                .thenReturn(List.of());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("workerId", "worker-1");
        params.put("sessionId", "session-used");
        params.put("prompt", "continue");
        params.put("model", "codex-terra:ultra");
        params.put("providerType", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        InternalTaskDispatchMarkers.markRuntimeAffinityInitialization(params);

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> service.createTaskDirectForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        params, "user-1", "tenant-1"));

        assertEquals("CODEX_RUNTIME_AFFINITY_INITIALIZATION_REJECTED", error.getCode());
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void ordinaryAppServerContinuationWithoutAffinityStillFailsClosed() {
        SessionEntity session = new SessionEntity();
        session.setId("session-missing-affinity");
        when(sessionEntityRepository.findById("session-missing-affinity"))
                .thenReturn(Optional.of(session));
        when(taskRepository.findFirstBySessionIdOrderByCreatedAtDesc("session-missing-affinity"))
                .thenReturn(Optional.empty());

        CodexRuntimeUnavailableException error = assertThrows(CodexRuntimeUnavailableException.class,
                () -> ReflectionTestUtils.invokeMethod(
                        service, "resolveRuntimeBinding", "worker-1", "codex-terra:ultra",
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, "task-new",
                        "session-missing-affinity", Set.of()));

        assertEquals("CODEX_RUNTIME_AFFINITY_MISSING", error.getCode());
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void trackedSyncRejectsAppServerSessionProvider() {
        SessionEntity session = new SessionEntity();
        session.setId("session-app");
        session.setUserId("user-1");
        session.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        session.setProviderStateJson(ProviderStateCodec.writeObject(Map.of(
                ProviderStateCodec.FIELD_CODEX_RUNTIME_ID, "app-main",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION, 1,
                ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE, "APP_SERVER",
                ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID, "instance-a",
                ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH, 3)));
        when(sessionEntityRepository.findById("session-app")).thenReturn(Optional.of(session));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createTrackedSyncTask("user-1", "worker-1", "session-app",
                        "continue", "D:/repo", null, null, "codex-latest"));

        assertTrue(error.getMessage().startsWith("SESSION_PROVIDER_MISMATCH"));
        verify(taskRepository, never()).save(any());
        verify(runtimeRegistryService, never()).selectForNewTask(
                anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void checkpointProgressKeepsAppServerAcceptanceSubscribed() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("task-progress");
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeAcceptanceState("SUBSCRIBED");
        when(taskRepository.findByTaskId("task-progress")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordWorkerProgress(
                "task-progress", "worker-task-1", "thread-1", null, 3, false, false);

        assertEquals("SUBSCRIBED", entity.getRuntimeAcceptanceState());
        assertEquals(3, entity.getLastAckedSeq());
    }

    @Test
    void appServerAcceptanceLifecycleSyncsToUnifiedTaskState() {
        CodexTaskEntity entity = createTask(
                "task-runtime-state", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 11, 16, 0));
        entity.setRuntimeId("app-main");
        entity.setRuntimeRevision(7);
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeInstanceId("instance-a");
        entity.setRoutingEpoch(11L);
        entity.setRuntimeAcceptanceState("SUBSCRIBED");
        SessionTaskEntity projection = new SessionTaskEntity();
        when(taskRepository.findByTaskId("task-runtime-state")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(sessionTaskRepository.findByTaskId("task-runtime-state"))
                .thenReturn(Optional.of(projection));

        service.recordWorkerProgress(
                "task-runtime-state", "task-runtime-state", "thread-1", null, 4, false, true);

        Map<String, Object> committed = ProviderStateCodec.parseObject(projection.getTaskStateJson());
        assertEquals("app-main", committed.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_ID));
        assertEquals(7, committed.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION));
        assertEquals("APP_SERVER", committed.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_TYPE));
        assertEquals("instance-a", committed.get(ProviderStateCodec.FIELD_CODEX_RUNTIME_INSTANCE_ID));
        assertEquals(11, committed.get(ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH));
        assertEquals("COMMITTED", committed.get(ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE));

        service.completeTask("task-runtime-state", "task-runtime-state", "thread-1", "done",
                null, null, null, null, null, null);

        Map<String, Object> terminal = ProviderStateCodec.parseObject(projection.getTaskStateJson());
        assertEquals("TERMINAL", terminal.get(ProviderStateCodec.FIELD_RUNTIME_ACCEPTANCE_STATE));
        verify(sessionTaskRepository, times(2)).findByTaskIdForUpdate("task-runtime-state");
    }

    @Test
    void appServerProgressAndCompletionPreserveRequestedModel() {
        CodexTaskEntity entity = createTask(
                "task-ultra", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 10, 12, 0));
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeAcceptanceState("SUBSCRIBED");
        entity.setModel("codex-ultra");
        when(taskRepository.findByTaskId("task-ultra")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordWorkerProgress(
                "task-ultra", "worker-task-1", "thread-1", "gpt-5.6-sol", 3, false, true);
        service.completeTask("task-ultra", "worker-task-1", "thread-1", "done",
                null, null, null, null, null, "gpt-5.6-sol");

        assertEquals("codex-ultra", entity.getModel());
        assertEquals("COMPLETED", entity.getStatus());
    }

    @Test
    void sdkProgressAndCompletionContinueToUseWorkerReportedModel() {
        CodexTaskEntity entity = createTask(
                "task-sdk", "session-1", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 10, 12, 0));
        entity.setRuntimeType("SDK_EXEC");
        entity.setModel("codex-max");
        when(taskRepository.findByTaskId("task-sdk")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordWorkerProgress(
                "task-sdk", "worker-task-1", "thread-1", "gpt-5.6-sol", 3, false, false);
        assertEquals("gpt-5.6-sol", entity.getModel());

        service.completeTask("task-sdk", "worker-task-1", "thread-1", "done",
                null, null, null, null, null, "gpt-5.6-sol-updated");

        assertEquals("gpt-5.6-sol-updated", entity.getModel());
    }

    @Test
    void executionCommittedProgressConfirmsAppServerCommit() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("task-progress");
        entity.setRuntimeType("APP_SERVER");
        entity.setRuntimeAcceptanceState("SUBSCRIBED");
        when(taskRepository.findByTaskId("task-progress")).thenReturn(Optional.of(entity));
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.recordWorkerProgress(
                "task-progress", "worker-task-1", "thread-1", null, 4, false, true);

        assertEquals("COMMITTED", entity.getRuntimeAcceptanceState());
        assertEquals(4, entity.getLastAckedSeq());
    }

    @Test
    void workerProgressCannotReplacePersistedWorkerTaskIdentity() {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId("task-progress");
        entity.setRuntimeType("APP_SERVER");
        entity.setWorkerTaskId("task-progress");
        when(taskRepository.findByTaskId("task-progress")).thenReturn(Optional.of(entity));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.recordWorkerProgress(
                        "task-progress", "other-task", "thread-1", null, 5, false, false));

        assertTrue(error.getMessage().contains("CODEX_RUNTIME_IDEMPOTENCY_CONFLICT"));
        assertEquals("task-progress", entity.getWorkerTaskId());
    }

    private CodexTaskEntity[] stubSuccessfulTaskCreation(String sessionId) {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));
        when(sessionManager.createSession(any())).thenReturn(sessionId);
        return savedTask;
    }

    private LlmModelConfigDTO codexModelConfig(List<String> availableModels) {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX");
        config.setAvailableModels(availableModels);
        return config;
    }

    private LlmModelConfigDTO appServerModelConfig(List<String> availableModels) {
        LlmModelConfigDTO config = new LlmModelConfigDTO();
        config.setWorkerBackend("OPENAI_CODEX_APP_SERVER");
        config.setAvailableModels(availableModels);
        return config;
    }

    private CodexTaskEntity appServerInputTask(String status) {
        CodexTaskEntity task = createTask(
                "task-input", "session-1", "worker-1", "dir-1", status, LocalDateTime.now());
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(2);
        task.setRuntimeType("APP_SERVER");
        task.setRuntimeInstanceId("worker-instance-a");
        task.setRuntimeAcceptanceState("SUBSCRIBED");
        task.setWorkerTaskId("worker-task-1");
        task.setCodexThreadId("thread-1");
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        return task;
    }

    private CodexTaskEntity terminalAppServerTask(String taskId, String tenantId) {
        CodexTaskEntity task = createTask(
                taskId, "session-1", "worker-1", "dir-1", "COMPLETED", LocalDateTime.now());
        task.setTenantId(tenantId);
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.APP_SERVER.name());
        task.setRuntimeId("app-main");
        task.setRuntimeRevision(2);
        task.setRuntimeInstanceId("instance-a");
        task.setRuntimeAcceptanceState("TERMINAL");
        task.setWorkerTaskId("worker-native-" + taskId);
        task.setCodexThreadId("thread-" + taskId);
        return task;
    }

    private CodexTaskEntity pendingAppServerRetryTask(String taskId, String tenantId) {
        CodexTaskEntity task = terminalAppServerTask(taskId, tenantId);
        task.setStatus("CANCEL_REQUESTED");
        task.setRuntimeAcceptanceState("ABORT_REQUESTED");
        return task;
    }

    private void stubAppServerAbortRetryTask(CodexTaskEntity task, String operationId) {
        stubAppServerAbortRetryTask(task, task, operationId);
    }

    private void stubAppServerAbortRetryTask(
            CodexTaskEntity reservedTask, CodexTaskEntity rereadTask, String operationId) {
        doReturn(Optional.of(reservedTask)).when(taskRepository)
                .findByTaskIdForUpdate(reservedTask.getTaskId());
        doReturn(Optional.of(rereadTask)).when(taskRepository)
                .findByTaskId(reservedTask.getTaskId());
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId(operationId);
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId(reservedTask.getRuntimeId())
                .runtimeRevision(reservedTask.getRuntimeRevision())
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId(reservedTask.getWorkerId())
                .endpointUrl("http://worker.example")
                .authToken("worker-token")
                .instanceId(reservedTask.getRuntimeInstanceId())
                .routingEpoch(1L)
                .build();
        lenient().when(runtimeRegistryService.resolveBoundRuntime(
                reservedTask.getRuntimeId(), reservedTask.getRuntimeRevision(), reservedTask.getWorkerId(),
                reservedTask.getRuntimeInstanceId())).thenReturn(binding);
        lenient().when(clientFactory.getOrCreate(
                "runtime:" + reservedTask.getRuntimeId() + ":" + reservedTask.getRuntimeRevision(),
                "http://worker.example", "worker-token", reservedTask.getRuntimeInstanceId()))
                .thenReturn(workerClient);
        lenient().when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
    }

    private void stubStaleCleanupTask(CodexTaskEntity task, String operationId) {
        stubStaleCleanupTask(task, task, operationId, true);
    }

    /**
     * Models the explicit re-read after the short reservation transaction.
     * Keeping both values in one setup avoids a strict-Mockito duplicate stub
     * and makes the affinity-change regression deterministic.
     */
    private void stubStaleCleanupTask(
            CodexTaskEntity reservedTask, CodexTaskEntity rereadTask, String operationId) {
        stubStaleCleanupTask(reservedTask, rereadTask, operationId, false);
    }

    private void stubStaleCleanupTask(
            CodexTaskEntity reservedTask,
            CodexTaskEntity rereadTask,
            String operationId,
            boolean stubNativeWorkerClient) {
        CodexTaskEntity task = reservedTask;
        doReturn(Optional.of(task)).when(taskRepository).findByTaskIdForUpdate(task.getTaskId());
        doReturn(Optional.of(rereadTask)).when(taskRepository).findByTaskId(task.getTaskId());
        when(terminationOperationService.hasActiveOperationForTask(task.getTaskId())).thenReturn(false);
        when(terminationOperationService.accept(any())).thenAnswer(invocation -> {
            TerminationOperationService.CreateCommand command = invocation.getArgument(0);
            TerminationOperationEntity operation = new TerminationOperationEntity();
            operation.setOperationId(operationId);
            operation.setSchemaVersion(1);
            operation.setTaskId(command.taskId());
            operation.setProviderTaskId(command.providerTaskId());
            operation.setWorkerId(command.workerId());
            operation.setKind(command.kind());
            operation.setOrigin(command.origin());
            operation.setActorId(command.actorId());
            operation.setActorType(command.actorType());
            operation.setAuthorizationDecisionId(command.authorizationDecisionId());
            operation.setReasonCode(command.reasonCode());
            operation.setCorrelationId(command.correlationId());
            return operation;
        });
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        if (!stubNativeWorkerClient) {
            return;
        }
        CodexRuntimeBinding binding = CodexRuntimeBinding.builder()
                .runtimeId(task.getRuntimeId())
                .runtimeRevision(task.getRuntimeRevision())
                .runtimeType(CodexRuntimeType.APP_SERVER)
                .workerId(task.getWorkerId())
                .endpointUrl("http://worker.example")
                .authToken("worker-token")
                .instanceId(task.getRuntimeInstanceId())
                .routingEpoch(1L)
                .build();
        when(runtimeRegistryService.resolveBoundRuntime(
                task.getRuntimeId(), task.getRuntimeRevision(), task.getWorkerId(),
                task.getRuntimeInstanceId())).thenReturn(binding);
        when(clientFactory.getOrCreate(
                "runtime:" + task.getRuntimeId() + ":" + task.getRuntimeRevision(),
                "http://worker.example", "worker-token", task.getRuntimeInstanceId()))
                .thenReturn(workerClient);
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"})
    void resumeTaskRepairsVerifiedAbsentSdkTaskAndRequestsRetry(String status) {
        CodexTaskEntity staleTask = stubActiveResumeTask(status, "worker-task-stale");
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        WebClientResponseException notFound = workerStatusError(404, "Not Found");
        lenient().when(workerClient.getTaskStatus("worker-task-stale")).thenReturn(Mono.error(notFound));
        lenient().when(workerClient.listCliProcesses()).thenReturn(Mono.just(Map.of(
                "processes", List.of(), "active_task_count", 0, "total", 0)));
        lenient().when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CodexStaleTaskRepairedException error = assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("CODEX_STALE_TASK_REPAIRED"));
        assertEquals("FAILED", staleTask.getStatus());
        assertEquals("CODEX_STALE_TASK_REPAIRED", staleTask.getErrorMessage());
        verify(workerClient).getTaskStatus("worker-task-stale");
        verify(workerClient).listCliProcesses();
        verify(taskRepository).save(argThat((CodexTaskEntity entity) ->
                "task-stale".equals(entity.getTaskId())
                        && "FAILED".equals(entity.getStatus())
                        && "CODEX_STALE_TASK_REPAIRED".equals(entity.getErrorMessage())));
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "task-stale".equals(entity.getTaskId())
                        && "FAILED".equals(entity.getStatus())
                        && "CODEX_STALE_TASK_REPAIRED".equals(entity.getErrorMessage())));
        verify(sessionEntityRepository).save(argThat((SessionEntity entity) ->
                "session-1".equals(entity.getId())
                        && "AWAITING_REPLY".equals(entity.getInteractionState())));
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-stale".equals(event.getTaskId())
                        && status.equals(event.getPreviousStatus())
                        && "FAILED".equals(event.getStatus())
                        && Boolean.TRUE.equals(event.getRecoverable())));
        verifyNoInteractions(terminationOperationService);
    }

    @Test
    void resumeTaskRepairsOlderTaskWithoutRewindingNewerSessionProjection() {
        LocalDateTime projectedActivity = LocalDateTime.of(2026, 7, 18, 15, 0);
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        session.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        session.setCurrentWorkerId("worker-new");
        session.setCurrentDirectoryId("dir-new");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-current\",\"custom\":\"keep\"}");
        session.setLatestTaskId("task-newer");
        session.setInteractionState("PROCESSING");
        session.setLastActivityAt(projectedActivity);
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity staleTask = createTask(
                "task-old", "session-1", "worker-1", "dir-old", "RUNNING",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        staleTask.setTenantId("tenant-1");
        staleTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        staleTask.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        staleTask.setWorkerTaskId("worker-task-old");
        staleTask.setCodexThreadId("thread-old");
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-current", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-old"));
        doReturn(Optional.of(staleTask)).when(taskRepository).findByTaskIdForUpdate("task-old");
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-task-old"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertEquals("FAILED", staleTask.getStatus());
        assertEquals(CodexStaleTaskRepairedException.CODE, staleTask.getErrorMessage());
        verify(sessionTaskRepository).save(argThat((SessionTaskEntity entity) ->
                "task-old".equals(entity.getTaskId())
                        && "FAILED".equals(entity.getStatus())
                        && CodexStaleTaskRepairedException.CODE.equals(entity.getErrorMessage())));
        verify(sessionEntityRepository, never()).save(any(SessionEntity.class));
        assertEquals("task-newer", session.getLatestTaskId());
        assertEquals("PROCESSING", session.getInteractionState());
        assertEquals(projectedActivity, session.getLastActivityAt());
        assertEquals(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, session.getProviderType());
        assertEquals("worker-new", session.getCurrentWorkerId());
        assertEquals("dir-new", session.getCurrentDirectoryId());
        assertEquals("{\"codexThreadId\":\"thread-current\",\"custom\":\"keep\"}",
                session.getProviderStateJson());
    }

    @Test
    void resumeTaskRepairsLatestTaskWithoutReconstructingSessionAffinity() {
        LocalDateTime projectedActivity = LocalDateTime.of(2026, 7, 17, 12, 0);
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        session.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        session.setCurrentWorkerId("worker-current");
        session.setCurrentDirectoryId("dir-current");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-current\",\"custom\":\"keep\"}");
        session.setLatestTaskId("task-old");
        session.setInteractionState("PROCESSING");
        session.setLastActivityAt(projectedActivity);
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity staleTask = createTask(
                "task-old", "session-1", "worker-1", "dir-old", "RUNNING",
                LocalDateTime.of(2026, 7, 17, 10, 0));
        staleTask.setTenantId("tenant-1");
        staleTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        staleTask.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        staleTask.setWorkerTaskId("worker-task-old");
        staleTask.setCodexThreadId("thread-old");
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-current", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-old"));
        doReturn(Optional.of(staleTask)).when(taskRepository).findByTaskIdForUpdate("task-old");
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("worker-task-old"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertEquals("AWAITING_REPLY", session.getInteractionState());
        assertTrue(session.getLastActivityAt().isAfter(projectedActivity));
        assertEquals("task-old", session.getLatestTaskId());
        assertEquals(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE, session.getProviderType());
        assertEquals("worker-current", session.getCurrentWorkerId());
        assertEquals("dir-current", session.getCurrentDirectoryId());
        assertEquals("{\"codexThreadId\":\"thread-current\",\"custom\":\"keep\"}",
                session.getProviderStateJson());
        verify(sessionEntityRepository).save(session);
    }

    @Test
    void resumeTaskAllowsUserRetryAfterPreviouslyRepairedTaskBecomesHistoricalAnchor() {
        CodexTaskEntity[] savedTask = new CodexTaskEntity[1];
        when(taskRepository.save(any(CodexTaskEntity.class))).thenAnswer(invocation -> {
            savedTask[0] = invocation.getArgument(0);
            return savedTask[0];
        });
        when(taskRepository.findByTaskId(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(savedTask[0]));

        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        session.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-repaired");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity repairedTask = stubHistoricalResumeTask(
                "task-repaired", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);
        repairedTask.setStatus("FAILED");
        repairedTask.setErrorMessage(CodexStaleTaskRepairedException.CODE);

        DispatchTaskDTO result = service.resumeTask(
                "user-1", "tenant-1", resumeParams());

        assertEquals("RUNNING", result.getStatus());
        assertEquals("session-1", result.getSessionId());
        assertEquals("thread-1", result.getCodexThreadId());
        assertNotEquals("task-repaired", result.getTaskId());
        assertEquals("FAILED", repairedTask.getStatus());
        assertEquals(CodexStaleTaskRepairedException.CODE, repairedTask.getErrorMessage());
        verify(taskRepository).findByTaskIdForUpdate("task-repaired");
        verifyNoInteractions(workerClient);
    }

    @Test
    void resumeTaskResolvesPendingInputWhenRepairingVerifiedAbsentTask() {
        CodexTaskEntity staleTask = stubActiveResumeTask("AWAITING_INPUT", "worker-task-stale");
        SessionTaskEntity sessionTask = new SessionTaskEntity();
        sessionTask.setTaskId("task-stale");
        sessionTask.setSessionId("session-1");
        sessionTask.setUserId("user-1");
        sessionTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        sessionTask.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                null, CodexTaskService.CODEX_PROVIDER_TYPE, "codexPendingInteraction",
                pendingState(pendingInputProjection(false), "PENDING")));
        when(sessionTaskRepository.findByTaskId("task-stale")).thenReturn(Optional.of(sessionTask));
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertEquals("FAILED", staleTask.getStatus());
        assertTrue(sessionTask.getTaskStateJson().contains("\"state\":\"RESOLVED\""));
        assertTrue(sessionTask.getTaskStateJson().contains("\"resolved_reason\":\"stale_task_repaired\""));
    }

    @Test
    void resumeTaskRepairsVerifiedAbsentCodexBizSdkTask() {
        CodexTaskEntity staleTask = stubActiveResumeTask(
                "RUNNING", "worker-task-stale", CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                CodexRuntimeType.SDK_EXEC.name());
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of())));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTaskForProvider(
                        CodexTaskService.CODEX_BIZ_PROVIDER_TYPE,
                        "user-1", "tenant-1", resumeParams()));

        assertEquals("FAILED", staleTask.getStatus());
    }

    @Test
    void resumeTaskKeepsGuardWhenWorkerStillReportsTaskActive() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-active");
        lenient().when(workerClient.getTaskStatus("worker-task-active")).thenReturn(Mono.just(Map.of(
                "task_id", "worker-task-active", "status", "running")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(workerClient).getTaskStatus("worker-task-active");
        verify(workerClient, never()).listCliProcesses();
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenWorkerStatusProbeFails() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-unavailable");
        WebClientResponseException unavailable = workerStatusError(503, "Service Unavailable");
        lenient().when(workerClient.getTaskStatus("worker-task-unavailable"))
                .thenReturn(Mono.error(unavailable));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(workerClient).getTaskStatus("worker-task-unavailable");
        verify(workerClient, never()).listCliProcesses();
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenProcessSnapshotContainsUnidentifiedOrphan() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        lenient().when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        lenient().when(workerClient.listCliProcesses()).thenReturn(Mono.just(Map.of(
                "processes", List.of(Map.of(
                        "pid", 4123, "command", "codex", "memory_mb", 8,
                        "process_type", "codex", "is_orphan", true)),
                "active_task_count", 0, "total", 1)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(workerClient).getTaskStatus("worker-task-stale");
        verify(workerClient).listCliProcesses();
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenProcessSnapshotStillContainsWorkerTask() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of(
                workerProcess(4123, false, "worker-task-stale", "thread-other")))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenProcessSnapshotStillContainsThread() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of(
                workerProcess(4123, false, "worker-task-other", "thread-1")))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenProcessProbeFailsAfterStatus404() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses())
                .thenReturn(Mono.error(workerStatusError(503, "Service Unavailable")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWhenProcessSnapshotIsMalformed() {
        CodexTaskEntity activeTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(Map.of(
                "processes", List.of(), "active_task_count", 0, "total", 1)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskAllowsAttributableOrphanFromDifferentThreadWhenTargetIsAbsent() {
        CodexTaskEntity staleTask = stubActiveResumeTask("RUNNING", "worker-task-stale");
        when(workerClient.getTaskStatus("worker-task-stale"))
                .thenReturn(Mono.error(workerStatusError(404, "Not Found")));
        when(workerClient.listCliProcesses()).thenReturn(Mono.just(processSnapshot(List.of(
                workerProcess(4123, true, null, "thread-other")))));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThrows(CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertEquals("FAILED", staleTask.getStatus());
    }

    @Test
    void resumeTaskDoesNotProbeAppServerRuntime() {
        CodexTaskEntity activeTask = stubActiveResumeTask(
                "RUNNING", "worker-task-active", CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                CodexRuntimeType.APP_SERVER.name());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTaskForProvider(
                        CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE,
                        "user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("RUNNING", activeTask.getStatus());
        verifyNoInteractions(workerClient);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskKeepsGuardWithoutPersistedWorkerTaskId() {
        CodexTaskEntity activeTask = stubActiveResumeTask("AWAITING_INPUT", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        assertEquals("AWAITING_INPUT", activeTask.getStatus());
        verifyNoInteractions(workerClient);
        verify(taskRepository, never()).save(any());
    }

    private CodexTaskEntity stubActiveResumeTask(String status, String workerTaskId) {
        return stubActiveResumeTask(status, workerTaskId, CodexTaskService.CODEX_PROVIDER_TYPE,
                CodexRuntimeType.SDK_EXEC.name());
    }

    private CodexTaskEntity stubActiveResumeTask(String status, String workerTaskId,
                                                 String providerType, String runtimeType) {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setTenantId("tenant-1");
        session.setProviderType(providerType);
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-stale");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity task = createTask(
                "task-stale", "session-1", "worker-1", "dir-1", status,
                LocalDateTime.of(2026, 7, 17, 17, 0));
        task.setTenantId("tenant-1");
        task.setProviderType(providerType);
        task.setRuntimeType(runtimeType);
        task.setWorkerTaskId(workerTaskId);
        task.setCodexThreadId("thread-1");

        lenient().when(taskRepository.findActiveResumeTaskIds(
                        "session-1", "thread-1", "worker-1", "user-1", providerType,
                        List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                        PageRequest.of(0, 1)))
                .thenReturn(List.of("task-stale"));
        doReturn(Optional.of(task)).when(taskRepository).findByTaskIdForUpdate("task-stale");
        lenient().when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder().baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        lenient().when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        return task;
    }

    private CodexTaskEntity stubHistoricalResumeTask(String taskId,
                                                      String sessionId,
                                                      String codexThreadId,
                                                      String providerType) {
        CodexTaskEntity task = createTask(
                taskId, sessionId, "worker-1", "dir-1", "COMPLETED",
                LocalDateTime.of(2026, 7, 17, 16, 0));
        task.setProviderType(providerType);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setCodexThreadId(codexThreadId);

        lenient().when(taskRepository.findActiveResumeTaskIds(
                        sessionId, codexThreadId, "worker-1", "user-1", providerType,
                        List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                        PageRequest.of(0, 1)))
                .thenReturn(List.of());
        if (codexThreadId == null) {
            lenient().when(taskRepository.findLatestResumeSessionTaskIds(
                            sessionId, "user-1", PageRequest.of(0, 1)))
                    .thenReturn(List.of(taskId));
        } else {
            lenient().when(taskRepository.findLatestResumeThreadTaskIds(
                            codexThreadId, "worker-1", "user-1", providerType,
                            PageRequest.of(0, 1)))
                    .thenReturn(List.of(taskId));
        }
        doReturn(Optional.of(task)).when(taskRepository).findByTaskIdForUpdate(taskId);
        return task;
    }

    private SessionEntityRepository.ResumeStateView resumeStateView(SessionEntity session) {
        return resumeStateView(
                session.getId(), session.getProviderStateJson(), session.getLatestTaskId());
    }

    private SessionEntityRepository.ResumeStateView resumeStateView(String sessionId,
                                                                    String providerStateJson,
                                                                    String latestTaskId) {
        return new SessionEntityRepository.ResumeStateView() {
            @Override
            public String getId() {
                return sessionId;
            }

            @Override
            public String getProviderStateJson() {
                return providerStateJson;
            }

            @Override
            public String getLatestTaskId() {
                return latestTaskId;
            }
        };
    }

    private Map<String, Object> processSnapshot(List<Map<String, Object>> processes) {
        return Map.of("processes", processes, "active_task_count", 0, "total", processes.size());
    }

    private Map<String, Object> workerProcess(int pid, boolean orphan,
                                              String workerTaskId, String codexThreadId) {
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("pid", pid);
        process.put("command", "codex");
        process.put("memory_mb", 8);
        process.put("process_type", "codex");
        process.put("is_orphan", orphan);
        if (workerTaskId != null) process.put("foggy_task_id", workerTaskId);
        if (codexThreadId != null) process.put("codex_thread_id", codexThreadId);
        return process;
    }

    private Map<String, Object> resumeParams() {
        return Map.of("workerId", "worker-1", "sessionId", "session-1", "prompt", "continue");
    }

    private WebClientResponseException workerStatusError(int status, String statusText) {
        return WebClientResponseException.create(status, statusText, null,
                "{\"error\":\"worker probe failed\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void resumeTaskLocksActiveTaskBeforeSessionWhenThreadAwaitsUserInput() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-input");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(entityManager.find(SessionEntity.class, "session-1")).thenReturn(session);
        CodexTaskEntity activeTask = createTask(
                "task-input", "session-1", "worker-1", "dir-1", "AWAITING_INPUT",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        activeTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        activeTask.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        activeTask.setCodexThreadId("thread-1");
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-1", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-input"));
        doReturn(Optional.of(activeTask)).when(taskRepository).findByTaskIdForUpdate("task-input");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", Map.of(
                        "workerId", "worker-1",
                        "sessionId", "session-1",
                        "prompt", "continue")));

        assertTrue(error.getMessage().contains("正在运行"));
        var lockOrder = org.mockito.Mockito.inOrder(taskRepository, sessionEntityRepository);
        lockOrder.verify(taskRepository).findByTaskIdForUpdate("task-input");
        lockOrder.verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");
        verify(entityManager).refresh(session);
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskGuardsActiveTaskFromAnotherSessionOnSameThread() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-history");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));
        SessionEntity activeSession = new SessionEntity();
        activeSession.setId("session-2");
        activeSession.setUserId("user-1");
        activeSession.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        activeSession.setLatestTaskId("task-cross-session");
        when(sessionEntityRepository.findById("session-2")).thenReturn(Optional.of(activeSession));
        CodexTaskEntity activeTask = createTask(
                "task-cross-session", "session-2", "worker-1", "dir-1", "AWAITING_INPUT",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        activeTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        activeTask.setCodexThreadId("thread-1");
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-1", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-cross-session"));
        doReturn(Optional.of(activeTask)).when(taskRepository)
                .findByTaskIdForUpdate("task-cross-session");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        var lockOrder = org.mockito.Mockito.inOrder(taskRepository, sessionEntityRepository);
        lockOrder.verify(taskRepository).findByTaskIdForUpdate("task-cross-session");
        lockOrder.verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");
        lockOrder.verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-2", "user-1");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskLocksOwnerSessionBeforeRequestedSessionWhenOwnerSortsFirst() {
        SessionEntity requestedSession = new SessionEntity();
        requestedSession.setId("session-2");
        requestedSession.setUserId("user-1");
        requestedSession.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        requestedSession.setLatestTaskId("task-history");
        when(sessionEntityRepository.findById("session-2"))
                .thenReturn(Optional.of(requestedSession));
        SessionEntity ownerSession = new SessionEntity();
        ownerSession.setId("session-1");
        ownerSession.setUserId("user-1");
        ownerSession.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        ownerSession.setLatestTaskId("task-cross-session");
        when(sessionEntityRepository.findById("session-1"))
                .thenReturn(Optional.of(ownerSession));

        CodexTaskEntity activeTask = createTask(
                "task-cross-session", "session-1", "worker-1", "dir-1", "AWAITING_INPUT",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        activeTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        activeTask.setCodexThreadId("thread-1");
        when(taskRepository.findActiveResumeTaskIds(
                "session-2", "thread-1", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-cross-session"));
        doReturn(Optional.of(activeTask)).when(taskRepository)
                .findByTaskIdForUpdate("task-cross-session");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", Map.of(
                        "workerId", "worker-1",
                        "sessionId", "session-2",
                        "prompt", "continue")));

        assertTrue(error.getMessage().contains("正在运行"));
        var lockOrder = org.mockito.Mockito.inOrder(taskRepository, sessionEntityRepository);
        lockOrder.verify(taskRepository).findByTaskIdForUpdate("task-cross-session");
        lockOrder.verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");
        lockOrder.verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-2", "user-1");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskGuardsSameSessionActiveTaskWhenNativeThreadWasCleared() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderStateJson(null);
        session.setLatestTaskId("task-active");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity activeTask = createTask(
                "task-active", "session-1", "worker-other", "dir-1", "AWAITING_INPUT",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        activeTask.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        activeTask.setCodexThreadId("thread-before-rewind");
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", null, "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-active"));
        doReturn(Optional.of(activeTask)).when(taskRepository).findByTaskIdForUpdate("task-active");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("正在运行"));
        verify(taskRepository).findByTaskIdForUpdate("task-active");
        verify(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskRequiresRetryWhenAnotherRequestAlreadyRepairedActiveTask() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-repaired");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));

        CodexTaskEntity repairedTask = createTask(
                "task-repaired", "session-1", "worker-1", "dir-1", "FAILED",
                LocalDateTime.of(2026, 7, 17, 17, 0));
        repairedTask.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        repairedTask.setCodexThreadId("thread-1");
        repairedTask.setErrorMessage(CodexStaleTaskRepairedException.CODE);
        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-1", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1))).thenReturn(List.of("task-repaired"));
        doReturn(Optional.of(repairedTask)).when(taskRepository)
                .findByTaskIdForUpdate("task-repaired");

        CodexStaleTaskRepairedException error = assertThrows(
                CodexStaleTaskRepairedException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains(CodexStaleTaskRepairedException.CODE));
        verify(sessionEntityRepository, never())
                .findResumeStateByIdAndUserIdForUpdate(anyString(), anyString());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskRejectsWhenSessionThreadChangesBeforeSessionLock() {
        stubHistoricalResumeTask(
                "task-history", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);
        doReturn(Optional.of(resumeStateView(
                "session-1", "{\"codexThreadId\":\"thread-1\"}", "task-history")))
                .when(sessionEntityRepository)
                .findResumeStateByIdAndUserId("session-1", "user-1");
        doReturn(Optional.of(resumeStateView(
                "session-1", "{\"codexThreadId\":\"thread-2\"}", "task-history")))
                .when(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("CODEX_RESUME_STATE_CHANGED"));
        verify(taskRepository).findByTaskIdForUpdate("task-history");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskRejectsWhenSessionLatestTaskChangesBeforeSessionLock() {
        stubHistoricalResumeTask(
                "task-history", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);
        doReturn(Optional.of(resumeStateView(
                "session-1", "{\"codexThreadId\":\"thread-1\"}", "task-history")))
                .when(sessionEntityRepository)
                .findResumeStateByIdAndUserId("session-1", "user-1");
        doReturn(Optional.of(resumeStateView(
                "session-1", "{\"codexThreadId\":\"thread-1\"}", "task-concurrent")))
                .when(sessionEntityRepository)
                .findResumeStateByIdAndUserIdForUpdate("session-1", "user-1");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("CODEX_RESUME_STATE_CHANGED"));
        verify(taskRepository).findByTaskIdForUpdate("task-history");
        verify(taskRepository, never()).save(any());
    }

    @Test
    void resumeTaskRejectsWhenNewActiveTaskAppearsAfterHistoricalAnchorLock() {
        SessionEntity session = new SessionEntity();
        session.setId("session-1");
        session.setUserId("user-1");
        session.setProviderStateJson("{\"codexThreadId\":\"thread-1\"}");
        session.setLatestTaskId("task-history");
        when(sessionEntityRepository.findById("session-1")).thenReturn(Optional.of(session));
        stubHistoricalResumeTask(
                "task-history", "session-1", "thread-1", CodexTaskService.CODEX_PROVIDER_TYPE);

        when(taskRepository.findActiveResumeTaskIds(
                "session-1", "thread-1", "worker-1", "user-1",
                CodexTaskService.CODEX_PROVIDER_TYPE,
                List.of("RUNNING", "AWAITING_INPUT", "CANCEL_REQUESTED"),
                PageRequest.of(0, 1)))
                .thenReturn(List.of(), List.of("task-concurrent"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resumeTask("user-1", "tenant-1", resumeParams()));

        assertTrue(error.getMessage().contains("CODEX_RESUME_STATE_CHANGED"));
        verify(taskRepository).findByTaskIdForUpdate("task-history");
        verify(taskRepository, never()).findByTaskIdForUpdate("task-concurrent");
        verify(sessionEntityRepository, never())
                .findResumeStateByIdAndUserIdForUpdate(anyString(), anyString());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void runtimeReconcileConsumesExactPersistedProofOnceAndDoesNotRedispatchOnReplay() {
        CodexTaskEntity task = createTask(
                "task-runtime-reconcile", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setWorkerTaskId("provider-task-1");
        task.setCodexThreadId("thread-1");
        TerminationOperationEntity original = terminationOperation(
                "rt_original", "task-runtime-reconcile", "provider-task-1",
                "worker-1", "REMOTE_CANCEL", "ACKNOWLEDGED");
        TerminationOperationEntity reconciliation = terminationOperation(
                "rc_request1", "task-runtime-reconcile", "provider-task-1",
                "worker-1", "RECONCILE_CANCEL", "PENDING");

        when(taskRepository.findByTaskId("task-runtime-reconcile"))
                .thenReturn(Optional.of(task));
        when(taskRepository.findByTaskIdForUpdate("task-runtime-reconcile"))
                .thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("provider-task-1"))
                .thenReturn(Mono.just(Map.of("status", "cancel_requested")));
        when(workerClient.terminationSigningSecret()).thenReturn("worker-token");
        when(terminationOperationService.findLatestForTaskAndKind(
                "task-runtime-reconcile", "REMOTE_CANCEL")).thenReturn(original);
        when(terminationOperationService.find("rc_request1")).thenReturn(null);
        when(terminationOperationService.accept(any(), eq("rc_request1")))
                .thenReturn(reconciliation);
        when(workerClient.reconcileTermination(
                eq("provider-task-1"), eq("rt_original"), any(TerminationOperationCapability.class)))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "provider-task-1",
                        "worker_id", "worker-1",
                        "terminal_observed", true,
                        "terminal_status", "ABORTED",
                        "terminal_source", "WORKER_WIDE_ZERO_PROCESS_RECONCILIATION",
                        "provider_status", "WORKER_WIDE_ZERO_PROCESS_RECONCILED",
                        "process_snapshot", Map.of("total", 0),
                        "termination_operation", Map.of(
                                "operation_id", "rt_original",
                                "status", "OBSERVED_EXIT"),
                        "reconciliation_operation", Map.of(
                                "operation_id", "rc_request1",
                                "kind", "RECONCILE_CANCEL"))));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        var result = service.reconcileRuntimeTask(
                "task-runtime-reconcile", "user-1", "tenant-1",
                "worker-1", "request-1", false);
        var replay = service.reconcileRuntimeTask(
                "task-runtime-reconcile", "user-1", "tenant-1",
                "worker-1", "request-1", false);

        assertTrue(result.reconciliationChanged());
        assertEquals("ABORTED", result.providerStatus());
        assertTrue(replay.alreadyConsistent());
        assertEquals("ABORTED", task.getStatus());
        verify(workerClient, times(1)).reconcileTermination(
                eq("provider-task-1"), eq("rt_original"), any(TerminationOperationCapability.class));
        verify(terminationOperationService).markObservedTerminal("rt_original", "ABORTED");
        verify(terminationOperationService).markObservedTerminal("rc_request1", "ABORTED");
    }

    @Test
    void runtimeReconcileRepublishesDurableProviderTerminalStateForOuterTaskConvergence() {
        CodexTaskEntity task = createTask(
                "task-terminal-projection-gap", "session-1", "worker-1", "dir-1",
                "FAILED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setErrorMessage("CODEX_WORKER_STREAM_FAILED_BEFORE_ACCEPTANCE");
        when(taskRepository.findByTaskId("task-terminal-projection-gap"))
                .thenReturn(Optional.of(task));

        var dryRun = service.reconcileRuntimeTask(
                "task-terminal-projection-gap", "user-1", "tenant-1",
                "worker-1", "request-dry-run", true);
        var applied = service.reconcileRuntimeTask(
                "task-terminal-projection-gap", "user-1", "tenant-1",
                "worker-1", "request-apply", false);

        assertFalse(dryRun.reconciliationChanged());
        assertFalse(dryRun.alreadyConsistent());
        assertEquals("NAVIGATOR_TERMINAL_REPUBLISH_READY", dryRun.durableEvidence());
        assertTrue(applied.reconciliationChanged());
        assertFalse(applied.alreadyConsistent());
        assertEquals("NAVIGATOR_TERMINAL_REPUBLISHED", applied.durableEvidence());
        verify(eventPublisher).publishEvent(argThat((TaskStatusChangeEvent event) ->
                "task-terminal-projection-gap".equals(event.getTaskId())
                        && "FAILED".equals(event.getStatus())
                        && "FAILED".equals(event.getPreviousStatus())
                        && Boolean.FALSE.equals(event.getRecoverable())));
        verifyNoInteractions(workerManagementFacade, workerClient);
    }

    @Test
    void runtimeReconcileReplayRepairsNavigatorFromDurableWorkerProofWithoutSecondDispatch() {
        CodexTaskEntity task = createTask(
                "task-runtime-reconcile-replay", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setWorkerTaskId("provider-task-replay");
        task.setCodexThreadId("thread-1");
        TerminationOperationEntity original = terminationOperation(
                "rt_original_replay", "task-runtime-reconcile-replay",
                "provider-task-replay", "worker-1", "REMOTE_CANCEL", "UNCONFIRMED");
        TerminationOperationEntity reconciliation = terminationOperation(
                "rc_requestreplay", "task-runtime-reconcile-replay",
                "provider-task-replay", "worker-1", "RECONCILE_CANCEL", "PENDING");

        when(taskRepository.findByTaskId("task-runtime-reconcile-replay"))
                .thenReturn(Optional.of(task));
        when(taskRepository.save(any(CodexTaskEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("provider-task-replay"))
                .thenReturn(Mono.just(Map.of("status", "cancel_requested")));
        when(terminationOperationService.findLatestForTaskAndKind(
                "task-runtime-reconcile-replay", "REMOTE_CANCEL")).thenReturn(original);
        when(terminationOperationService.find("rc_requestreplay")).thenReturn(reconciliation);
        when(workerClient.getTerminationReconciliationReadiness(
                "provider-task-replay", "rt_original_replay"))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "provider-task-replay",
                        "worker_id", "worker-1",
                        "terminal_observed", true,
                        "terminal_status", "ABORTED",
                        "terminal_source", "WORKER_WIDE_ZERO_PROCESS_RECONCILIATION",
                        "provider_status", "WORKER_WIDE_ZERO_PROCESS_RECONCILED",
                        "process_snapshot", Map.of("total", 0),
                        "termination_operation", Map.of(
                                "operation_id", "rt_original_replay",
                                "status", "OBSERVED_EXIT"))));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);
        ReflectionTestUtils.setField(service, "terminationTransactionManager", transactionManager);

        var result = service.reconcileRuntimeTask(
                "task-runtime-reconcile-replay", "user-1", "tenant-1",
                "worker-1", "request-replay", false);

        assertTrue(result.reconciliationChanged());
        assertFalse(result.alreadyConsistent());
        assertEquals("ABORTED", result.providerStatus());
        assertEquals("ABORTED", task.getStatus());
        verify(workerClient, never()).reconcileTermination(anyString(), anyString(), any());
        verify(terminationOperationService).markObservedTerminal("rt_original_replay", "ABORTED");
        verify(terminationOperationService).markObservedTerminal("rc_requestreplay", "ABORTED");
        verify(transactionManager).getTransaction(any());
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void runtimeReconcileFailsClosedWhenWorkerProofDoesNotMatchOriginalOperation() {
        CodexTaskEntity task = createTask(
                "task-runtime-reconcile-invalid", "session-1", "worker-1", "dir-1",
                "CANCEL_REQUESTED", LocalDateTime.now());
        task.setTenantId("tenant-1");
        task.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        task.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        task.setWorkerTaskId("provider-task-invalid");
        TerminationOperationEntity original = terminationOperation(
                "rt_original_invalid", "task-runtime-reconcile-invalid",
                "provider-task-invalid", "worker-1", "REMOTE_CANCEL", "UNCONFIRMED");

        when(taskRepository.findByTaskId("task-runtime-reconcile-invalid"))
                .thenReturn(Optional.of(task));
        when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://worker.example")
                        .authToken("worker-token").build());
        when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        when(workerClient.getTaskStatus("provider-task-invalid"))
                .thenReturn(Mono.just(Map.of("status", "cancel_requested")));
        when(terminationOperationService.findLatestForTaskAndKind(
                "task-runtime-reconcile-invalid", "REMOTE_CANCEL")).thenReturn(original);
        when(workerClient.getTerminationReconciliationReadiness(
                "provider-task-invalid", "rt_original_invalid"))
                .thenReturn(Mono.just(Map.of(
                        "task_id", "provider-task-invalid",
                        "worker_id", "other-worker",
                        "terminal_observed", true,
                        "terminal_status", "ABORTED",
                        "terminal_source", "WORKER_WIDE_ZERO_PROCESS_RECONCILIATION",
                        "provider_status", "WORKER_WIDE_ZERO_PROCESS_RECONCILED",
                        "process_snapshot", Map.of("total", 0),
                        "termination_operation", Map.of(
                                "operation_id", "rt_original_invalid",
                                "status", "OBSERVED_EXIT"))));
        ReflectionTestUtils.setField(service, "terminationOperationService", terminationOperationService);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.reconcileRuntimeTask(
                        "task-runtime-reconcile-invalid", "user-1", "tenant-1",
                        "worker-1", "request-invalid", true));

        assertEquals("RUNTIME_TASK_RECONCILE_EVIDENCE_UNREACHABLE", error.getMessage());
        assertEquals("CANCEL_REQUESTED", task.getStatus());
        verify(terminationOperationService, never()).accept(any(), anyString());
        verify(workerClient, never()).reconcileTermination(anyString(), anyString(), any());
    }

    private SessionTaskEntity inputSessionTask() {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-input");
        task.setSessionId("session-1");
        task.setUserId("user-1");
        task.setProviderType(CodexTaskService.CODEX_APP_SERVER_PROVIDER_TYPE);
        return task;
    }

    private Map<String, Object> pendingInputProjection(boolean secret) {
        return pendingInputProjection(secret, "request-1");
    }

    private Map<String, Object> pendingInputProjection(boolean secret, Object requestId) {
        Map<String, Object> question = new LinkedHashMap<>();
        question.put("id", "choice");
        question.put("header", "Choice");
        question.put("question", "Select one option");
        question.put("options", List.of(
                Map.of("label", "one", "description", ""),
                Map.of("label", "two", "description", "Second option")));
        question.put("is_other", true);
        question.put("is_secret", secret);
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("contract_version", 1);
        pending.put("request_id", requestId);
        pending.put("method", "item/tool/requestUserInput");
        pending.put("thread_id", "thread-1");
        pending.put("turn_id", "turn-1");
        pending.put("item_id", "item-1");
        pending.put("questions", List.of(question));
        pending.put("runtime_instance_id", "pool-lease-7");
        pending.put("created_at", "2026-07-11T12:00:00Z");
        return pending;
    }

    private Map<String, Object> pendingState(Map<String, Object> projection, String state) {
        Map<String, Object> pending = new LinkedHashMap<>(projection);
        pending.put("state", state);
        return pending;
    }

    private CodexTaskEntity createTask(String taskId, String sessionId, String workerId,
                                       String directoryId, String status, LocalDateTime createdAt) {
        CodexTaskEntity entity = new CodexTaskEntity();
        entity.setTaskId(taskId);
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setDirectoryId(directoryId);
        entity.setUserId("user-1");
        entity.setPrompt(taskId + " prompt");
        entity.setStatus(status);
        entity.setCreatedAt(createdAt);
        entity.setUpdatedAt(createdAt.plusMinutes(1));
        return entity;
    }

    private CodexTaskEntity completionReadinessTask(String taskId, String workerTaskId) {
        CodexTaskEntity entity = createTask(
                taskId, "session-completion", "worker-1", "dir-1", "RUNNING",
                LocalDateTime.of(2026, 7, 25, 9, 0));
        entity.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        entity.setRuntimeType(CodexRuntimeType.SDK_EXEC.name());
        entity.setWorkerTaskId(workerTaskId);
        when(taskRepository.findByTaskId(taskId)).thenReturn(Optional.of(entity));
        lenient().when(workerManagementFacade.getCodexConfig("worker-1"))
                .thenReturn(CodexConfig.builder()
                        .baseUrl("http://worker.example")
                        .authToken("worker-token")
                        .build());
        lenient().when(clientFactory.getOrCreate(
                "worker-1:codex", "http://worker.example", "worker-token"))
                .thenReturn(workerClient);
        return entity;
    }

    private Map<String, Object> completionObservation(
            String workerId, String providerTaskId, int dispatchCount) {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("worker_id", workerId);
        observed.put("task_id", providerTaskId);
        observed.put("provider_task_id", providerTaskId);
        observed.put("dispatch_count", dispatchCount);
        observed.put("worker_observed_at", "2026-07-25T01:02:03Z");
        observed.put("worker_task_known", false);
        observed.put("worker_task_state", "UNKNOWN");
        observed.put("provider_process_present", false);
        observed.put("provider_process_state", "ABSENT");
        observed.put("provider_active_task_present", null);
        observed.put("provider_task_terminal", true);
        observed.put("provider_terminal_status", "COMPLETED");
        observed.put("final_output_present", true);
        observed.put("final_output_durable", true);
        observed.put("final_output_digest",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        observed.put("final_output_recorded_at", "2026-07-25T01:02:02Z");
        observed.put("structured_output_present", false);
        observed.put("structured_output_digest", null);
        observed.put("completion_signal_present", true);
        observed.put("completion_signal_source", "PROVIDER_TERMINAL_EVENT");
        observed.put("completion_signal_recorded_at", "2026-07-25T01:02:02Z");
        observed.put("result_recoverable", true);
        observed.put("completion_evidence_schema", "CODEX_COMPLETION_RECEIPT_V2");
        return observed;
    }

    private TerminationOperationEntity terminationOperation(
            String operationId,
            String taskId,
            String providerTaskId,
            String workerId,
            String kind,
            String dispatchState) {
        TerminationOperationEntity entity = new TerminationOperationEntity();
        entity.setOperationId(operationId);
        entity.setSchemaVersion(1);
        entity.setTaskId(taskId);
        entity.setProviderTaskId(providerTaskId);
        entity.setSessionId("session-1");
        entity.setOwnerUserId("user-1");
        entity.setTenantId("tenant-1");
        entity.setProviderType(CodexTaskService.CODEX_PROVIDER_TYPE);
        entity.setWorkerId(workerId);
        entity.setKind(kind);
        entity.setOrigin("UPSTREAM_USER");
        entity.setActorId("user-1");
        entity.setActorType("RUNTIME_CLIENT");
        entity.setAuthorizationDecisionId("runtime-reconcile:test");
        entity.setReasonCode("operator-stuck-task-reconciliation");
        entity.setCorrelationId("runtime-task-reconcile:test");
        entity.setStatus("ACCEPTED");
        entity.setDispatchState(dispatchState);
        entity.setRequestedAt(LocalDateTime.now());
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        return entity;
    }
}
