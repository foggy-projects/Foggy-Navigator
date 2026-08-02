package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.dto.a2a.A2aTaskState;
import com.foggy.navigator.common.dto.a2a.A2aTaskStatus;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.session.agent.pipeline.AgentSubmitPipeline;
import com.foggy.navigator.session.agent.pipeline.AgentTaskSubmitResult;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.OpenApiSessionQueryService;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiRuntimeTaskCreateFacadeTest {

    @Test
    void createSubmitsExactlyOnceAndOwnsDispatchAndSessionSideEffects() {
        Fixture fixture = fixture();
        A2aTask task = A2aTask.builder()
                .id("task-1")
                .metadata(Map.of("sessionId", "session-1", "workerId", "worker-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build();
        when(fixture.submitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(task));

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome =
                fixture.facade.create(command(
                        fixture.facade, launchPlan(), false, "{\"trace\":\"one\"}"));

        assertTrue(outcome.created());
        assertEquals("task-1", outcome.task().getId());
        assertTrue(outcome.task().getContextId().startsWith("ctx-"));
        assertTrue(outcome.metadata().containsKey("optionalRuntimeValue"));
        assertEquals(null, outcome.metadata().get("optionalRuntimeValue"));
        verify(fixture.submitPipeline, times(1)).submit(any(AgentTaskSubmitRequest.class));
        verify(fixture.auditService).taskDispatchRecorded(
                eq(new RuntimeRequestAuditService.AuditHandle("request-1")), any());
        verify(fixture.sessionQueryService).updateClientContextJson(
                outcome.task().getContextId(), "owner-1", "agent-1", "{\"trace\":\"one\"}");
        verify(fixture.sessionService).bindOpenApiSession(
                "tenant-1",
                "app-1",
                "upstream-1",
                outcome.task().getContextId(),
                "session-1",
                "agent-1",
                "task-1",
                "{\"trace\":\"one\"}");
    }

    @Test
    void createUsesOnlyVerifiedCredentialReferencesForTokenPreparationAndBinding() {
        Fixture fixture = fixture();
        when(fixture.taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("owner-1"),
                eq("app-1"),
                eq("upstream-1"),
                eq("skill-1"),
                any(),
                eq("model-1"),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(new BusinessAgentTaskService.PreparedOpenApiTaskScopedToken(
                        "task-token-1",
                        "token-id-1",
                        "worker-1",
                        "lease-1",
                        "pool-1",
                        "LANGGRAPH_BIZ",
                        0,
                        "REQUEST_EXPLICIT_EMPTY",
                        true));
        A2aTask task = A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "session-1", "workerId", "worker-1"))
                .status(A2aTaskStatus.builder().state(A2aTaskState.SUBMITTED).build())
                .build();
        when(fixture.submitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(task));

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome =
                fixture.facade.create(command(fixture.facade, launchPlan(), false, null));

        assertTrue(outcome.created());
        @SuppressWarnings("unchecked")
        Map<String, Object> runtimeContext =
                (Map<String, Object>) outcome.metadata().get("runtimeContext");
        assertEquals("task-token-1", runtimeContext.get("task_scoped_token"));
        verify(fixture.taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant-1", "task-token-1", "task-1", "session-1", "worker-1", "lease-1");
        var selectionCaptor = org.mockito.ArgumentCaptor.forClass(
                BusinessAgentWorkerTaskLaunchRequest.class);
        verify(fixture.taskService).prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("owner-1"),
                eq("app-1"),
                eq("upstream-1"),
                eq("skill-1"),
                any(),
                eq("model-1"),
                selectionCaptor.capture());
        assertEquals("credential-1", selectionCaptor.getValue().getCallerCredentialId());
        assertEquals("access-token-id-1", selectionCaptor.getValue().getCallerAccessTokenId());
    }

    @Test
    void createRejectsUnownedExistingContextBeforeTokenOrSubmit() {
        Fixture fixture = fixture();
        when(fixture.sessionService.getSession(
                "tenant-1", "app-1", "upstream-1", "ctx-existing"))
                .thenThrow(new IllegalArgumentException("business agent session not found: ctx-existing"));
        when(fixture.sessionQueryService.findContextForUser("ctx-existing", "owner-1"))
                .thenReturn(Optional.empty());

        OpenApiRuntimeTaskCreateFacade.PrepareOutcome outcome = fixture.facade.prepare(
                context(launchPlan("ctx-existing"), true));

        assertFalse(outcome.ready());
        assertEquals("business agent session not found: ctx-existing", outcome.rejectionMessage());
        verify(fixture.auditService).askFailed(
                new RuntimeRequestAuditService.AuditHandle("request-1"),
                "CONTEXT_OWNERSHIP_REJECTED");
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedToken(
                any(), any(), any(), any(), any(), any(), nullable(String.class), any());
        verify(fixture.submitPipeline, never()).submit(any());
    }

    @Test
    void createRevokesPreparedTokenBeforePropagatingUnexpectedSubmitFailure() {
        Fixture fixture = fixture();
        when(fixture.taskService.prepareOpenApiTaskScopedToken(
                eq("tenant-1"),
                eq("owner-1"),
                eq("app-1"),
                eq("upstream-1"),
                eq("skill-1"),
                any(),
                eq("model-1"),
                any(BusinessAgentWorkerTaskLaunchRequest.class)))
                .thenReturn(new BusinessAgentTaskService.PreparedOpenApiTaskScopedToken(
                        "task-token-1",
                        "token-id-1",
                        "worker-1",
                        "lease-1",
                        "pool-1",
                        "LANGGRAPH_BIZ",
                        0,
                        "REQUEST_EXPLICIT_EMPTY",
                        true));
        when(fixture.submitPipeline.submit(any()))
                .thenThrow(new UnsupportedOperationException("provider exploded"));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> fixture.facade.create(command(
                        fixture.facade, launchPlan(), false, null)));

        assertEquals("provider exploded", failure.getMessage());
        verify(fixture.submitPipeline, times(1)).submit(any());
        verify(fixture.taskService).revokeOpenApiTaskScopedToken(
                "tenant-1", "task-token-1", "system", "open api task submission failed");
        verify(fixture.auditService).askFailed(
                new RuntimeRequestAuditService.AuditHandle("request-1"),
                "STANDARD_ASK_SUBMIT_FAILED");
    }

    private Fixture fixture() {
        CodingAgentRepository codingAgentRepository = mock(CodingAgentRepository.class);
        CodingAgentEntity codingAgent = new CodingAgentEntity();
        codingAgent.setAgentId("agent-1");
        codingAgent.setTenantId("tenant-1");
        codingAgent.setUserId("owner-1");
        when(codingAgentRepository.findByAgentIdAndTenantId("agent-1", "tenant-1"))
                .thenReturn(Optional.of(codingAgent));
        UnifiedAgentResolver agentResolver = mock(UnifiedAgentResolver.class);
        when(agentResolver.resolveAgent(eq("agent-1"), any()))
                .thenReturn(Optional.of(mock(A2aAgent.class)));
        AgentSubmitPipeline submitPipeline = mock(AgentSubmitPipeline.class);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        RuntimeRequestAuditService auditService = mock(RuntimeRequestAuditService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        OpenApiRuntimeTaskCreateFacade facade = new OpenApiRuntimeTaskCreateFacade(
                codingAgentRepository,
                agentResolver,
                submitPipeline,
                sessionQueryService,
                provider(auditService),
                provider(taskService),
                provider(sessionService));
        return new Fixture(
                facade,
                submitPipeline,
                sessionQueryService,
                auditService,
                taskService,
                sessionService);
    }

    private OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand command(
            OpenApiRuntimeTaskCreateFacade facade,
            OpenApiRuntimeTaskLaunchPlanner.LaunchPlan plan,
            boolean existingContextRequested,
            String clientContextJson) {
        OpenApiRuntimeTaskCreateFacade.PrepareOutcome preparation =
                facade.prepare(context(plan, existingContextRequested));
        assertTrue(preparation.ready());
        return new OpenApiRuntimeTaskCreateFacade.VerifiedCreateCommand(
                preparation.preparedContext(),
                "create task",
                3,
                clientContextJson,
                plan);
    }

    private OpenApiRuntimeTaskCreateFacade.VerifiedCreateContext context(
            OpenApiRuntimeTaskLaunchPlanner.LaunchPlan plan,
            boolean existingContextRequested) {
        return new OpenApiRuntimeTaskCreateFacade.VerifiedCreateContext(
                new OpenApiRuntimeTaskCreateFacade.RuntimeCredentialReference(
                        "tenant-1", "app-1", "credential-1", "access-token-id-1"),
                "upstream-1",
                "agent-1",
                "skill-1",
                plan.context().contextId(),
                existingContextRequested,
                plan.modelResource().modelConfigId(),
                new RuntimeRequestAuditService.AuditHandle("request-1"));
    }

    private OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan() {
        return launchPlan("ctx-1");
    }

    private OpenApiRuntimeTaskLaunchPlanner.LaunchPlan launchPlan(String contextId) {
        OpenApiRuntimeTaskLaunchPlanner.LaunchContext context =
                new OpenApiRuntimeTaskLaunchPlanner.LaunchContext(
                        "tenant-1", "app-1", "upstream-1", "agent-1", "skill-1", contextId);
        var model = new com.foggy.navigator.business.agent.service.A2AgentResourceResolver
                .ResolvedModelResource(
                        "model-1",
                        "model-1",
                        null,
                        LlmModelCategory.GENERAL,
                        "model-variant-1",
                        "REQUESTED_MODEL",
                        "LANGGRAPH_BIZ",
                        "MODEL_CONFIG_GRANT");
        var workerSelection = new OpenApiRuntimeTaskLaunchPlanner.WorkerSelection(
                context,
                "pool-1",
                ResourceOwnerType.CLIENT_APP,
                "app-1",
                "worker-1",
                "LANGGRAPH_BIZ",
                "model-1",
                "model-variant-1",
                "directory-1",
                "/workspace",
                List.of("/workspace"),
                List.of(),
                true,
                List.of());
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("providerType", "LANGGRAPH_BIZ");
        metadata.put("modelConfigId", "model-1");
        metadata.put("workerId", "worker-1");
        metadata.put("requestedToolCount", 0);
        metadata.put("effectiveToolCount", 0);
        metadata.put("toolScopeKind", "NAVIGATOR_BUSINESS_MCP_WRAPPERS");
        metadata.put("toolScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("requestedFunctionCount", 0);
        metadata.put("functionScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("optionalRuntimeValue", null);
        return new OpenApiRuntimeTaskLaunchPlanner.LaunchPlan(
                context,
                null,
                model,
                null,
                false,
                metadata,
                List.of(),
                workerSelection);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private record Fixture(
            OpenApiRuntimeTaskCreateFacade facade,
            AgentSubmitPipeline submitPipeline,
            OpenApiSessionQueryService sessionQueryService,
            RuntimeRequestAuditService auditService,
            BusinessAgentTaskService taskService,
            BusinessAgentSessionService sessionService) {
    }
}
