package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenApiAgentRouteServiceTest {

    private final CodingAgentRepository repository = mock(CodingAgentRepository.class);
    private final ClientAppService clientAppService = mock(ClientAppService.class);
    private final OpenApiAgentRouteService service =
            new OpenApiAgentRouteService(repository, clientAppService, new ObjectMapper());

    @BeforeEach
    void setUp() {
        ClientAppEntity clientApp = new ClientAppEntity();
        clientApp.setTenantId("tenant-1");
        clientApp.setClientAppId("app-1");
        clientApp.setUpstreamSystemId("upstream-system-1");
        when(clientAppService.requireClientApp("tenant-1", "app-1")).thenReturn(clientApp);
    }

    @Test
    void resolve_derivesSkillIdFromAgentProfile() {
        CodingAgentEntity agent = agent("root-agent");
        agent.setAgentProfile("""
                {"clientAppId":"app-1","skillId":"tms.navigator.agent"}
                """);
        agent.setSkills("""
                [{"id":"fallback.skill"}]
                """);
        when(repository.findByAgentIdAndTenantId("root-agent", "tenant-1"))
                .thenReturn(Optional.of(agent));

        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                service.resolve("root-agent", credential());

        assertEquals("root-agent", route.agentId());
        assertEquals("tms.navigator.agent", route.skillId());
        assertEquals("app-1", route.clientAppId());
        assertTrue(route.agentFound());
        assertFalse(route.legacySkillRoute());
    }

    @Test
    void resolve_fallsBackToFirstSkillSummaryId() {
        CodingAgentEntity agent = agent("root-agent");
        agent.setAgentProfile("""
                {"clientAppId":"app-1"}
                """);
        agent.setSkills("""
                [{"id":"summary.skill","name":"Summary Skill"}]
                """);
        when(repository.findByAgentIdAndTenantId("root-agent", "tenant-1"))
                .thenReturn(Optional.of(agent));

        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                service.resolve("root-agent", credential());

        assertEquals("summary.skill", route.skillId());
    }

    @Test
    void resolve_rejectsAgentBoundToAnotherClientApp() {
        CodingAgentEntity agent = agent("root-agent");
        agent.setAgentProfile("""
                {"clientAppId":"other-app","skillId":"tms.navigator.agent"}
                """);
        when(repository.findByAgentIdAndTenantId("root-agent", "tenant-1"))
                .thenReturn(Optional.of(agent));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve("root-agent", credential()));

        assertTrue(error.getMessage().contains("not bound"));
    }

    @Test
    void resolve_rejectsMissingAgentInsteadOfLegacySkillRoute() {
        when(repository.findByAgentIdAndTenantId("legacy-skill", "tenant-1"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.resolve("legacy-skill", credential()));

        assertTrue(error.getMessage().contains("Agent not found"));
    }

    private CodingAgentEntity agent(String agentId) {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId(agentId);
        agent.setTenantId("tenant-1");
        agent.setOwnerType(ResourceOwnerType.CLIENT_APP);
        agent.setOwnerId("app-1");
        agent.setClientAppId("app-1");
        agent.setEnabled(true);
        return agent;
    }

    private ResolvedClientAppCredentialDTO credential() {
        return ResolvedClientAppCredentialDTO.builder()
                .tenantId("tenant-1")
                .clientAppId("app-1")
                .credentialId("cred-1")
                .build();
    }
}
