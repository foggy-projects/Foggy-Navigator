package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenApiAgentReadinessServiceTest {

    private UnifiedAgentResolver agentResolver;
    private ClientAppService clientAppService;
    private SkillRegistryService skillRegistryService;
    private ClientAppUserGrantService userGrantService;
    private A2AgentResourceResolver resourceResolver;
    private ClientAppUpstreamRouteService upstreamRouteService;
    private BusinessFunctionRegistryService functionRegistryService;
    private OpenApiAgentRouteService agentRouteService;
    private Environment environment;
    private OpenApiAgentReadinessService service;

    @BeforeEach
    void setUp() {
        agentResolver = mock(UnifiedAgentResolver.class);
        clientAppService = mock(ClientAppService.class);
        skillRegistryService = mock(SkillRegistryService.class);
        userGrantService = mock(ClientAppUserGrantService.class);
        resourceResolver = mock(A2AgentResourceResolver.class);
        upstreamRouteService = mock(ClientAppUpstreamRouteService.class);
        functionRegistryService = mock(BusinessFunctionRegistryService.class);
        agentRouteService = mock(OpenApiAgentRouteService.class);
        environment = mock(Environment.class);
        service = new OpenApiAgentReadinessService(
                agentResolver,
                clientAppService,
                skillRegistryService,
                userGrantService,
                resourceResolver,
                upstreamRouteService,
                functionRegistryService,
                agentRouteService,
                environment,
                new ObjectMapper());

        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("capp_1");
        app.setName("external-llm-agent-dev");
        when(clientAppService.requireActiveClientApp("tenant_1", "capp_1")).thenReturn(app);
        when(agentResolver.resolveAgent(eq("world-sim.bug-coordinator.decision.v1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(mock(A2aAgent.class)));
        when(resourceResolver.resolveRequiredModelForAgent(eq("tenant_1"), eq("capp_1"), any(), nullable(String.class), nullable(String.class), any()))
                .thenAnswer(invocation -> {
                    String requestedModelConfigId = invocation.getArgument(3, String.class);
                    String requestedModelVariant = invocation.getArgument(4, String.class);
                    LlmModelCategory category = invocation.getArgument(5, LlmModelCategory.class);
                    return new A2AgentResourceResolver.ResolvedModelResource(
                            requestedModelConfigId != null ? requestedModelConfigId : "model_default",
                            requestedModelConfigId,
                            requestedModelVariant,
                            category,
                            requestedModelVariant != null
                                    ? requestedModelVariant
                                    : (requestedModelConfigId != null ? requestedModelConfigId + "-name" : "model_default-name"),
                            requestedModelVariant != null ? "REQUESTED_MODEL_VARIANT" : "MODEL_CONFIG_DEFAULT",
                            "LANGGRAPH_BIZ",
                            requestedModelConfigId != null
                                    ? "AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT"
                                    : "AGENT_DEFAULT_MODEL:DEFAULT_MODEL_GRANT");
                });
        when(resourceResolver.resolveRequiredAgent(eq("tenant_1"), eq("capp_1"), eq("private_1"), anyString()))
                .thenAnswer(invocation -> {
                    String resolvedAgentId = invocation.getArgument(3, String.class);
                    return new A2AgentResourceResolver.ResolvedAgentResource(
                            resolvedAgentId,
                            ResourceOwnerType.CLIENT_APP,
                            "capp_1",
                            "capp_1",
                            resolvedAgentId,
                            "pool_1",
                            ResourceOwnerType.UPSTREAM_SYSTEM,
                            "usys_1",
                            "WORKER_POOL:UPSTREAM_SYSTEM",
                            "LANGGRAPH_BIZ",
                            "model_1",
                            "qwen-plus",
                            null,
                            "AGENT:CLIENT_APP");
                });
        when(agentRouteService.resolve(eq("world-sim.bug-coordinator.decision.v1"), any()))
                .thenReturn(new OpenApiAgentRouteService.ResolvedOpenApiAgentRoute(
                        "world-sim.bug-coordinator.decision.v1",
                        "world-sim.bug-coordinator.decision.v1",
                        "capp_1",
                        true,
                        false));
        when(functionRegistryService.listClientAppVisibleFunctionSummaries("tenant_1", "capp_1"))
                .thenReturn(List.of());
    }

    @Test
    void verify_returnsOkWhenAllChecksPass() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of("skillId", "world-sim.bug-coordinator.decision.v1"));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("OK", result.getOverallStatus());
        assertEquals("model_1", result.getEffectiveModelConfigId());
        assertEquals("model_1-name", result.getEffectiveModelName());
        assertEquals("LANGGRAPH_BIZ", result.getEffectiveWorkerBackend());
        assertEquals("CLIENT_APP", result.getAgentOwnerType());
        assertEquals("pool_1", result.getWorkerPoolId());
        assertEquals("pool_1", result.getInternalWorkerPoolId());
        assertEquals("UPSTREAM_SYSTEM", result.getWorkerPoolOwnerType());
        assertEquals("AGENT_DEFAULT_MODEL:REQUESTED_MODEL_GRANT", result.getModelConfigSource());
        assertNotNull(result.getSkillArtifact());
        assertEquals(7, result.getChecks().size());
        assertTrue(result.getChecks().stream().allMatch(check -> "OK".equals(check.getStatus())));
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "capp_1", "world-sim.bug-coordinator.decision.v1");
        verify(userGrantService).checkUpstreamUserAccess("tenant_1", "capp_1", "private_1");
    }

    @Test
    void verify_resolvesRequestedWorkspaceResourceForInspection() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setDirectoryId("dir_user");
        form.setContext(Map.of("skillId", "world-sim.bug-coordinator.decision.v1"));
        when(resourceResolver.resolveRequiredWorkspaceForAgent(
                eq("tenant_1"), eq("capp_1"), eq("private_1"), any(), eq("dir_user")))
                .thenReturn(new A2AgentResourceResolver.ResolvedWorkspaceResource(
                        "dir_user",
                        "worker_1",
                        WorkspaceScope.USER_PRIVATE,
                        WorkingDirectoryResolverType.MANAGED,
                        "/workspace/user",
                        List.of("/workspace/user"),
                        false,
                        null,
                        null,
                        null,
                        "WORKING_DIRECTORY:USER_PRIVATE"));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("OK", result.getOverallStatus());
        assertEquals("dir_user", result.getRequestedDirectoryId());
        assertEquals("dir_user", result.getEffectiveDirectoryId());
        assertEquals("worker_1", result.getEffectivePhysicalWorkerId());
        assertEquals("USER_PRIVATE", result.getWorkspaceScope());
        assertEquals("MANAGED", result.getWorkspaceResolverType());
        assertEquals(Boolean.FALSE, result.getWorkspaceReadOnly());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "WORKSPACE_RESOURCE".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
    }

    @Test
    void verify_usesRootAgentRouteAndDerivedSkillGrant() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of("skillId", "tms.navigator.agent"));

        when(agentRouteService.resolve(eq("root-agent"), any()))
                .thenReturn(new OpenApiAgentRouteService.ResolvedOpenApiAgentRoute(
                        "root-agent",
                        "tms.navigator.agent",
                        "capp_1",
                        true,
                        false));
        when(agentResolver.resolveAgent(eq("root-agent"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(mock(A2aAgent.class)));

        AgentReadinessDTO result = service.verify(
                "root-agent",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("OK", result.getOverallStatus());
        assertEquals("/api/v1/open/skills/tms.navigator.agent/files/tree",
                result.getSkillArtifact().getTreeUrl());
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "capp_1", "tms.navigator.agent");
        verify(agentResolver).resolveAgent(eq("root-agent"), any(AgentResolveContext.class));
    }

    @Test
    void verify_reportsRootAgentBindingFailure() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        when(agentRouteService.resolve(eq("root-agent"), any()))
                .thenThrow(new IllegalArgumentException("Agent is not bound to this ClientApp: root-agent"));

        AgentReadinessDTO result = service.verify(
                "root-agent",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertEquals("ROOT_AGENT_BINDING", result.getChecks().get(0).getCode());
        assertTrue(result.getChecks().get(0).getMessage().contains("not bound"));
        verifyNoInteractions(agentResolver, skillRegistryService, userGrantService, resourceResolver);
    }

    @Test
    void verify_acceptsContextSkillIdAsDiagnosticOnly() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setContext(Map.of("skillId", "other-skill"));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertTrue(result.getChecks().stream().noneMatch(check ->
                "ROUTE_SKILL_MISMATCH".equals(check.getCode())));
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "capp_1", "world-sim.bug-coordinator.decision.v1");
    }

    @Test
    void verify_reportsMissingUpstreamUserGrantAsDistinctCheckFailure() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        doThrow(new IllegalStateException("Upstream user is not granted access to this Client App"))
                .when(userGrantService).checkUpstreamUserAccess("tenant_1", "capp_1", "private_1");

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "UPSTREAM_USER_GRANT".equals(check.getCode())
                        && "FAIL".equals(check.getStatus())
                        && check.getMessage().contains("not granted")));
    }

    @Test
    void verify_checksRequiredClientAppUpstreamRouteAndTokenBinding() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of(
                "skillId", "world-sim.bug-coordinator.decision.v1",
                "requiredUpstreamRefs", java.util.List.of("local-smoke-2026-05-18")));
        when(upstreamRouteService.resolveEnabledRoute("tenant_1", "capp_1", "local-smoke-2026-05-18"))
                .thenReturn(Optional.of(new ClientAppUpstreamRouteService.ResolvedUpstreamRoute(
                        "http://tms-local", "X-TMS-Agent-Token")));
        when(userGrantService.resolveUpstreamUserToken("tenant_1", "capp_1", "private_1"))
                .thenReturn("user-token-secret");

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("OK", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "UPSTREAM_ROUTE:local-smoke-2026-05-18".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "UPSTREAM_USER_TOKEN:local-smoke-2026-05-18".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
        verify(userGrantService).resolveUpstreamUserToken("tenant_1", "capp_1", "private_1");
    }

    @Test
    void verify_reportsMissingRequiredUpstreamRoute() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of(
                "skillId", "world-sim.bug-coordinator.decision.v1",
                "requiredUpstreamRefs", java.util.List.of("local-smoke-2026-05-18")));
        when(upstreamRouteService.resolveEnabledRoute("tenant_1", "capp_1", "local-smoke-2026-05-18"))
                .thenReturn(Optional.empty());

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "UPSTREAM_ROUTE:local-smoke-2026-05-18".equals(check.getCode())
                        && "FAIL".equals(check.getStatus())
                        && check.getMessage().contains("Unauthorized or unconfigured upstream_ref")));
    }

    @Test
    void verify_reportsMissingRequiredUpstreamUserTokenBinding() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of(
                "skillId", "world-sim.bug-coordinator.decision.v1",
                "requiredUpstreamRefs", java.util.List.of("local-smoke-2026-05-18")));
        when(upstreamRouteService.resolveEnabledRoute("tenant_1", "capp_1", "local-smoke-2026-05-18"))
                .thenReturn(Optional.of(new ClientAppUpstreamRouteService.ResolvedUpstreamRoute(
                        "http://tms-local", "X-TMS-Agent-Token")));
        when(userGrantService.resolveUpstreamUserToken("tenant_1", "capp_1", "private_1"))
                .thenThrow(new IllegalStateException("Upstream user token is not configured"));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "UPSTREAM_USER_TOKEN:local-smoke-2026-05-18".equals(check.getCode())
                        && "FAIL".equals(check.getStatus())
                        && check.getMessage().contains("token is not configured")));
    }

    @Test
    void verify_reportsInvalidBusinessFunctionAdapterUpstreamRef() {
        AgentReadinessPreflightForm form = baselineForm();
        BusinessFunctionSummaryDTO summary = functionSummary("tms.ticket.createPlatformFeedback", "v1");
        BusinessFunctionRuntimeContextDTO context = functionContext(
                "tms.ticket.createPlatformFeedback",
                "v1",
                """
                {"type":"rest","method":"POST","upstream_ref":"TMS:3","path":"/x3-agent/tms/ticket/platform-feedback"}
                """);
        when(functionRegistryService.listClientAppVisibleFunctionSummaries("tenant_1", "capp_1"))
                .thenReturn(List.of(summary));
        when(functionRegistryService.resolveClientAppFunction(
                "tenant_1", "capp_1", "tms.ticket.createPlatformFeedback", "v1"))
                .thenReturn(context);

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "BUSINESS_FUNCTION_ADAPTER:tms.ticket.createPlatformFeedback".equals(check.getCode())
                        && "FAIL".equals(check.getStatus())
                        && check.getMessage().contains("TMS:3")
                        && check.getMessage().contains("[A-Za-z0-9._-]{1,128}")));
        verify(upstreamRouteService, never()).resolveEnabledRoute("tenant_1", "capp_1", "TMS:3");
    }

    @Test
    void verify_reportsMissingBusinessFunctionUpstreamRoute() {
        AgentReadinessPreflightForm form = baselineForm();
        BusinessFunctionSummaryDTO summary = functionSummary("tms.ticket.createPlatformFeedback", "v1");
        BusinessFunctionRuntimeContextDTO context = functionContext(
                "tms.ticket.createPlatformFeedback",
                "v1",
                """
                {"type":"rest","method":"POST","upstream_ref":"TMS-3","path":"/x3-agent/tms/ticket/platform-feedback"}
                """);
        when(functionRegistryService.listClientAppVisibleFunctionSummaries("tenant_1", "capp_1"))
                .thenReturn(List.of(summary));
        when(functionRegistryService.resolveClientAppFunction(
                "tenant_1", "capp_1", "tms.ticket.createPlatformFeedback", "v1"))
                .thenReturn(context);
        when(upstreamRouteService.resolveEnabledRoute("tenant_1", "capp_1", "TMS-3"))
                .thenReturn(Optional.empty());

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "BUSINESS_FUNCTION_ADAPTER:tms.ticket.createPlatformFeedback".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "BUSINESS_FUNCTION_UPSTREAM_ROUTE:TMS-3".equals(check.getCode())
                        && "FAIL".equals(check.getStatus())
                        && check.getMessage().contains("not configured")));
    }

    @Test
    void verify_acceptsResolvableBusinessFunctionUpstreamRoute() {
        AgentReadinessPreflightForm form = baselineForm();
        BusinessFunctionSummaryDTO summary = functionSummary("tms.ticket.createPlatformFeedback", "v1");
        BusinessFunctionRuntimeContextDTO context = functionContext(
                "tms.ticket.createPlatformFeedback",
                "v1",
                """
                {"type":"rest","method":"POST","upstream_ref":"TMS-3","path":"/x3-agent/tms/ticket/platform-feedback"}
                """);
        when(functionRegistryService.listClientAppVisibleFunctionSummaries("tenant_1", "capp_1"))
                .thenReturn(List.of(summary));
        when(functionRegistryService.resolveClientAppFunction(
                "tenant_1", "capp_1", "tms.ticket.createPlatformFeedback", "v1"))
                .thenReturn(context);
        when(upstreamRouteService.resolveEnabledRoute("tenant_1", "capp_1", "TMS-3"))
                .thenReturn(Optional.of(new ClientAppUpstreamRouteService.ResolvedUpstreamRoute(
                        "http://tms-local", null)));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("OK", result.getOverallStatus());
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "BUSINESS_FUNCTION_ADAPTER:tms.ticket.createPlatformFeedback".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
        assertTrue(result.getChecks().stream().anyMatch(check ->
                "BUSINESS_FUNCTION_UPSTREAM_ROUTE:TMS-3".equals(check.getCode())
                        && "OK".equals(check.getStatus())));
    }

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .tenantId("tenant_1")
                .clientAppId("capp_1")
                .credentialId("cred_1")
                .build();
    }

    private AgentReadinessPreflightForm baselineForm() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setUpstreamUserId("private_1");
        form.setModelConfigId("model_1");
        form.setContext(Map.of("skillId", "world-sim.bug-coordinator.decision.v1"));
        return form;
    }

    private BusinessFunctionSummaryDTO functionSummary(String functionId, String version) {
        BusinessFunctionSummaryDTO summary = new BusinessFunctionSummaryDTO();
        summary.setFunctionId(functionId);
        summary.setVersion(version);
        return summary;
    }

    private BusinessFunctionRuntimeContextDTO functionContext(String functionId, String version, String adapterConfigJson) {
        BusinessFunctionRuntimeContextDTO context = new BusinessFunctionRuntimeContextDTO();
        context.setFunctionId(functionId);
        context.setVersion(version);
        context.setAdapterConfigJson(adapterConfigJson);
        return context;
    }
}
