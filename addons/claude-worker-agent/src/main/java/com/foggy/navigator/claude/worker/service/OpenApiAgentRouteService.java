package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenApiAgentRouteService {

    private final CodingAgentRepository codingAgentRepository;
    private final ClientAppService clientAppService;
    private final ObjectMapper objectMapper;

    public ResolvedOpenApiAgentRoute resolve(String routeAgentId, ResolvedClientAppCredentialDTO credential) {
        Assert.hasText(routeAgentId, "agentId is required");
        if (credential == null) {
            throw new IllegalArgumentException("client app access token is required");
        }

        CodingAgentEntity agent = codingAgentRepository.findByAgentIdAndTenantId(routeAgentId, credential.getTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + routeAgentId));
        ClientAppEntity clientApp = clientAppService.requireClientApp(
                credential.getTenantId(),
                credential.getClientAppId());
        return resolveFromAgent(routeAgentId, credential, clientApp, agent);
    }

    private ResolvedOpenApiAgentRoute resolveFromAgent(
            String routeAgentId,
            ResolvedClientAppCredentialDTO credential,
            ClientAppEntity clientApp,
            CodingAgentEntity agent) {
        validateAgentVisibility(routeAgentId, credential, clientApp, agent);
        String boundClientAppId = resolveProfileText(agent.getAgentProfile(), "clientAppId", "client_app_id");
        if (StringUtils.hasText(boundClientAppId) && !boundClientAppId.equals(credential.getClientAppId())) {
            throw new IllegalArgumentException("Agent is not bound to this ClientApp: " + routeAgentId);
        }

        String skillId = firstText(
                resolveProfileText(agent.getAgentProfile(), "skillId", "skill_id"),
                resolveFirstSkillId(agent.getSkills()),
                routeAgentId);
        return new ResolvedOpenApiAgentRoute(
                agent.getAgentId(),
                skillId,
                StringUtils.hasText(boundClientAppId) ? boundClientAppId : credential.getClientAppId(),
                true,
                false);
    }

    private void validateAgentVisibility(
            String routeAgentId,
            ResolvedClientAppCredentialDTO credential,
            ClientAppEntity clientApp,
            CodingAgentEntity agent) {
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalArgumentException("Agent is disabled: " + routeAgentId);
        }
        if (!credential.getTenantId().equals(agent.getTenantId())) {
            throw new IllegalArgumentException("Agent not found: " + routeAgentId);
        }
        ResourceOwnerType ownerType = agent.getOwnerType();
        String ownerId = agent.getOwnerId();
        if (ownerType == null || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("Agent owner is not configured: " + routeAgentId);
        }
        if (ownerType == ResourceOwnerType.CLIENT_APP) {
            if (!ownerId.equals(credential.getClientAppId())
                    || (StringUtils.hasText(agent.getClientAppId())
                    && !agent.getClientAppId().equals(credential.getClientAppId()))) {
                throw new IllegalArgumentException("Agent is not bound to this ClientApp: " + routeAgentId);
            }
            return;
        }
        if (ownerType == ResourceOwnerType.UPSTREAM_SYSTEM) {
            if (clientApp == null || !ownerId.equals(clientApp.getUpstreamSystemId())) {
                throw new IllegalArgumentException("Agent is not visible to this ClientApp: " + routeAgentId);
            }
            return;
        }
        throw new IllegalArgumentException("Agent owner is not visible to ClientApp runtime tokens: " + routeAgentId);
    }

    private String resolveProfileText(String profileJson, String... keys) {
        if (!StringUtils.hasText(profileJson)) {
            return null;
        }
        try {
            Map<String, Object> profile = objectMapper.readValue(profileJson, new TypeReference<>() {
            });
            for (String key : keys) {
                Object value = profile.get(key);
                if (value instanceof String text && StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String resolveFirstSkillId(String skillsJson) {
        if (!StringUtils.hasText(skillsJson)) {
            return null;
        }
        try {
            List<Object> skills = objectMapper.readValue(skillsJson, new TypeReference<>() {
            });
            for (Object skill : skills) {
                String skillId = resolveSkillItemId(skill);
                if (StringUtils.hasText(skillId)) {
                    return skillId.trim();
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String resolveSkillItemId(Object item) {
        if (item instanceof String text) {
            return text;
        }
        if (item instanceof Map<?, ?> rawMap) {
            Map<Object, Object> map = (Map<Object, Object>) rawMap;
            Object value = map.get("id");
            if (value == null) {
                value = map.get("skillId");
            }
            if (value == null) {
                value = map.get("skill_id");
            }
            return value instanceof String text ? text : null;
        }
        return null;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    public record ResolvedOpenApiAgentRoute(
            String agentId,
            String skillId,
            String clientAppId,
            boolean agentFound,
            boolean legacySkillRoute) {
    }
}
