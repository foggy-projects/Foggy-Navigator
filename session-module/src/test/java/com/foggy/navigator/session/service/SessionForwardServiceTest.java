package com.foggy.navigator.session.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionRelationEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.dto.SessionForwardCreateRequest;
import com.foggy.navigator.session.dto.SessionForwardCreateResponse;
import com.foggy.navigator.session.repository.SessionMessageRepository;
import com.foggy.navigator.session.repository.SessionRelationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionForwardServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private SessionMessageRepository sessionMessageRepository;
    @Mock
    private SessionRelationRepository sessionRelationRepository;
    @Mock
    private SessionTaskRepository sessionTaskRepository;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private TaskDispatchFacade taskDispatchFacade;
    @Mock
    private AgentSubmitPipeline agentSubmitPipeline;

    private SessionForwardService service;

    @BeforeEach
    void setUp() {
        service = new SessionForwardService(
                sessionRepository,
                sessionMessageRepository,
                sessionRelationRepository,
                sessionTaskRepository,
                workingDirectoryRepository,
                sessionManager,
                taskDispatchFacade,
                agentSubmitPipeline
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
        latestTask.setModel("gpt-5.4-mini");
        latestTask.setModelConfigId("cfg-target");

        DispatchTaskDTO resumedTask = DispatchTaskDTO.builder()
                .taskId("task-resumed")
                .sessionId("session-child")
                .workerId("worker-target")
                .directoryId("dir-target")
                .model("gpt-5.4-mini")
                .modelConfigId("cfg-target")
                .providerType("codex-worker")
                .build();

        when(sessionRepository.findByIdAndUserId("session-source", "user-1")).thenReturn(Optional.of(sourceSession));
        when(sessionRepository.findByIdAndUserId("session-child", "user-1")).thenReturn(Optional.of(targetSession));
        when(sessionMessageRepository.findById("msg-1")).thenReturn(Optional.of(sourceMessage));
        when(sessionTaskRepository.findByTaskIdAndUserId("task-target-latest", "user-1")).thenReturn(Optional.of(latestTask));
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
        assertEquals("gpt-5.4-mini", dispatchRequest.getModel());
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

        verify(sessionManager, never()).createSession(any());
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

        SessionMessageEntity sourceMessage = new SessionMessageEntity();
        sourceMessage.setId("msg-1");
        sourceMessage.setSessionId("session-source");
        sourceMessage.setRole("ASSISTANT");
        sourceMessage.setContent("原始回复");
        sourceMessage.setCreatedAt(LocalDateTime.now());

        when(sessionRepository.findByIdAndUserId("session-source", "user-1")).thenReturn(Optional.of(sourceSession));
        when(sessionRepository.findByIdAndUserId("session-other", "user-1")).thenReturn(Optional.of(unrelatedTarget));
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
        verify(sessionTaskRepository, never()).findBySessionIdOrderByCreatedAtDesc(any());
    }
}
