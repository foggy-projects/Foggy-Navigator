package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.AgentConsultationEntity;
import com.foggy.navigator.common.form.SharedAskForm;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.repository.AgentConsultationRepository;
import com.foggy.navigator.session.service.ScopedSharedTaskCreateCommandAdapter;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedAskControllerTest {

    private static final String CLIENT_REQUEST_ID =
            "550e8400-e29b-41d4-a716-446655440000";

    @Mock private UnifiedAgentResolver agentResolver;
    @Mock private AgentConsultationRepository consultationRepository;
    @Mock private AgentSubmitPipeline agentSubmitPipeline;
    @Mock private ScopedSharedTaskCreateCommandAdapter scopedAdapter;
    @Mock private ScopedSharedTaskCreateCommandAdapter.SharedCommandScope scope;
    @Mock private A2aAgent agent;

    private SharedAskController controller;

    @BeforeEach
    void setUp() {
        controller = new SharedAskController(
                agentResolver,
                consultationRepository,
                agentSubmitPipeline,
                scopedAdapter);
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    @Test
    void askFreshUsesScopeIdentityExplicitMetadataAndRecordsConsultation() {
        AgentResolveContext sharedContext = stubScope(CLIENT_REQUEST_ID);
        SharedAskForm form = form();
        form.setSystemPrompt("explicit system");
        form.setFirstMsg("first");
        A2aTask task = A2aTask.builder().id("task-1").build();
        DispatchTaskDTO fresh = DispatchTaskDTO.builder()
                .taskId("task-1")
                .agentId("agent-1")
                .status("COMPLETED")
                .resultText("done")
                .build();
        stubFreshExecution(task, fresh);
        CurrentUser ambient = CurrentUser.builder()
                .userId("jwt-user")
                .tenantId("jwt-tenant")
                .roles("SUPER_ADMIN")
                .build();
        UserContext.setCurrentUser(ambient);
        when(agentSubmitPipeline.submit(any())).thenAnswer(invocation -> {
            assertNull(UserContext.getCurrentUser());
            return AgentTaskSubmitResult.of(task, fresh);
        });

        RX<A2aTask> result = controller.ask("shk-1", CLIENT_REQUEST_ID, form);

        assertTrue(result.isOk());
        assertSame(task, result.getData());
        assertEquals("ctx-1", task.getContextId());
        assertSame(ambient, UserContext.getCurrentUser());
        verify(scopedAdapter).mintScope("shk-1", CLIENT_REQUEST_ID);
        verify(agentResolver).resolveAgent("agent-1", sharedContext);

        ArgumentCaptor<AgentTaskSubmitRequest> requestCaptor =
                ArgumentCaptor.forClass(AgentTaskSubmitRequest.class);
        verify(scopedAdapter).executeScoped(
                same(scope), requestCaptor.capture(), any(), any());
        AgentTaskSubmitRequest request = requestCaptor.getValue();
        verify(agentSubmitPipeline).submit(same(request));
        assertEquals(CLIENT_REQUEST_ID, request.getClientRequestId());
        assertEquals("agent-1", request.getAgentId());
        assertSame(sharedContext, request.getResolveContext());
        assertNull(request.getMaxTurns());
        assertEquals("hello", request.getPrompt());
        assertEquals("ctx-1", request.getContextId());
        assertEquals("alias-1", request.getContextAlias());
        assertEquals("explicit system", request.getMetadata().get("systemPrompt"));
        assertEquals("first", request.getMetadata().get("firstMsg"));
        assertFalse(request.getMetadata().containsKey("maxTurns"));
        assertEquals(request.getMetadata(), request.getMessage().getMetadata());

        ArgumentCaptor<AgentConsultationEntity> consultationCaptor =
                ArgumentCaptor.forClass(AgentConsultationEntity.class);
        verify(consultationRepository).save(consultationCaptor.capture());
        AgentConsultationEntity consultation = consultationCaptor.getValue();
        assertEquals("shared-key-1", consultation.getSessionId());
        assertEquals("owner-1", consultation.getUserId());
        assertEquals("agent-1", consultation.getTargetAgentId());
        assertEquals("Agent 1", consultation.getTargetAgentName());
        assertEquals("hello", consultation.getQuestion());
        assertEquals("done", consultation.getAnswer());
        assertEquals("COMPLETED", consultation.getStatus());
        assertEquals("ctx-1", consultation.getContextId());
        assertEquals("SHARED", consultation.getSource());
        assertEquals("key-1", consultation.getSharingKeyId());
        assertTrue(consultation.getDurationMs() >= 0L);
    }

    @Test
    void askRecordedReplaySkipsPipelineAndConsultationButKeepsResponseFallback() {
        stubScope(CLIENT_REQUEST_ID);
        SharedAskForm form = form();
        A2aTask replay = A2aTask.builder().id("task-recorded").build();
        DispatchTaskDTO durable = DispatchTaskDTO.builder()
                .taskId("task-recorded")
                .contextId(null)
                .build();
        when(scopedAdapter.executeScoped(
                same(scope), any(), any(), any()))
                .thenReturn(AgentTaskSubmitResult.of(replay, durable));

        RX<A2aTask> result = controller.ask("shk-1", CLIENT_REQUEST_ID, form);

        assertTrue(result.isOk());
        assertSame(replay, result.getData());
        assertEquals("ctx-1", replay.getContextId());
        verifyNoInteractions(agentSubmitPipeline, consultationRepository);
    }

    @Test
    void askLockedAdmissionRejectionPreservesLegacyRxWithoutFreshEffects() {
        stubScope(CLIENT_REQUEST_ID);
        SharedAskForm form = form();
        ScopedSharedTaskCreateCommandAdapter.SharedCommandAdmissionRejectedException rejection =
                mock(ScopedSharedTaskCreateCommandAdapter
                        .SharedCommandAdmissionRejectedException.class);
        when(rejection.getMessage()).thenReturn("Daily call limit exceeded (1)");
        when(scopedAdapter.executeScoped(
                same(scope), any(), any(), any()))
                .thenThrow(rejection);

        RX<A2aTask> result = controller.ask("shk-1", CLIENT_REQUEST_ID, form);

        assertEquals(RX.COMMON_ERROR, result.getCode());
        assertEquals(RX.A_COMMON, result.getExCode());
        assertEquals("Daily call limit exceeded (1)", result.getMsg());
        assertNull(result.getData());
        verifyNoInteractions(agentSubmitPipeline, consultationRepository);
    }

    @Test
    void askDoesNotMisclassifyProviderIllegalArgumentAndAlwaysRestoresAmbientUser() {
        stubScope(CLIENT_REQUEST_ID);
        SharedAskForm form = form();
        CurrentUser ambient = CurrentUser.builder()
                .userId("jwt-user")
                .tenantId("jwt-tenant")
                .build();
        UserContext.setCurrentUser(ambient);
        when(scopedAdapter.executeScoped(
                same(scope), any(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Supplier<AgentTaskSubmitResult> submission = invocation.getArgument(3);
                    return submission.get();
                });
        when(agentSubmitPipeline.submit(any()))
                .thenThrow(new IllegalArgumentException("provider request invalid"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> controller.ask("shk-1", CLIENT_REQUEST_ID, form));

        assertEquals("provider request invalid", failure.getMessage());
        assertSame(ambient, UserContext.getCurrentUser());
        verifyNoInteractions(consultationRepository);
    }

    @Test
    void askAgentUnavailableStopsBeforeScopedPipeline() {
        AgentResolveContext sharedContext = stubScopeOnly(null);
        when(agentResolver.resolveAgent("agent-1", sharedContext))
                .thenReturn(Optional.empty());

        RX<A2aTask> result = controller.ask("shk-1", null, form());

        assertEquals("Shared agent not available", result.getMsg());
        assertEquals(RX.A_COMMON, result.getExCode());
        verify(scopedAdapter, never()).executeScoped(any(), any(), any(), any());
        verifyNoInteractions(agentSubmitPipeline, consultationRepository, agent);
    }

    @Test
    void askQuestionMintAndOwnerFailuresKeepPreEffectBoundaries() {
        SharedAskForm blank = form();
        blank.setQuestion(" ");
        RX<A2aTask> blankResult = controller.ask("shk-1", null, blank);
        assertEquals("question is required", blankResult.getMsg());
        verifyNoInteractions(scopedAdapter);

        when(scopedAdapter.mintScope("invalid", null))
                .thenThrow(new IllegalArgumentException("Invalid sharing key"));
        RX<A2aTask> invalidResult = controller.ask("invalid", null, form());
        assertEquals("Invalid sharing key", invalidResult.getMsg());
        assertEquals(RX.A_COMMON, invalidResult.getExCode());

        CurrentUser ambient = CurrentUser.builder()
                .userId("jwt-user")
                .tenantId("jwt-tenant")
                .build();
        UserContext.setCurrentUser(ambient);
        when(scopedAdapter.mintScope("owner-drift", null))
                .thenThrow(new SecurityException("shared resource is not accessible"));
        SecurityException ownerFailure = assertThrows(
                SecurityException.class,
                () -> controller.ask("owner-drift", null, form()));
        assertEquals("shared resource is not accessible", ownerFailure.getMessage());
        assertSame(ambient, UserContext.getCurrentUser());
        verifyNoInteractions(agentResolver, agentSubmitPipeline, consultationRepository, agent);
    }

    @Test
    void askConsultationSaveFailureRemainsBestEffort() {
        stubScope(CLIENT_REQUEST_ID);
        SharedAskForm form = form();
        A2aTask task = A2aTask.builder().id("task-1").build();
        DispatchTaskDTO fresh = DispatchTaskDTO.builder()
                .taskId("task-1")
                .status("COMPLETED")
                .build();
        stubFreshExecution(task, fresh);
        when(consultationRepository.save(any()))
                .thenThrow(new IllegalStateException("consultation store unavailable"));

        RX<A2aTask> result = controller.ask("shk-1", CLIENT_REQUEST_ID, form);

        assertTrue(result.isOk());
        assertEquals("task-1", result.getData().getId());
        verify(consultationRepository).save(any());
    }

    private AgentResolveContext stubScope(String suppliedClientRequestId) {
        AgentResolveContext context = stubScopeOnly(suppliedClientRequestId);
        when(agentResolver.resolveAgent("agent-1", context))
                .thenReturn(Optional.of(agent));
        when(agent.getAgentCard()).thenReturn(A2aAgentCard.builder()
                .id("agent-1")
                .name("Agent 1")
                .build());
        return context;
    }

    private AgentResolveContext stubScopeOnly(String suppliedClientRequestId) {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId("owner-1")
                .tenantId("tenant-1")
                .requestSource("SHARED_API")
                .build();
        when(scopedAdapter.mintScope("shk-1", suppliedClientRequestId))
                .thenReturn(scope);
        lenient().when(scope.sharingKeyId()).thenReturn("key-1");
        lenient().when(scope.ownerUserId()).thenReturn("owner-1");
        lenient().when(scope.agentId()).thenReturn("agent-1");
        lenient().when(scope.clientRequestId()).thenReturn(CLIENT_REQUEST_ID);
        lenient().when(scope.newResolveContext()).thenReturn(context);
        return context;
    }

    private void stubFreshExecution(A2aTask task, DispatchTaskDTO fresh) {
        when(scopedAdapter.executeScoped(
                same(scope), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertNull(UserContext.getCurrentUser());
                    ScopedSharedTaskCreateCommandAdapter.FreshParticipants participants =
                            invocation.getArgument(2);
                    @SuppressWarnings("unchecked")
                    Supplier<AgentTaskSubmitResult> submission = invocation.getArgument(3);
                    participants.prepareFreshTask();
                    AgentTaskSubmitResult result = submission.get();
                    participants.completeFreshTask(fresh);
                    return result;
                });
        lenient().when(agentSubmitPipeline.submit(any()))
                .thenReturn(AgentTaskSubmitResult.of(task, fresh));
    }

    private SharedAskForm form() {
        SharedAskForm form = new SharedAskForm();
        form.setQuestion("hello");
        form.setContextId("ctx-1");
        form.setContextAlias("alias-1");
        return form;
    }
}
