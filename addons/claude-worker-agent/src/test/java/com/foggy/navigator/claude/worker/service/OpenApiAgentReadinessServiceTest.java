package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private OpenApiAgentReadinessService service;

    @BeforeEach
    void setUp() {
        agentResolver = mock(UnifiedAgentResolver.class);
        clientAppService = mock(ClientAppService.class);
        skillRegistryService = mock(SkillRegistryService.class);
        userGrantService = mock(ClientAppUserGrantService.class);
        modelConfigGrantService = mock(ClientAppModelConfigGrantService.class);
        service = new OpenApiAgentReadinessService(
                agentResolver,
                clientAppService,
                skillRegistryService,
                userGrantService,
                modelConfigGrantService);

        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("capp_1");
        app.setName("external-llm-agent-dev");
        when(clientAppService.requireActiveClientApp("tenant_1", "capp_1")).thenReturn(app);
        when(agentResolver.resolveAgent(eq("world-sim.bug-coordinator.decision.v1"), any(AgentResolveContext.class)))
                .thenReturn(Optional.of(mock(A2aAgent.class)));
        when(modelConfigGrantService.resolveEffectiveModelConfigId("tenant_1", "capp_1", "model_1"))
                .thenReturn("model_1");
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
        assertEquals(4, result.getChecks().size());
        assertTrue(result.getChecks().stream().allMatch(check -> "OK".equals(check.getStatus())));
        verify(skillRegistryService).checkClientAppSkillAccess(
                "tenant_1", "capp_1", "world-sim.bug-coordinator.decision.v1");
        verify(userGrantService).checkUpstreamUserAccess("tenant_1", "capp_1", "private_1");
    }

    @Test
    void verify_failsClosedWhenRouteSkillMismatch() {
        AgentReadinessPreflightForm form = new AgentReadinessPreflightForm();
        form.setContext(Map.of("skillId", "other-skill"));

        AgentReadinessDTO result = service.verify(
                "world-sim.bug-coordinator.decision.v1",
                form,
                credential(),
                "http://localhost:8112");

        assertEquals("FAIL", result.getOverallStatus());
        assertEquals("ROUTE_SKILL_MISMATCH", result.getChecks().get(0).getCode());
        verifyNoInteractions(agentResolver, skillRegistryService, userGrantService, modelConfigGrantService);
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

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .tenantId("tenant_1")
                .clientAppId("capp_1")
                .credentialId("cred_1")
                .build();
    }
}
