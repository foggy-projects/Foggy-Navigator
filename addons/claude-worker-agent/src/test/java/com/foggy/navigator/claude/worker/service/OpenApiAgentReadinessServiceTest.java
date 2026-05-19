package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

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
    private ClientAppModelConfigGrantService modelConfigGrantService;
    private ClientAppUpstreamRouteService upstreamRouteService;
    private OpenApiAgentRouteService agentRouteService;
    private Environment environment;
    private OpenApiAgentReadinessService service;

    @BeforeEach
    void setUp() {
        agentResolver = mock(UnifiedAgentResolver.class);
        clientAppService = mock(ClientAppService.class);
        skillRegistryService = mock(SkillRegistryService.class);
        userGrantService = mock(ClientAppUserGrantService.class);
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        upstreamRouteService = mock(ClientAppUpstreamRouteService.class);
        agentRouteService = mock(OpenApiAgentRouteService.class);
        environment = mock(Environment.class);
        service = new OpenApiAgentReadinessService(
                agentResolver,
                clientAppService,
                skillRegistryService,
                userGrantService,
                modelConfigGrantService,
                upstreamRouteService,
                agentRouteService,
                environment);

        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("capp_1");
        app.setName("external-llm-agent-dev");
        when(clientAppService.requireActiveClientApp("tenant_1", "capp_1")).thenReturn(app);
        when(agentResolver.resolveAgent(eq("world-sim.bug-coordinator.decision.v1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(mock(A2aAgent.class)));
        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant_1", "capp_1", "model_1"))
                .thenReturn("model_1");
        when(agentRouteService.resolve(eq("world-sim.bug-coordinator.decision.v1"), any()))
                .thenReturn(new OpenApiAgentRouteService.ResolvedOpenApiAgentRoute(
                        "world-sim.bug-coordinator.decision.v1",
                        "world-sim.bug-coordinator.decision.v1",
                        "capp_1",
                        true,
                        false));
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
        assertNotNull(result.getSkillArtifact());
        assertEquals(5, result.getChecks().size());
        assertTrue(result.getChecks().stream().allMatch(check -> "OK".equals(check.getStatus())));
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "capp_1", "world-sim.bug-coordinator.decision.v1");
        verify(userGrantService).checkUpstreamUserAccess("tenant_1", "capp_1", "private_1");
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
        verifyNoInteractions(agentResolver, skillRegistryService, userGrantService, modelConfigGrantService);
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

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .tenantId("tenant_1")
                .clientAppId("capp_1")
                .credentialId("cred_1")
                .build();
    }
}
