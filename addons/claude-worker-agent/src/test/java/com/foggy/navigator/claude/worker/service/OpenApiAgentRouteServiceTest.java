package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
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
    private final OpenApiAgentRouteService service = new OpenApiAgentRouteService(repository, new ObjectMapper());

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
    void resolve_keepsLegacySkillRouteWhenAgentRowIsMissing() {
        when(repository.findByAgentIdAndTenantId("legacy-skill", "tenant-1"))
                .thenReturn(Optional.empty());

        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route =
                service.resolve("legacy-skill", credential());

        assertEquals("legacy-skill", route.agentId());
        assertEquals("legacy-skill", route.skillId());
        assertFalse(route.agentFound());
        assertTrue(route.legacySkillRoute());
    }

    private CodingAgentEntity agent(String agentId) {
        CodingAgentEntity agent = new CodingAgentEntity();
        agent.setAgentId(agentId);
        agent.setTenantId("tenant-1");
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
