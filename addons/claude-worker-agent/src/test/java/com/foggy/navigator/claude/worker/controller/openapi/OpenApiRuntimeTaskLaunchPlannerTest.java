package com.foggy.navigator.claude.worker.controller.openapi;

import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.claude.worker.model.form.OpenApiQueryForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiRuntimeTaskLaunchPlannerTest {

    @Test
    void planProducesImmutableServerOwnedLaunchSnapshot() {
        ClaudeWorkerRepository workerRepository = mock(ClaudeWorkerRepository.class);
        A2AgentResourceResolver resolver = mock(A2AgentResourceResolver.class);
        OpenApiRuntimeTaskLaunchPlanner planner =
                new OpenApiRuntimeTaskLaunchPlanner(workerRepository);
        A2AgentResourceResolver.ResolvedAgentResource agent = agentResource(
                "OPENAI_CODEX_APP_SERVER", "agent-worker", "AGENT_WORKER_REF", "pool-1");
        A2AgentResourceResolver.ResolvedModelResource model = modelResource(
                "OPENAI_CODEX_APP_SERVER", "model-requested", "codex-terra:ultra");
        A2AgentResourceResolver.ResolvedWorkspaceResource workspace = workspaceResource(
                "workspace-worker", "/srv/resolved");
        when(resolver.resolveRequiredAgent("tenant-1", "app-1", "upstream-1", "agent-1"))
                .thenReturn(agent);
        when(resolver.resolveRequiredModelForAgent(
                eq("tenant-1"), eq("app-1"), eq(agent),
                eq("model-requested"), eq("codex-terra:ultra"), eq(LlmModelCategory.GENERAL)))
                .thenReturn(model);
        when(resolver.resolveRequiredWorkspaceForAgent(
                "tenant-1", "app-1", "upstream-1", agent, "dir-1"))
                .thenReturn(workspace);

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setModelConfigId("model-requested");
        form.setModelVariant("codex-terra:ultra");
        form.setDirectoryId("dir-1");
        form.setAllowedTools(new ArrayList<>(List.of(" business.functions.list ", "business.functions.list")));
        form.setAllowedDirs(List.of("/srv/repeated", " /srv/repeated "));
        List<String> expectedFunctions = new ArrayList<>();
        expectedFunctions.add("function-a");
        expectedFunctions.add(null);
        expectedFunctions.add("function-a");
        form.setAllowedFunctions(new ArrayList<>(expectedFunctions));
        form.setAttachments(List.of(Map.of("url", "https://example.test/top.txt")));
        Map<String, Object> callerContext = new LinkedHashMap<>();
        callerContext.put("businessKey", "kept");
        Map<String, Object> callerMetadata = new LinkedHashMap<>();
        callerMetadata.put("workerId", "caller-worker");
        callerMetadata.put("physicalWorkerId", "caller-physical-worker");
        callerMetadata.put("selectedWorkerId", "caller-selected-worker");
        callerMetadata.put("directoryId", "caller-dir");
        callerMetadata.put("modelConfigId", "caller-model");
        callerMetadata.put("upstreamSystemId", "caller-system");
        callerMetadata.put("context", callerContext);
        callerMetadata.put("attachments", List.of(Map.of(
                "url", "https://example.test/metadata.txt")));
        form.setMetadata(callerMetadata);

        OpenApiRuntimeTaskLaunchPlanner.LaunchContext launchContext =
                new OpenApiRuntimeTaskLaunchPlanner.LaunchContext(
                        "tenant-1", "app-1", "upstream-1", "agent-1", "skill-1", "ctx-1");
        OpenApiRuntimeTaskLaunchPlanner.ResolvedLaunchResources resources =
                planner.resolveResources(resolver, launchContext, form);
        OpenApiRuntimeTaskLaunchPlanner.LaunchPlan plan =
                planner.plan(resolver, resources, form);

        assertFalse(plan.taskDirectoryMissing());
        assertEquals("workspace-worker", plan.metadata().get("workerId"));
        assertEquals("dir-1", plan.metadata().get("directoryId"));
        assertEquals("model-requested", plan.metadata().get("modelConfigId"));
        assertEquals(1, plan.metadata().get("requestedToolCount"));
        assertEquals(1, plan.metadata().get("effectiveToolCount"));
        assertEquals(1, plan.metadata().get("requestedFunctionCount"));
        assertNull(plan.metadata().get("effectiveFunctionCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> executionPolicy = (Map<String, Object>)
                ((Map<String, Object>) plan.metadata().get("context")).get("execution_policy");
        assertEquals(List.of("/srv/repeated", "/srv/repeated"),
                executionPolicy.get("allowed_dirs"));
        assertEquals("https://example.test/top.txt",
                plan.normalizedAttachments().get(0).get("url"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.metadata().put("workerId", "mutated"));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.normalizedAttachments().get(0).put("url", "mutated"));

        Map<String, Object> mutableMetadata = plan.mutableMetadata();
        mutableMetadata.put("workerId", "downstream-copy");
        assertEquals("workspace-worker", plan.metadata().get("workerId"));
        form.getAllowedFunctions().set(0, "mutated-after-plan");

        BusinessAgentWorkerTaskLaunchRequest selection =
                plan.workerSelectionRequest("owner-user-1");
        assertEquals("owner-user-1", selection.getActorUserId());
        assertEquals("workspace-worker", selection.getPhysicalWorkerId());
        assertEquals("pool-1", selection.getWorkerPoolId());
        assertEquals("upstream-1", selection.getUpstreamUserId());
        assertNull(selection.getUpstreamSystemId());
        assertNull(selection.getSelectedWorkerId());
        assertEquals(List.of("business.functions.list"), selection.getAllowedTools());
        assertTrue(selection.isAllowedFunctionsProvided());
        assertEquals(expectedFunctions, selection.getAllowedFunctions());
        assertThrows(UnsupportedOperationException.class,
                () -> selection.getAllowedFunctions().add("mutated"));

        verify(resolver).resolveRequiredAgent("tenant-1", "app-1", "upstream-1", "agent-1");
        verify(resolver).resolveRequiredModelForAgent(
                "tenant-1", "app-1", agent,
                "model-requested", "codex-terra:ultra", LlmModelCategory.GENERAL);
        verify(resolver).resolveRequiredWorkspaceForAgent(
                "tenant-1", "app-1", "upstream-1", agent, "dir-1");
    }

    @Test
    void langgraphPlanUsesServerResolvedBizIdentityAndRequiresWorkspace() {
        A2AgentResourceResolver resolver = mock(A2AgentResourceResolver.class);
        OpenApiRuntimeTaskLaunchPlanner planner = new OpenApiRuntimeTaskLaunchPlanner(
                mock(ClaudeWorkerRepository.class));
        A2AgentResourceResolver.ResolvedAgentResource agent = agentResource(
                "LANGGRAPH_BIZ", "coding-worker", "AGENT_WORKER_REF", null);
        A2AgentResourceResolver.ResolvedModelResource model = modelResource(
                "LANGGRAPH_BIZ", "model-biz", "biz-default");
        when(resolver.resolveRequiredAgent("tenant-1", "app-1", "upstream-1", "agent-1"))
                .thenReturn(agent);
        when(resolver.resolveRequiredModelForAgent(
                eq("tenant-1"), eq("app-1"), eq(agent),
                eq("model-biz"), isNull(), eq(LlmModelCategory.GENERAL)))
                .thenReturn(model);
        when(resolver.resolveLatestHealthyBizWorkerIdentityId("tenant-1", "app-1"))
                .thenReturn(Optional.of("biz-worker-server-owned"));

        OpenApiQueryForm form = new OpenApiQueryForm();
        form.setMetadata(Map.of(
                "workerId", "caller-worker",
                "upstreamSystemId", "caller-system"));
        OpenApiRuntimeTaskLaunchPlanner.LaunchContext launchContext =
                new OpenApiRuntimeTaskLaunchPlanner.LaunchContext(
                        "tenant-1", "app-1", "upstream-1", "agent-1", "skill-1", "ctx-1");
        OpenApiRuntimeTaskLaunchPlanner.ResolvedLaunchResources resources =
                planner.resolveResources(resolver, launchContext, form);
        OpenApiRuntimeTaskLaunchPlanner.LaunchPlan plan =
                planner.plan(resolver, resources, form);

        assertTrue(plan.taskDirectoryMissing());
        assertEquals("biz-worker-server-owned", plan.metadata().get("workerId"));
        assertEquals("BIZ_WORKER_IDENTITY", plan.metadata().get("workerSource"));
        BusinessAgentWorkerTaskLaunchRequest request = plan.workerSelectionRequest("owner-user-1");
        assertEquals("biz-worker-server-owned", request.getPhysicalWorkerId());
        assertEquals("biz-worker-server-owned", request.getWorkerPoolId());
        assertNull(request.getUpstreamSystemId());
    }

    private A2AgentResourceResolver.ResolvedAgentResource agentResource(
            String workerBackend,
            String physicalWorkerId,
            String physicalWorkerSource,
            String workerPoolId) {
        return new A2AgentResourceResolver.ResolvedAgentResource(
                "agent-1",
                ResourceOwnerType.CLIENT_APP,
                "app-1",
                "app-1",
                "skill-1",
                workerPoolId,
                workerPoolId == null ? null : ResourceOwnerType.UPSTREAM_SYSTEM,
                workerPoolId == null ? null : "server-owned-system",
                workerPoolId == null ? null : "WORKER_POOL:UPSTREAM_SYSTEM",
                workerBackend,
                physicalWorkerId,
                ResourceOwnerType.CLIENT_APP,
                "app-1",
                physicalWorkerSource,
                workerBackend.equals("LANGGRAPH_BIZ") ? "model-biz" : "model-requested",
                null,
                workerBackend.equals("LANGGRAPH_BIZ") ? null : "dir-1",
                "AGENT:CLIENT_APP");
    }

    private A2AgentResourceResolver.ResolvedModelResource modelResource(
            String workerBackend,
            String modelConfigId,
            String modelName) {
        return new A2AgentResourceResolver.ResolvedModelResource(
                modelConfigId,
                modelConfigId,
                null,
                LlmModelCategory.GENERAL,
                modelName,
                "REQUESTED_MODEL",
                workerBackend,
                "MODEL_CONFIG_GRANT");
    }

    private A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource(
            String workerId, String workdir) {
        return new A2AgentResourceResolver.ResolvedWorkspaceResource(
                "dir-1",
                workerId,
                WorkspaceScope.USER_PRIVATE,
                WorkingDirectoryResolverType.MANAGED,
                workdir,
                List.of(workdir),
                false,
                null,
                null,
                null,
                "WORKING_DIRECTORY:USER_PRIVATE");
    }
}
