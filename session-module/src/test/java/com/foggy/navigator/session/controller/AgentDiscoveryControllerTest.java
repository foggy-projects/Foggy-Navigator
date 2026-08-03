package com.foggy.navigator.session.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.session.service.TrustedNavigatorTaskTerminationCommandAdapter;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDiscoveryControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";
    private static final String REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private UnifiedAgentResolver agentResolver;
    @Mock
    private AgentConsultationRepository consultationRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private AgentSubmitPipeline agentSubmitPipeline;
    @Mock
    private SessionTaskResourceAccessService resourceAccessService;
    @Mock
    private TrustedNavigatorTaskTerminationCommandAdapter taskTerminationCommandAdapter;
    @Mock
    private A2aAgent agent;

    private AgentDiscoveryController controller;

    @BeforeEach
    void setUp() {
        controller = new AgentDiscoveryController(
                agentResolver,
                consultationRepository,
                sessionRepository,
                new ObjectMapper(),
                agentSubmitPipeline,
                resourceAccessService,
                taskTerminationCommandAdapter);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void askAgent_submitsA2aSourceAndHeaderBeforeUpdatingParticipation() {
        String clientRequestId = "550e8400-e29b-41d4-a716-446655440000";
        A2aTask task = A2aTask.builder().id("task-1").build();
        SessionEntity session = new SessionEntity();
        when(agentResolver.resolveAgent(eq("agent-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(agent));
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        when(agentSubmitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(task));

        RX<A2aTask> result = controller.askAgent(
                "agent-1", Map.of("question", "help", "sessionId", "session-1"),
                clientRequestId);

        assertSame(task, result.getData());
        InOrder ordered = inOrder(
                resourceAccessService, agentResolver, sessionRepository, agentSubmitPipeline);
        ordered.verify(resourceAccessService)
                .requireOwnedSession("session-1", USER_ID, TENANT_ID);
        ordered.verify(agentResolver).resolveAgent(
                eq("agent-1"),
                argThat(context -> USER_ID.equals(context.getUserId())
                        && TENANT_ID.equals(context.getTenantId())
                        && "A2A".equals(context.getRequestSource())));
        ordered.verify(agentSubmitPipeline).submit(argThat(submitRequest ->
                clientRequestId.equals(submitRequest.getClientRequestId())
                        && submitRequest.getResolveContext() != null
                        && "A2A".equals(submitRequest.getResolveContext().getRequestSource())
                        && submitRequest.getMetadata() != null
                        && Boolean.TRUE.equals(submitRequest.getMetadata().get("tracked"))
                        && "session-1".equals(submitRequest.getMetadata().get("sessionId"))
                        && !submitRequest.getMetadata().containsKey("clientRequestId")
                        && !submitRequest.getMetadata().containsValue(clientRequestId)));
        ordered.verify(sessionRepository).findById("session-1");
        ordered.verify(sessionRepository).save(session);
        assertEquals("[\"agent-1\"]", session.getParticipatingAgentIds());
    }

    @Test
    void askAgent_rejectsUnownedParentBeforeProviderResolutionOrMutation() {
        rejectSession("session-other");

        assertThrows(SecurityException.class, () -> controller.askAgent(
                "agent-1", Map.of("question", "help", "sessionId", "session-other"), null));

        verifyNoInteractions(agentResolver, sessionRepository, agentSubmitPipeline);
    }

    @Test
    void askAgent_conflictDoesNotMutateParticipation() {
        when(agentResolver.resolveAgent(eq("agent-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(agent));
        when(agentSubmitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenThrow(new IllegalStateException("TASK_CREATE_BINDING_CONFLICT"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> controller.askAgent(
                        "agent-1",
                        Map.of("question", "help", "sessionId", "session-1"),
                        "550e8400-e29b-41d4-a716-446655440000"));

        assertEquals("TASK_CREATE_BINDING_CONFLICT", failure.getMessage());
        verifyNoInteractions(sessionRepository);
    }

    @Test
    void askAgent_repeatedSuccessfulResultKeepsParticipationIdempotent() {
        A2aTask recordedTask = A2aTask.builder().id("task-recorded-1").build();
        SessionEntity session = new SessionEntity();
        when(agentResolver.resolveAgent(eq("agent-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(agent));
        when(agentSubmitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(recordedTask));
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(session));
        Map<String, String> body = Map.of("question", "help", "sessionId", "session-1");
        String clientRequestId = "550e8400-e29b-41d4-a716-446655440000";

        controller.askAgent("agent-1", body, clientRequestId);
        controller.askAgent("agent-1", body, clientRequestId);

        verify(sessionRepository, times(2)).findById("session-1");
        verify(sessionRepository).save(session);
        assertEquals("[\"agent-1\"]", session.getParticipatingAgentIds());
    }

    @Test
    void getTaskStatus_authorizesTaskBeforeProviderLookup() {
        A2aTask task = A2aTask.builder().id("task-1").build();
        when(resourceAccessService.requireOwnedTask("task-1", USER_ID, TENANT_ID))
                .thenReturn(ownedTask("task-1", "agent-1"));
        when(agentResolver.resolveAgent(eq("agent-1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(agent));
        when(agent.getTask("task-1")).thenReturn(Optional.of(task));

        RX<A2aTask> result = controller.getTaskStatus("agent-1", "task-1");

        assertSame(task, result.getData());
        InOrder ordered = inOrder(resourceAccessService, agentResolver, agent);
        ordered.verify(resourceAccessService).requireOwnedTask("task-1", USER_ID, TENANT_ID);
        ordered.verify(agentResolver).resolveAgent(
                eq("agent-1"),
                argThat(context -> USER_ID.equals(context.getUserId())
                        && TENANT_ID.equals(context.getTenantId())));
        ordered.verify(agent).getTask("task-1");
    }

    @Test
    void getTaskStatus_rejectsUnownedTaskBeforeProviderLookup() {
        rejectTask("task-other");

        assertThrows(SecurityException.class,
                () -> controller.getTaskStatus("agent-1", "task-other"));

        verifyNoInteractions(agentResolver, agent);
    }

    @Test
    void cancelTask_rejectsUnownedTaskBeforeProviderMutation() {
        rejectTask("task-other");

        assertThrows(SecurityException.class,
                () -> controller.cancelTask("agent-1", "task-other", REQUEST_ID));

        verifyNoInteractions(agentResolver, agent, taskTerminationCommandAdapter);
    }

    @Test
    void cancelTask_declaresOptionalClientRequestIdHeader() throws Exception {
        RequestHeader header = AgentDiscoveryController.class
                .getMethod("cancelTask", String.class, String.class, String.class)
                .getParameters()[2]
                .getAnnotation(RequestHeader.class);

        assertEquals("X-Navigator-Client-Request-Id", header.value());
        assertEquals(false, header.required());
    }

    @Test
    void cancelTask_authorizesTaskBeforeTrustedA2aAdapter() {
        when(resourceAccessService.requireOwnedTask("task-1", USER_ID, TENANT_ID))
                .thenReturn(ownedTask("task-1", "agent-1"));
        when(taskTerminationCommandAdapter.terminateA2aTask(
                "agent-1", "task-1", REQUEST_ID))
                .thenReturn(acceptedTermination());

        RX<String> result = controller.cancelTask(
                "agent-1", "task-1", REQUEST_ID);

        assertEquals("Task cancel requested", result.getData());
        InOrder ordered = inOrder(resourceAccessService, taskTerminationCommandAdapter);
        ordered.verify(resourceAccessService).requireOwnedTask("task-1", USER_ID, TENANT_ID);
        ordered.verify(taskTerminationCommandAdapter).terminateA2aTask(
                "agent-1", "task-1", REQUEST_ID);
        verifyNoInteractions(agent);
    }

    @Test
    void cancelTask_keepsWordingForAbsentBlankAndTerminalResults() {
        when(resourceAccessService.requireOwnedTask("task-1", USER_ID, TENANT_ID))
                .thenReturn(ownedTask("task-1", "agent-1"));
        when(taskTerminationCommandAdapter.terminateA2aTask(
                eq("agent-1"), eq("task-1"), any()))
                .thenReturn(
                        acceptedTermination(),
                        new TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult(
                                "TASK_ALREADY_TERMINAL_COMPLETED", "COMPLETED"));

        RX<String> absent = controller.cancelTask("agent-1", "task-1", null);
        RX<String> blank = controller.cancelTask("agent-1", "task-1", "  ");

        assertEquals("Task cancel requested", absent.getData());
        assertEquals("Task cancel requested", blank.getData());
        verify(taskTerminationCommandAdapter).terminateA2aTask(
                "agent-1", "task-1", null);
        verify(taskTerminationCommandAdapter).terminateA2aTask(
                "agent-1", "task-1", "  ");
    }

    @Test
    void cancelTask_rejectsAgentRouteMismatchBeforeProviderLookup() {
        when(resourceAccessService.requireOwnedTask("task-1", USER_ID, TENANT_ID))
                .thenReturn(ownedTask("task-1", "agent-owner"));

        assertThrows(SecurityException.class,
                () -> controller.cancelTask("agent-other", "task-1", REQUEST_ID));

        verifyNoInteractions(agentResolver, agent, taskTerminationCommandAdapter);
    }

    @Test
    void cancelTask_rejectsMissingPersistedAgentRouteBeforeProviderLookup() {
        when(resourceAccessService.requireOwnedTask("task-1", USER_ID, TENANT_ID))
                .thenReturn(ownedTask("task-1", null));

        assertThrows(SecurityException.class,
                () -> controller.cancelTask("agent-requested", "task-1", REQUEST_ID));

        verifyNoInteractions(agentResolver, agent, taskTerminationCommandAdapter);
    }

    @Test
    void listConsultations_authorizesParentBeforeReadingChildren() {
        AgentConsultationEntity consultation = new AgentConsultationEntity();
        consultation.setId("consultation-1");
        when(consultationRepository.findBySessionIdOrderByCreatedAtAsc("session-1"))
                .thenReturn(List.of(consultation));

        RX<List<AgentConsultationEntity>> result = controller.listConsultations("session-1");

        assertEquals("consultation-1", result.getData().get(0).getId());
        InOrder ordered = inOrder(resourceAccessService, consultationRepository);
        ordered.verify(resourceAccessService)
                .requireOwnedSession("session-1", USER_ID, TENANT_ID);
        ordered.verify(consultationRepository)
                .findBySessionIdOrderByCreatedAtAsc("session-1");
    }

    @Test
    void listConsultations_rejectsUnownedParentBeforeRepositoryQuery() {
        rejectSession("session-other");

        assertThrows(SecurityException.class,
                () -> controller.listConsultations("session-other"));

        verifyNoInteractions(consultationRepository);
    }

    private void rejectSession(String sessionId) {
        doThrow(new SecurityException("session resource is not owned by current user"))
                .when(resourceAccessService)
                .requireOwnedSession(sessionId, USER_ID, TENANT_ID);
    }

    private void rejectTask(String taskId) {
        doThrow(new SecurityException("task resource is not owned by current user"))
                .when(resourceAccessService)
                .requireOwnedTask(taskId, USER_ID, TENANT_ID);
    }

    private SessionTaskEntity ownedTask(String taskId, String agentId) {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId(taskId);
        task.setAgentId(agentId);
        return task;
    }

    private static TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult
    acceptedTermination() {
        return new TrustedNavigatorTaskTerminationCommandAdapter.TerminationResult(
                "TERMINATION_REQUEST_ACCEPTED", null);
    }
}
