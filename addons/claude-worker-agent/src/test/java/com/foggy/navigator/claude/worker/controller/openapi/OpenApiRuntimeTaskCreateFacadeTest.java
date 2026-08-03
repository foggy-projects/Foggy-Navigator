package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.BusinessAgentSessionService;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.business.agent.service.RuntimeRequestAuditService;
import com.foggy.navigator.business.agent.service.TerminalTaskBindingException;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
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
import com.foggy.navigator.session.service.ScopedOpenApiTaskCreateCommandAdapter;
import com.foggy.navigator.session.service.TaskDispatchRequest;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiRuntimeTaskCreateFacadeTest {

    private static final String REQUEST_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void createScopesAdmissionDispatchAndSessionSideEffectsExactlyOnceWithoutLauncher() {
        Fixture fixture = fixture();
        A2aTask task = submittedTask(A2aTaskState.SUBMITTED);
        when(fixture.submitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(task));

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command(fixture.facade, launchPlan(), false, "{\"trace\":\"one\"}"));

        assertTrue(outcome.created());
        assertEquals("task-1", outcome.task().getId());
        assertEquals(REQUEST_ID, fixture.submittedRequest.get().getClientRequestId());
        assertEquals("worker-1", outcome.metadata().get("workerId"));
        assertEquals("NOT_ISSUED", outcome.metadata().get("taskTokenStatus"));
        verify(fixture.scopedAdapter, times(1)).executeScoped(any(), any(), any(), any());
        verify(fixture.submitPipeline, times(1)).submit(any());
        verify(fixture.auditService, times(1)).taskAdmissionRecorded(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)), any());
        verify(fixture.auditService, times(1)).taskDispatchRecorded(
                eq(new RuntimeRequestAuditService.AuditHandle(REQUEST_ID)), any());
        verify(fixture.sessionQueryService).updateClientContextJson(
                "ctx-1", "owner-1", "agent-1", "{\"trace\":\"one\"}");
        verify(fixture.sessionService).bindOpenApiSession(
                "tenant-1", "app-1", "upstream-1", "ctx-1", "session-1",
                "agent-1", "task-1", "{\"trace\":\"one\"}");
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), nullable(String.class), any(), any());
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedToken(
                any(), any(), any(), any(), any(), any(), nullable(String.class), any());
    }

    @Test
    void createUsesImmutablePreflightAndInjectsTokenOnlyIntoCanonicalMetadata() {
        Fixture fixture = fixture();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                stubPreflight(fixture, "worker-1", "model-1", "upstream-system-1");
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                eq("tenant-1"), eq("owner-1"), eq("app-1"), eq("upstream-1"),
                eq("skill-1"), eq("ctx-1"), eq("model-1"),
                any(BusinessAgentWorkerTaskLaunchRequest.class), eq(preflight)))
                .thenReturn(preparedToken());
        when(fixture.submitPipeline.submit(any(AgentTaskSubmitRequest.class)))
                .thenReturn(AgentTaskSubmitResult.of(submittedTask(A2aTaskState.SUBMITTED)));

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command(fixture.facade, launchPlan(), false, null));

        assertTrue(outcome.created());
        TaskDispatchRequest canonical = fixture.canonicalRequest.get();
        AgentTaskSubmitRequest submitted = fixture.submittedRequest.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> canonicalRuntime =
                (Map<String, Object>) canonical.getMetadata().get("runtimeContext");
        @SuppressWarnings("unchecked")
        Map<String, Object> submittedRuntime =
                (Map<String, Object>) submitted.getMetadata().get("runtimeContext");
        assertEquals("task-token-1", canonicalRuntime.get("task_scoped_token"));
        assertEquals("sentinel", canonicalRuntime.get("existing"));
        assertFalse(submittedRuntime.containsKey("task_scoped_token"));
        assertFalse(submitted.getMessage().getMetadata().toString().contains("task-token-1"));
        assertNotSame(canonicalRuntime, submittedRuntime);
        assertFalse(outcome.metadata().containsKey("runtimeContext"));
        assertFalse(outcome.metadata().containsKey("workerLeaseId"));
        assertEquals("ACTIVE", outcome.metadata().get("taskTokenStatus"));
        assertEquals(0, outcome.metadata().get("effectiveFunctionCount"));

        var selectionCaptor = org.mockito.ArgumentCaptor.forClass(
                BusinessAgentWorkerTaskLaunchRequest.class);
        verify(fixture.taskService).resolveOpenApiTaskWorkerPreflight(
                eq("tenant-1"), eq("owner-1"), eq("app-1"), eq("upstream-1"),
                eq("skill-1"), eq("ctx-1"), eq("model-1"), selectionCaptor.capture());
        assertEquals("credential-1", selectionCaptor.getValue().getCallerCredentialId());
        assertEquals("access-token-id-1", selectionCaptor.getValue().getCallerAccessTokenId());
        verify(fixture.taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                "tenant-1", "task-token-1", "task-1", "session-1", "worker-1", "lease-1");
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedToken(
                any(), any(), any(), any(), any(), any(), nullable(String.class), any());

        var order = inOrder(
                fixture.taskService,
                fixture.scopedAdapter,
                fixture.auditService,
                fixture.submitPipeline,
                fixture.sessionService);
        order.verify(fixture.taskService).resolveOpenApiTaskWorkerPreflight(
                any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(fixture.scopedAdapter).executeScoped(any(), any(), any(), any());
        order.verify(fixture.auditService).taskAdmissionRecorded(any(), any());
        order.verify(fixture.taskService).prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(fixture.submitPipeline).submit(any());
        order.verify(fixture.taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any());
        order.verify(fixture.auditService).taskDispatchRecorded(any(), any());
        order.verify(fixture.sessionService).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), nullable(String.class));
    }

    @Test
    void recordedReplayHydratesWithoutFreshParticipantMutationOrResponseOverlay() {
        Fixture fixture = fixture();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                stubPreflight(fixture, "worker-1", "model-1", "upstream-system-1");
        A2aTask replay = submittedTask(A2aTaskState.SUBMITTED);
        doAnswer(invocation -> replay)
                .when(fixture.scopedAdapter).executeScoped(any(), any(), any(), any());

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command(fixture.facade, launchPlan(), false, "{\"trace\":\"replay\"}"));

        assertTrue(outcome.created());
        assertTrue(outcome.metadata().isEmpty());
        verify(fixture.taskService).resolveOpenApiTaskWorkerPreflight(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.taskService, never()).prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), eq(preflight));
        verify(fixture.auditService, never()).taskAdmissionRecorded(any(), any());
        verify(fixture.auditService, never()).taskDispatchRecorded(any(), any());
        verify(fixture.submitPipeline, never()).submit(any());
        verify(fixture.sessionQueryService, never()).updateClientContextJson(any(), any(), any(), any());
        verify(fixture.sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.taskService, never()).revokeOpenApiTaskScopedToken(
                any(), any(), any(), any());
    }

    @Test
    void createRejectsUnownedExistingContextBeforePreflightOrScope() {
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
                new RuntimeRequestAuditService.AuditHandle(REQUEST_ID),
                "CONTEXT_OWNERSHIP_REJECTED");
        verify(fixture.taskService, never()).resolveOpenApiTaskWorkerPreflight(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.scopedAdapter, never()).executeScoped(any(), any(), any(), any());
        verify(fixture.submitPipeline, never()).submit(any());
    }

    @Test
    void postPermitProviderFailureDoesNotCompensateMarkFailedOrRetry() {
        Fixture fixture = fixture();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                stubPreflight(fixture, "worker-1", "model-1", "upstream-system-1");
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), eq(preflight)))
                .thenReturn(preparedToken());
        when(fixture.submitPipeline.submit(any()))
                .thenThrow(new UnsupportedOperationException("provider exploded"));

        UnsupportedOperationException failure = assertThrows(
                UnsupportedOperationException.class,
                () -> fixture.facade.create(command(fixture.facade, launchPlan(), false, null)));

        assertEquals("provider exploded", failure.getMessage());
        verify(fixture.submitPipeline, times(1)).submit(any());
        verify(fixture.taskService, never()).revokeOpenApiTaskScopedToken(
                any(), any(), any(), any());
        verify(fixture.auditService, never()).askFailed(any(), any());
        verify(fixture.auditService, never()).taskDispatchRecorded(any(), any());
        verify(fixture.sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalBindingRaceUsesCommittedRevocationWithoutSecondRevokeOrRedispatch() {
        Fixture fixture = fixture();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                stubPreflight(fixture, "worker-1", "model-1", "upstream-system-1");
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), eq(preflight)))
                .thenReturn(preparedToken());
        when(fixture.submitPipeline.submit(any()))
                .thenReturn(AgentTaskSubmitResult.of(submittedTask(A2aTaskState.SUBMITTED)));
        doAnswer(invocation -> {
            throw new TerminalTaskBindingException("terminal race");
        }).when(fixture.taskService).bindOpenApiTaskScopedTokenToWorkerTask(
                any(), any(), any(), any(), any(), any());

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command(fixture.facade, launchPlan(), false, null));

        assertTrue(outcome.created());
        assertEquals("REVOKED", outcome.metadata().get("taskTokenStatus"));
        verify(fixture.submitPipeline, times(1)).submit(any());
        verify(fixture.taskService, never()).revokeOpenApiTaskScopedToken(
                any(), any(), any(), any());
        verify(fixture.auditService, times(1)).taskDispatchRecorded(any(), any());
    }

    @Test
    void immediateTerminalRevokeFailureStopsCompletionWithoutRetryOrFalseSuccess() {
        Fixture fixture = fixture();
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                stubPreflight(fixture, "worker-1", "model-1", "upstream-system-1");
        when(fixture.taskService.prepareOpenApiTaskScopedTokenAfterPreflight(
                any(), any(), any(), any(), any(), any(), any(), any(), eq(preflight)))
                .thenReturn(preparedToken());
        when(fixture.submitPipeline.submit(any()))
                .thenReturn(AgentTaskSubmitResult.of(submittedTask(A2aTaskState.FAILED)));
        org.mockito.Mockito.doThrow(new IllegalStateException("terminal revoke failed"))
                .when(fixture.taskService)
                .revokeOpenApiTaskScopedToken(
                        "tenant-1",
                        "task-token-1",
                        "system",
                        "open api task returned terminal after submission");

        OpenApiRuntimeTaskCreateFacade.CreateOutcome outcome = fixture.facade.create(
                command(fixture.facade, launchPlan(), false, null));

        assertFalse(outcome.created());
        assertEquals("terminal revoke failed", outcome.rejectionMessage());
        assertTrue(outcome.metadata().isEmpty());
        verify(fixture.submitPipeline, times(1)).submit(any());
        verify(fixture.taskService, times(1)).revokeOpenApiTaskScopedToken(
                "tenant-1",
                "task-token-1",
                "system",
                "open api task returned terminal after submission");
        verify(fixture.auditService, never()).taskDispatchRecorded(any(), any());
        verify(fixture.sessionQueryService, never()).updateClientContextJson(
                any(), any(), any(), any());
        verify(fixture.sessionService, never()).bindOpenApiSession(
                any(), any(), any(), any(), any(), any(), any(), any());
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
        ScopedOpenApiTaskCreateCommandAdapter scopedAdapter =
                mock(ScopedOpenApiTaskCreateCommandAdapter.class);
        AtomicReference<AgentTaskSubmitRequest> submittedRequest = new AtomicReference<>();
        AtomicReference<TaskDispatchRequest> canonicalRequest = new AtomicReference<>();
        installFreshScopedAdapterHarness(
                scopedAdapter, submittedRequest, canonicalRequest);
        OpenApiSessionQueryService sessionQueryService = mock(OpenApiSessionQueryService.class);
        RuntimeRequestAuditService auditService = mock(RuntimeRequestAuditService.class);
        BusinessAgentTaskService taskService = mock(BusinessAgentTaskService.class);
        BusinessAgentSessionService sessionService = mock(BusinessAgentSessionService.class);
        OpenApiRuntimeTaskCreateFacade facade = new OpenApiRuntimeTaskCreateFacade(
                codingAgentRepository,
                agentResolver,
                submitPipeline,
                scopedAdapter,
                sessionQueryService,
                provider(auditService),
                provider(taskService),
                provider(sessionService));
        return new Fixture(
                facade,
                scopedAdapter,
                submitPipeline,
                sessionQueryService,
                auditService,
                taskService,
                sessionService,
                submittedRequest,
                canonicalRequest);
    }

    @SuppressWarnings("unchecked")
    private void installFreshScopedAdapterHarness(
            ScopedOpenApiTaskCreateCommandAdapter adapter,
            AtomicReference<AgentTaskSubmitRequest> submittedRequest,
            AtomicReference<TaskDispatchRequest> canonicalRequest) {
        when(adapter.executeScoped(any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentTaskSubmitRequest expected = invocation.getArgument(1);
            ScopedOpenApiTaskCreateCommandAdapter.FreshParticipants participants =
                    invocation.getArgument(2);
            Supplier<A2aTask> submission = invocation.getArgument(3);
            submittedRequest.set(expected);
            TaskDispatchRequest canonical = toCanonicalRequest(expected);
            canonicalRequest.set(canonical);
            participants.prepare(canonical);

            Map<String, Object> originalMetadata = expected.getMetadata();
            A2aMessage originalMessage = expected.getMessage();
            A2aMessage providerMessage = copyMessage(originalMessage, canonical.getMetadata());
            expected.setMetadata(canonical.getMetadata());
            expected.setMessage(providerMessage);
            A2aTask task;
            try {
                task = submission.get();
            } finally {
                expected.setMetadata(originalMetadata);
                expected.setMessage(originalMessage);
            }
            participants.complete(canonical, toDispatchTask(task, canonical));
            return task;
        });
    }

    private TaskDispatchRequest toCanonicalRequest(AgentTaskSubmitRequest request) {
        return TaskDispatchRequest.builder()
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .sessionId(request.getSessionId())
                .workerId(request.getWorkerId())
                .prompt(request.getPrompt())
                .cwd(request.getCwd())
                .directoryId(request.getDirectoryId())
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .maxTurns(request.getMaxTurns())
                .contextId(request.getContextId())
                .metadata(request.getMetadata() == null
                        ? null
                        : new LinkedHashMap<>(request.getMetadata()))
                .build();
    }

    private A2aMessage copyMessage(A2aMessage source, Map<String, Object> metadata) {
        return A2aMessage.builder()
                .role(source.getRole())
                .parts(source.getParts())
                .taskId(source.getTaskId())
                .contextId(source.getContextId())
                .contextAlias(source.getContextAlias())
                .metadata(metadata == null ? null : new LinkedHashMap<>(metadata))
                .build();
    }

    private DispatchTaskDTO toDispatchTask(A2aTask task, TaskDispatchRequest request) {
        String workerId = task != null && task.getMetadata() != null
                ? stringValue(task.getMetadata().get("workerId"))
                : null;
        String sessionId = task != null && task.getMetadata() != null
                ? stringValue(task.getMetadata().get("sessionId"))
                : null;
        return DispatchTaskDTO.builder()
                .taskId(task != null ? task.getId() : null)
                .sessionId(sessionId != null ? sessionId : task != null ? task.getContextId() : null)
                .contextId(task != null ? task.getContextId() : null)
                .workerId(workerId != null ? workerId : request.getWorkerId())
                .agentId(request.getAgentId())
                .providerType(request.getProviderType())
                .status(taskStatus(task))
                .model(request.getModel())
                .modelConfigId(request.getModelConfigId())
                .directoryId(request.getDirectoryId())
                .build();
    }

    private String taskStatus(A2aTask task) {
        if (task == null || task.getStatus() == null || task.getStatus().getState() == null) {
            return "SUBMITTED";
        }
        return switch (task.getStatus().getState()) {
            case SUBMITTED -> "SUBMITTED";
            case WORKING -> "RUNNING";
            case INPUT_REQUIRED -> "AWAITING_INPUT";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELED -> "CANCELLED";
        };
    }

    private BusinessAgentTaskService.OpenApiTaskWorkerPreflight stubPreflight(
            Fixture fixture,
            String workerId,
            String modelConfigId,
            String upstreamSystemId) {
        BusinessAgentTaskService.OpenApiTaskWorkerPreflight preflight =
                mock(BusinessAgentTaskService.OpenApiTaskWorkerPreflight.class);
        when(preflight.workerId()).thenReturn(workerId);
        when(preflight.modelConfigId()).thenReturn(modelConfigId);
        when(preflight.workerPoolId()).thenReturn("pool-1");
        when(preflight.workerBackend()).thenReturn("LANGGRAPH_BIZ");
        when(preflight.upstreamSystemId()).thenReturn(upstreamSystemId);
        when(fixture.taskService.resolveOpenApiTaskWorkerPreflight(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    BusinessAgentWorkerTaskLaunchRequest selection = invocation.getArgument(7);
                    selection.setSelectedWorkerId(workerId);
                    return preflight;
                });
        return preflight;
    }

    private BusinessAgentTaskService.PreparedOpenApiTaskScopedToken preparedToken() {
        return new BusinessAgentTaskService.PreparedOpenApiTaskScopedToken(
                "task-token-1",
                "token-id-1",
                "worker-1",
                "lease-1",
                "pool-1",
                "LANGGRAPH_BIZ",
                0,
                "REQUEST_EXPLICIT_EMPTY",
                true);
    }

    private A2aTask submittedTask(A2aTaskState state) {
        return A2aTask.builder()
                .id("task-1")
                .contextId("ctx-1")
                .metadata(Map.of("sessionId", "session-1", "workerId", "worker-1"))
                .status(A2aTaskStatus.builder().state(state).build())
                .build();
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
                new RuntimeRequestAuditService.AuditHandle(REQUEST_ID));
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("providerType", "LANGGRAPH_BIZ");
        metadata.put("modelConfigId", "model-1");
        metadata.put("workerId", "worker-1");
        metadata.put("workerBackend", "LANGGRAPH_BIZ");
        metadata.put("requestedToolCount", 0);
        metadata.put("effectiveToolCount", 0);
        metadata.put("toolScopeKind", "NAVIGATOR_BUSINESS_MCP_WRAPPERS");
        metadata.put("toolScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("requestedFunctionCount", 0);
        metadata.put("functionScopeSource", "REQUEST_EXPLICIT_EMPTY");
        metadata.put("runtimeContext", new LinkedHashMap<>(Map.of("existing", "sentinel")));
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

    private static String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private record Fixture(
            OpenApiRuntimeTaskCreateFacade facade,
            ScopedOpenApiTaskCreateCommandAdapter scopedAdapter,
            AgentSubmitPipeline submitPipeline,
            OpenApiSessionQueryService sessionQueryService,
            RuntimeRequestAuditService auditService,
            BusinessAgentTaskService taskService,
            BusinessAgentSessionService sessionService,
            AtomicReference<AgentTaskSubmitRequest> submittedRequest,
            AtomicReference<TaskDispatchRequest> canonicalRequest) {
    }
}
