package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.dto.SessionRelationDTO;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRelationRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionForwardServiceTest {

    private static final String CLIENT_REQUEST_ID =
            "5f5a402e-8506-4735-8441-1cbaca240627";

    @Mock
    private SessionMessageRepository sessionMessageRepository;
    @Mock
    private SessionRelationRepository sessionRelationRepository;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private AgentSubmitPipeline agentSubmitPipeline;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;
    @Mock
    private SessionForwardTransactionBoundary transactionBoundary;
    @Mock
    private TrustedNavigatorTaskCreateCommandFactory forwardCommandFactory;
    @Mock
    private TrustedNavigatorTaskCreateCommandFactory.ForwardCommandScope forwardScope;
    @Mock
    private SessionForwardTargetSessionReservationService targetReservationService;
    @Mock
    private SessionForwardOutcomeStore outcomeStore;
    @Mock
    private TaskCreateTargetResolver.CreateExecutionPlan resolvedTarget;
    @Mock
    private TaskCreateContextNormalizer.PendingContextClaim pendingContextClaim;

    private SessionForwardService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionBoundary.executeExistingTarget(any()))
                .thenAnswer(invocation -> callback(invocation).get());
        lenient().when(transactionBoundary.executeNewTarget(any()))
                .thenAnswer(invocation -> callback(invocation).get());
        service = new SessionForwardService(
                sessionMessageRepository,
                sessionRelationRepository,
                sessionTaskRepository,
                workingDirectoryRepository,
                taskDispatchFacade,
                agentSubmitPipeline,
                resourceAccessService,
                transactionBoundary,
                forwardCommandFactory,
                targetReservationService,
                outcomeStore
        );
    }

    @Test
    void forwardToNewSession_existingSession_reusesTargetContextAndSavesRelation() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");
        sourceSession.setCurrentWorkerId("worker-source");
        sourceSession.setCurrentDirectoryId("dir-source");
        sourceSession.setMilestoneId("ms-source");

        SessionEntity targetSession = new SessionEntity();
        targetSession.setId("session-child");
        targetSession.setUserId("user-1");
        targetSession.setParentSessionId("session-source");
        targetSession.setAgentId("agent-target");
        targetSession.setCurrentWorkerId("worker-target");
        targetSession.setCurrentDirectoryId("dir-target");
        targetSession.setMilestoneId("ms-target");
        targetSession.setLatestTaskId("task-target-latest");
        targetSession.setLatestModel("gpt-5.4");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");

        SessionTaskEntity latestTask = new SessionTaskEntity();
        latestTask.setTaskId("task-target-latest");
        latestTask.setSessionId("session-child");
        latestTask.setAgentId("agent-latest");
        latestTask.setWorkerId("worker-target");
        latestTask.setDirectoryId("dir-target");
        latestTask.setCwd("D:/repo");
        latestTask.setModel("gpt-5.6-sol");
        latestTask.setModelConfigId("cfg-target");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .sessionId("session-child")
                .workerId("worker-target")
                .directoryId("dir-target")
                .model("gpt-5.6-sol")
                .modelConfigId("cfg-target")
                .providerType("codex-worker")
                .build();

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(resourceAccessService.requireOwnedSession("session-child", "user-1", "tenant-1"))
                .thenReturn(targetSession);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId(
                "task-target-latest", "user-1", "tenant-1")).thenReturn(Optional.of(latestTask));
        when(taskDispatchFacade.resumeTask(any(), any())).thenReturn(resumedTask);
        when(sessionRelationRepository.save(any())).thenAnswer(invocation -> {
            SessionRelationEntity relation = invocation.getArgument(0);
            relation.setId(99L);
            return relation;
        });

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("EXISTING_SESSION");
        request.setTargetSessionId("session-child");
        request.setPrompt("补充说明");

        SessionForwardCreateResponse response = service.forwardToNewSession(request, "user-1", "tenant-1");

        assertEquals("EXISTING_SESSION", response.getTargetMode());
        assertEquals("session-child", response.getTargetSessionId());
        assertEquals("task-resumed", response.getTask().getTaskId());

        ArgumentCaptor<TaskDispatchRequest> requestCaptor = ArgumentCaptor.forClass(TaskDispatchRequest.class);
        ArgumentCaptor<AgentResolveContext> contextCaptor = ArgumentCaptor.forClass(AgentResolveContext.class);
        verify(taskDispatchFacade).resumeTask(requestCaptor.capture(), contextCaptor.capture());

        TaskDispatchRequest dispatchRequest = requestCaptor.getValue();
        assertEquals("session-child", dispatchRequest.getSessionId());
        assertEquals("agent-target", dispatchRequest.getAgentId());
        assertEquals("worker-target", dispatchRequest.getWorkerId());
        assertEquals("dir-target", dispatchRequest.getDirectoryId());
        assertEquals("D:/repo", dispatchRequest.getCwd());
        assertEquals("补充说明", dispatchRequest.getPrompt());
        assertEquals("gpt-5.6-sol", dispatchRequest.getModel());
        assertEquals("cfg-target", dispatchRequest.getModelConfigId());

        AgentResolveContext context = contextCaptor.getValue();
        assertEquals("user-1", context.getUserId());
        assertEquals("tenant-1", context.getTenantId());
        assertEquals("session-child", context.getSessionId());
        assertEquals("UI_FORWARD", context.getRequestSource());

        ArgumentCaptor<SessionRelationEntity> relationCaptor = ArgumentCaptor.forClass(SessionRelationEntity.class);
        verify(sessionRelationRepository).save(relationCaptor.capture());
        SessionRelationEntity savedRelation = relationCaptor.getValue();
        assertEquals("FORWARD", savedRelation.getRelationType());
        assertEquals("EXISTING_SESSION", savedRelation.getTargetMode());
        assertEquals("session-source", savedRelation.getSourceSessionId());
        assertEquals("session-child", savedRelation.getTargetSessionId());
        assertEquals("worker-target", savedRelation.getTargetWorkerId());
        assertEquals("dir-target", savedRelation.getTargetDirectoryId());
        assertEquals("ms-target", savedRelation.getTargetMilestoneId());
        assertEquals("cfg-target", savedRelation.getTargetModelConfigId());
        assertEquals("codex-worker", savedRelation.getTargetProviderType());
        assertTrue(savedRelation.getMetadataJson().contains("\"targetMode\":\"EXISTING_SESSION\""));

        verify(transactionBoundary).executeExistingTarget(any());
        verify(transactionBoundary, never()).executeNewTarget(any());
        verify(targetReservationService, never()).reserve(any(), any());
    }

    @Test
    void forwardToNewSession_rejectsUnownedSourceBeforeReadingMessageOrMutating() {
        when(resourceAccessService.requireOwnedSession("session-other", "user-1", "tenant-1"))
                .thenThrow(new SecurityException("Resource access denied"));

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-other");
        request.setSourceMessageId("msg-secret");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-1");

        SecurityException error = assertThrows(SecurityException.class,
                () -> service.forwardToNewSession(request, "user-1", "tenant-1"));

        assertEquals("Resource access denied", error.getMessage());
        verify(sessionMessageRepository, never()).findById(any());
        verify(targetReservationService, never()).reserve(any(), any());
        verify(sessionRelationRepository, never()).save(any());
        verify(taskDispatchFacade, never()).resumeTask(any(), any());
    }

    @Test
    void forwardToExistingSession_rejectsLatestTaskBoundToAnotherSession() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionEntity targetSession = new SessionEntity();
        targetSession.setId("session-target");
        targetSession.setUserId("user-1");
        targetSession.setParentSessionId("session-source");
        targetSession.setLatestTaskId("task-cross-bound");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");

        SessionTaskEntity crossBoundTask = new SessionTaskEntity();
        crossBoundTask.setTaskId("task-cross-bound");
        crossBoundTask.setSessionId("session-other");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(resourceAccessService.requireOwnedSession("session-target", "user-1", "tenant-1"))
                .thenReturn(targetSession);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(sessionTaskRepository.findByTaskIdAndUserIdAndTenantId(
                "task-cross-bound", "user-1", "tenant-1"))
                .thenReturn(Optional.of(crossBoundTask));

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("EXISTING_SESSION");
        request.setTargetSessionId("session-target");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.forwardToNewSession(request, "user-1", "tenant-1"));

        assertEquals("Target session latest task binding is invalid", error.getMessage());
        verify(taskDispatchFacade, never()).resumeTask(any(), any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_existingSession_rejectsUnrelatedTargetSession() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionEntity unrelatedTarget = new SessionEntity();
        unrelatedTarget.setId("session-other");
        unrelatedTarget.setUserId("user-1");
        unrelatedTarget.setParentSessionId("another-parent");
        unrelatedTarget.setLatestTaskId("task-2");

        SessionEntity unrelatedRoot = new SessionEntity();
        unrelatedRoot.setId("another-parent");
        unrelatedRoot.setUserId("user-1");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");
        sourceMessage.setCreatedAt(LocalDateTime.now());

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(resourceAccessService.requireOwnedSession("session-other", "user-1", "tenant-1"))
                .thenReturn(unrelatedTarget);
        when(resourceAccessService.requireOwnedSession("another-parent", "user-1", "tenant-1"))
                .thenReturn(unrelatedRoot);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(sessionRelationRepository.existsByUserIdAndRelationTypeAndSourceSessionIdAndTargetSessionId(
                "user-1", "FORWARD", "session-source", "session-other"
        )).thenReturn(false);

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("EXISTING_SESSION");
        request.setTargetSessionId("session-other");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.forwardToNewSession(request, "user-1", "tenant-1")
        );

        assertEquals("Target session must be a previously forwarded child session", error.getMessage());
        verify(taskDispatchFacade, never()).resumeTask(any(), any());
        verify(sessionRelationRepository, never()).save(any());
        verify(sessionTaskRepository, never())
                .findBySessionIdAndUserIdAndTenantIdOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void forwardToNewSession_newSessionUsesCanonicalOrderAndFlattensToRootParent() {
        SessionEntity rootSession = new SessionEntity();
        rootSession.setId("session-root");
        rootSession.setUserId("user-1");

        SessionEntity sourceChild = new SessionEntity();
        sourceChild.setId("session-child-a");
        sourceChild.setUserId("user-1");
        sourceChild.setParentSessionId("session-root");
        sourceChild.setCurrentWorkerId("worker-source");
        sourceChild.setCurrentDirectoryId("dir-source");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-child-a");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");

        when(resourceAccessService.requireOwnedSession("session-child-a", "user-1", "tenant-1"))
                .thenReturn(sourceChild);
        when(resourceAccessService.requireOwnedSession("session-root", "user-1", "tenant-1"))
                .thenReturn(rootSession);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        NewForwardFixture forward = stubNewForward(true, 100L);
        when(resolvedTarget.sessionId()).thenReturn("synthetic-preplan-session");
        when(resolvedTarget.pendingContextClaim()).thenReturn(pendingContextClaim);
        when(pendingContextClaim.navigatorSessionId())
                .thenReturn("synthetic-preplan-session");
        when(pendingContextClaim.ownerUserId()).thenReturn("user-1");
        when(pendingContextClaim.tenantId()).thenReturn("tenant-1");
        when(pendingContextClaim.logicalAgentId()).thenReturn("agent-target");

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-child-a");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("NEW_SESSION");
        request.setAgentId("agent-target");
        request.setPrompt("继续拆一个分支");

        SessionForwardCreateResponse response = service.forwardToNewSession(
                request,
                "user-1",
                "tenant-1",
                CLIENT_REQUEST_ID);

        assertEquals("NEW_SESSION", response.getTargetMode());
        assertEquals(forward.targetSessionId(), response.getTargetSessionId());
        assertEquals(100L, response.getRelationId());
        assertEquals(forward.task(), response.getTask());
        assertEquals("agent-target", request.getAgentId());
        assertNull(request.getWorkerId());
        assertNull(request.getDirectoryId());
        assertNull(request.getModel());
        assertNull(request.getModelConfigId());

        ArgumentCaptor<SessionForwardTargetSessionReservationService.ReservationSpec>
                reservationCaptor = ArgumentCaptor.forClass(
                SessionForwardTargetSessionReservationService.ReservationSpec.class);
        InOrder order = inOrder(forwardCommandFactory, targetReservationService);
        order.verify(forwardCommandFactory).mintForwardScope(
                eq(CLIENT_REQUEST_ID), any());
        order.verify(forwardCommandFactory).preauthorizeForwardScope(
                eq(forwardScope), any());
        order.verify(targetReservationService).reserve(
                eq(CLIENT_REQUEST_ID), reservationCaptor.capture());
        order.verify(forwardCommandFactory).executeForwardScoped(
                eq(forwardScope), any(), any(), any());
        assertEquals("session-root", reservationCaptor.getValue().rootParentSessionId());
        assertEquals("dir-target", reservationCaptor.getValue().directoryId());
        verify(transactionBoundary).executeNewTarget(any());
        verify(transactionBoundary, never()).executeExistingTarget(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_preauthorizationFailureHasNoReservationProviderOrOutcomeEffect() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any()))
                .thenReturn(resolvedTarget);
        when(resolvedTarget.ownerUserId()).thenReturn("user-1");
        when(resolvedTarget.tenantId()).thenReturn("tenant-1");
        when(resolvedTarget.logicalAgentId()).thenReturn("agent-target");
        when(resolvedTarget.physicalWorkerId()).thenReturn("worker-target");
        when(resolvedTarget.directoryId()).thenReturn("dir-target");
        when(resolvedTarget.model()).thenReturn("gpt-5.4");
        when(resolvedTarget.modelConfigId()).thenReturn("model-config-target");

        com.foggy.navigator.common.entity.WorkingDirectoryEntity directory =
                new com.foggy.navigator.common.entity.WorkingDirectoryEntity();
        directory.setDirectoryId("dir-target");
        directory.setUserId("user-1");
        directory.setTenantId("tenant-1");
        directory.setWorkerId("worker-target");
        directory.setPath("/workspace/target");
        directory.setEnabled(true);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId(
                "dir-target", "user-1")).thenReturn(Optional.of(directory));

        when(forwardCommandFactory.mintForwardScope(
                eq(CLIENT_REQUEST_ID), any())).thenReturn(forwardScope);
        when(forwardScope.clientRequestId()).thenReturn(CLIENT_REQUEST_ID);
        doThrow(new SecurityException("Forward scope denied"))
                .when(forwardCommandFactory)
                .preauthorizeForwardScope(eq(forwardScope), any());

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("NEW_SESSION");
        request.setAgentId("agent-target");

        SecurityException error = assertThrows(SecurityException.class,
                () -> service.forwardToNewSession(
                        request, "user-1", "tenant-1", CLIENT_REQUEST_ID));

        assertEquals("Forward scope denied", error.getMessage());
        verify(transactionBoundary).executeNewTarget(any());
        verify(targetReservationService, never()).reserve(any(), any());
        verify(forwardCommandFactory, never())
                .executeForwardScoped(any(), any(), any(), any());
        verify(agentSubmitPipeline, never()).submit(any());
        verify(outcomeStore, never()).insertFresh(any());
        verify(outcomeStore, never()).requireExactReplay(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_rejectsDirectoryTenantDriftBeforeScopeOrReservation() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any()))
                .thenReturn(resolvedTarget);
        when(resolvedTarget.ownerUserId()).thenReturn("user-1");
        when(resolvedTarget.tenantId()).thenReturn("tenant-1");
        when(resolvedTarget.physicalWorkerId()).thenReturn("worker-target");
        when(resolvedTarget.directoryId()).thenReturn("dir-target");

        com.foggy.navigator.common.entity.WorkingDirectoryEntity directory =
                new com.foggy.navigator.common.entity.WorkingDirectoryEntity();
        directory.setDirectoryId("dir-target");
        directory.setUserId("user-1");
        directory.setTenantId("tenant-other");
        directory.setWorkerId("worker-target");
        directory.setPath("/workspace/foreign");
        directory.setEnabled(true);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId(
                "dir-target", "user-1")).thenReturn(Optional.of(directory));

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-1");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");
        request.setDirectoryId("dir-target");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.forwardToNewSession(
                        request, "user-1", "tenant-1", CLIENT_REQUEST_ID));

        assertEquals("FORWARD_TARGET_DIRECTORY_CHANGED_BEFORE_PLAN_FREEZE",
                error.getMessage());
        verify(forwardCommandFactory, never()).mintForwardScope(any(), any());
        verify(forwardCommandFactory, never())
                .preauthorizeForwardScope(any(), any());
        verify(targetReservationService, never()).reserve(any(), any());
        verify(forwardCommandFactory, never())
                .executeForwardScoped(any(), any(), any(), any());
        verify(agentSubmitPipeline, never()).submit(any());
        verify(outcomeStore, never()).insertFresh(any());
        verify(outcomeStore, never()).requireExactReplay(any());
    }

    @Test
    void forwardToNewSession_recoveredTaskResultUsesReadOnlyCanonicalProjection() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionTaskEntity sourceTask = new SessionTaskEntity();
        sourceTask.setTaskId("task-source");
        sourceTask.setSessionId("session-source");
        sourceTask.setStatus("COMPLETED");
        sourceTask.setResultText("任务最终结果");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(resourceAccessService.requireOwnedTask("task-source", "user-1", "tenant-1"))
                .thenReturn(sourceTask);
        when(sessionMessageRepository.findById("task-result-task-source"))
                .thenReturn(Optional.empty());
        NewForwardFixture forward = stubNewForward(true, 102L);

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("task-result-task-source");
        request.setSourceTaskId("task-source");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");
        request.setPrompt("继续处理");

        SessionForwardCreateResponse response = service.forwardToNewSession(request, "user-1", "tenant-1");

        assertEquals("NEW_SESSION", response.getTargetMode());
        assertEquals(forward.targetSessionId(), response.getTargetSessionId());
        String expectedReference = UUID.nameUUIDFromBytes(
                "forward-task-result:session-source:task-source"
                        .getBytes(StandardCharsets.UTF_8)).toString();
        assertEquals(expectedReference, response.getSourceMessageId());
        verify(sessionMessageRepository, never())
                .findBySessionIdAndTaskIdAndRoleOrderByCreatedAtDescIdDesc(
                        any(), any(), any());
        verify(outcomeStore).insertFresh(any());
        verify(outcomeStore, never()).requireExactReplay(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_recoveredTaskReferenceDoesNotDependOnExistingMessages() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionTaskEntity sourceTask = new SessionTaskEntity();
        sourceTask.setTaskId("task-source");
        sourceTask.setSessionId("session-source");
        sourceTask.setStatus("COMPLETED");
        sourceTask.setResultText("任务最终结果");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(resourceAccessService.requireOwnedTask("task-source", "user-1", "tenant-1"))
                .thenReturn(sourceTask);
        when(sessionMessageRepository.findById("task-result-task-source")).thenReturn(Optional.empty());
        NewForwardFixture forward = stubNewForward(false, 103L);

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("task-result-task-source");
        request.setSourceTaskId("task-source");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");

        SessionForwardCreateResponse response = service.forwardToNewSession(request, "user-1", "tenant-1");

        String expectedReference = UUID.nameUUIDFromBytes(
                "forward-task-result:session-source:task-source"
                        .getBytes(StandardCharsets.UTF_8)).toString();
        assertEquals(expectedReference, response.getSourceMessageId());
        assertEquals(forward.targetSessionId(), response.getTargetSessionId());
        assertEquals(103L, response.getRelationId());
        verify(sessionMessageRepository, never())
                .findBySessionIdAndTaskIdAndRoleOrderByCreatedAtDescIdDesc(
                        any(), any(), any());
        verify(outcomeStore, never()).insertFresh(any());
        verify(outcomeStore).requireExactReplay(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_recoveredTaskResult_rejectsInvalidTaskFactsWithoutMutation() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionTaskEntity mismatchedTask = new SessionTaskEntity();
        mismatchedTask.setTaskId("task-source");
        mismatchedTask.setSessionId("session-other");
        mismatchedTask.setStatus("COMPLETED");
        mismatchedTask.setResultText("结果");

        SessionTaskEntity runningTask = new SessionTaskEntity();
        runningTask.setTaskId("task-source");
        runningTask.setSessionId("session-source");
        runningTask.setStatus("RUNNING");
        runningTask.setResultText("结果");

        SessionTaskEntity emptyResultTask = new SessionTaskEntity();
        emptyResultTask.setTaskId("task-source");
        emptyResultTask.setSessionId("session-source");
        emptyResultTask.setStatus("COMPLETED");
        emptyResultTask.setResultText(" ");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(sessionMessageRepository.findById("task-result-task-source")).thenReturn(Optional.empty());
        when(resourceAccessService.requireOwnedTask("task-source", "user-1", "tenant-1"))
                .thenReturn(mismatchedTask, runningTask, emptyResultTask);

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("task-result-task-source");
        request.setSourceTaskId("task-source");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");

        assertEquals("Source task does not belong to source session",
                assertThrows(IllegalArgumentException.class,
                        () -> service.forwardToNewSession(request, "user-1", "tenant-1")).getMessage());
        assertEquals("Source task is not completed",
                assertThrows(IllegalArgumentException.class,
                        () -> service.forwardToNewSession(request, "user-1", "tenant-1")).getMessage());
        assertEquals("Source task result is empty",
                assertThrows(IllegalArgumentException.class,
                        () -> service.forwardToNewSession(request, "user-1", "tenant-1")).getMessage());

        verify(targetReservationService, never()).reserve(any(), any());
        verify(agentSubmitPipeline, never()).submit(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_recoveredTaskResult_rejectsUnownedTaskWithoutMutation() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(sessionMessageRepository.findById("task-result-task-secret")).thenReturn(Optional.empty());
        when(resourceAccessService.requireOwnedTask("task-secret", "user-1", "tenant-1"))
                .thenThrow(new SecurityException("Resource access denied"));

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("task-result-task-secret");
        request.setSourceTaskId("task-secret");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");

        SecurityException error = assertThrows(SecurityException.class,
                () -> service.forwardToNewSession(request, "user-1", "tenant-1"));

        assertEquals("Resource access denied", error.getMessage());
        verify(targetReservationService, never()).reserve(any(), any());
        verify(agentSubmitPipeline, never()).submit(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void forwardToNewSession_existingMessageFromAnotherSession_doesNotFallbackToTask() {
        SessionEntity sourceSession = new SessionEntity();
        sourceSession.setId("session-source");
        sourceSession.setUserId("user-1");

        SessionMessageEntity otherSessionMessage = new SessionMessageEntity();
        otherSessionMessage.setId("msg-other");
        otherSessionMessage.setSessionId("session-other");
        otherSessionMessage.setRole("ASSISTANT");
        otherSessionMessage.setContent("其他会话结果");

        when(resourceAccessService.requireOwnedSession("session-source", "user-1", "tenant-1"))
                .thenReturn(sourceSession);
        when(sessionMessageRepository.findById("msg-other")).thenReturn(Optional.of(otherSessionMessage));

        SessionForwardCreateRequest request = new SessionForwardCreateRequest();
        request.setSourceSessionId("session-source");
        request.setSourceMessageId("msg-other");
        request.setSourceTaskId("task-source");
        request.setTargetMode("NEW_SESSION");
        request.setWorkerId("worker-target");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.forwardToNewSession(request, "user-1", "tenant-1"));

        assertEquals("Source message not found: msg-other", error.getMessage());
        verify(resourceAccessService, never()).requireOwnedTask(any(), any(), any());
        verify(targetReservationService, never()).reserve(any(), any());
        verify(agentSubmitPipeline, never()).submit(any());
        verify(sessionRelationRepository, never()).save(any());
    }

    @Test
    void findIncomingForwardRelation_returnsLatestForwardForOwnedTarget() {
        SessionEntity targetSession = new SessionEntity();
        targetSession.setId("session-child-b");
        targetSession.setUserId("user-1");

        SessionRelationEntity relation = new SessionRelationEntity();
        relation.setId(101L);
        relation.setRelationType("FORWARD");
        relation.setTargetMode("NEW_SESSION");
        relation.setSourceSessionId("session-child-a");
        relation.setSourceMessageId("msg-1");
        relation.setTargetSessionId("session-child-b");
        relation.setSourceWorkerId("worker-source");
        relation.setTargetWorkerId("worker-target");
        relation.setCreatedAt(LocalDateTime.now());

        when(resourceAccessService.requireOwnedSession("session-child-b", "user-1", "tenant-1"))
                .thenReturn(targetSession);
        when(sessionRelationRepository.findFirstByUserIdAndRelationTypeAndTargetSessionIdOrderByCreatedAtDesc(
                "user-1", "FORWARD", "session-child-b"
        )).thenReturn(Optional.of(relation));

        SessionRelationDTO result = service.findIncomingForwardRelation("session-child-b", "user-1", "tenant-1");

        assertEquals(101L, result.getId());
        assertEquals("FORWARD", result.getRelationType());
        assertEquals("NEW_SESSION", result.getTargetMode());
        assertEquals("session-child-a", result.getSourceSessionId());
        assertEquals("msg-1", result.getSourceMessageId());
        assertEquals("session-child-b", result.getTargetSessionId());
        assertEquals("worker-source", result.getSourceWorkerId());
        assertEquals("worker-target", result.getTargetWorkerId());
    }

    @Test
    void findIncomingForwardRelation_rejectsUnownedTargetBeforeRelationLookup() {
        when(resourceAccessService.requireOwnedSession("session-other", "user-1", "tenant-1"))
                .thenThrow(new SecurityException("Resource access denied"));

        SecurityException error = assertThrows(SecurityException.class,
                () -> service.findIncomingForwardRelation("session-other", "user-1", "tenant-1"));

        assertEquals("Resource access denied", error.getMessage());
        verify(sessionRelationRepository, never())
                .findFirstByUserIdAndRelationTypeAndTargetSessionIdOrderByCreatedAtDesc(any(), any(), any());
    }

    private NewForwardFixture stubNewForward(boolean fresh, long relationId) {
        when(taskDispatchFacade.resolveCreateExecutionPlan(any(), any()))
                .thenReturn(resolvedTarget);
        when(resolvedTarget.ownerUserId()).thenReturn("user-1");
        when(resolvedTarget.tenantId()).thenReturn("tenant-1");
        when(resolvedTarget.logicalAgentId()).thenReturn("agent-target");
        when(resolvedTarget.physicalWorkerId()).thenReturn("worker-target");
        when(resolvedTarget.directoryId()).thenReturn("dir-target");
        when(resolvedTarget.model()).thenReturn("gpt-5.4");
        when(resolvedTarget.modelConfigId()).thenReturn("model-config-target");

        com.foggy.navigator.common.entity.WorkingDirectoryEntity directory =
                new com.foggy.navigator.common.entity.WorkingDirectoryEntity();
        directory.setDirectoryId("dir-target");
        directory.setUserId("user-1");
        directory.setTenantId("tenant-1");
        directory.setWorkerId("worker-target");
        directory.setPath("/workspace/target");
        directory.setEnabled(true);
        when(workingDirectoryRepository.findByDirectoryIdAndUserId(
                "dir-target", "user-1")).thenReturn(Optional.of(directory));

        when(forwardScope.clientRequestId()).thenReturn(CLIENT_REQUEST_ID);
        when(forwardCommandFactory.mintForwardScope(any(), any()))
                .thenReturn(forwardScope);
        String targetSessionId =
                SessionForwardTargetSessionReservationService.deriveSessionId(
                        CLIENT_REQUEST_ID, "user-1", "tenant-1");
        when(targetReservationService.reserve(any(), any()))
                .thenReturn(new SessionForwardTargetSessionReservationService.ReservationResult(
                        targetSessionId,
                        SessionForwardTargetSessionReservationService.ReservationDisposition.EXACT_REPLAY));

        DispatchTaskDTO task = DispatchTaskDTO.builder()
                .taskId("task-created")
                .sessionId(targetSessionId)
                .agentId("agent-target")
                .workerId("worker-target")
                .directoryId("dir-target")
                .model("gpt-5.4")
                .modelConfigId("model-config-target")
                .providerType("codex-worker")
                .build();
        when(agentSubmitPipeline.submit(any()))
                .thenReturn(AgentTaskSubmitResult.of(null, task));
        when(forwardCommandFactory.executeForwardScoped(
                any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentTaskSubmitRequest submit = invocation.getArgument(1);
            TrustedNavigatorTaskCreateCommandFactory.ForwardFreshParticipants participants =
                    invocation.getArgument(2);
            @SuppressWarnings("unchecked")
            Supplier<AgentTaskSubmitResult> submission = invocation.getArgument(3);
            submit.setMetadata(Map.of("directoryId", "dir-target"));
            if (fresh) {
                participants.prepareFreshTask();
            }
            AgentTaskSubmitResult result = submission.get();
            if (fresh) {
                participants.completeFreshTask(result.getDispatchTask());
            }
            return result;
        });
        if (fresh) {
            when(outcomeStore.insertFresh(any())).thenAnswer(invocation -> {
                SessionForwardOutcomeStore.OutcomeSpec spec = invocation.getArgument(0);
                return new SessionForwardOutcomeStore.OutcomeSnapshot(
                        relationId,
                        spec,
                        LocalDateTime.of(2026, 8, 4, 1, 0));
            });
        } else {
            when(outcomeStore.requireExactReplay(any())).thenAnswer(invocation -> {
                SessionForwardOutcomeStore.OutcomeSpec spec = invocation.getArgument(0);
                return new SessionForwardOutcomeStore.OutcomeSnapshot(
                        relationId,
                        spec,
                        LocalDateTime.of(2026, 8, 4, 1, 0));
            });
        }
        return new NewForwardFixture(targetSessionId, task);
    }

    private static Supplier<?> callback(
            org.mockito.invocation.InvocationOnMock invocation) {
        return invocation.getArgument(0);
    }

    private record NewForwardFixture(String targetSessionId, DispatchTaskDTO task) {
    }
}
