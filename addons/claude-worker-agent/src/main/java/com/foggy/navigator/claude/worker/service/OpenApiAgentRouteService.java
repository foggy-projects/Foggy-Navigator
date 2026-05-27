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
                .orElseThrow(() -> bindingFailure(
                        "ROOT_AGENT_NOT_FOUND",
                        "Agent not found: " + routeAgentId,
                        "Register or resync the ClientApp root agent with `upstream agent sync --manifest <agent-manifest.json>`, then rerun verify-agent-readiness."));
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
            throw bindingFailure(
                    "ROOT_AGENT_PROFILE_CLIENT_APP_MISMATCH",
                    "Agent profile ClientApp binding mismatch: agentId=" + routeAgentId
                            + " expectedClientAppId=" + credential.getClientAppId()
                            + " profileClientAppId=" + boundClientAppId,
                    "Update the root agent profile for this ClientApp or resync the agent bundle with `upstream agent sync --manifest <agent-manifest.json>`.");
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
            throw bindingFailure(
                    "ROOT_AGENT_DISABLED",
                    "Agent is disabled: " + routeAgentId,
                    "Enable or resync the root agent for this profile, then rerun verify-agent-readiness.");
        }
        if (!credential.getTenantId().equals(agent.getTenantId())) {
            throw bindingFailure(
                    "ROOT_AGENT_TENANT_MISMATCH",
                    "Agent tenant mismatch: agentId=" + routeAgentId
                            + " expectedTenantId=" + credential.getTenantId()
                            + " actualTenantId=" + agent.getTenantId(),
                    "Use a profile for the same Navigator tenant as the root agent, or recreate the profile/agent pair.");
        }
        ResourceOwnerType ownerType = agent.getOwnerType();
        String ownerId = agent.getOwnerId();
        if (ownerType == null || !StringUtils.hasText(ownerId)) {
            throw bindingFailure(
                    "ROOT_AGENT_OWNER_MISSING",
                    "Agent owner is not configured: " + routeAgentId,
                    "Recreate or resync the root agent so it is owned by this ClientApp or its upstream system.");
        }
        if (ownerType == ResourceOwnerType.CLIENT_APP) {
            if (!ownerId.equals(credential.getClientAppId())
                    || (StringUtils.hasText(agent.getClientAppId())
                    && !agent.getClientAppId().equals(credential.getClientAppId()))) {
                throw bindingFailure(
                        "ROOT_AGENT_CLIENT_APP_MISMATCH",
                        "Agent ClientApp binding mismatch: agentId=" + routeAgentId
                                + " expectedClientAppId=" + credential.getClientAppId()
                                + " ownerType=" + ownerType
                                + " ownerId=" + ownerId
                                + " agentClientAppId=" + valueOrEmpty(agent.getClientAppId()),
                        "Use the profile whose NAVI_CLIENT_APP_ID owns this agent, or resync/register this root agent for the current ClientApp with `upstream agent sync --manifest <agent-manifest.json>`.");
            }
            return;
        }
        if (ownerType == ResourceOwnerType.UPSTREAM_SYSTEM) {
            if (clientApp == null || !ownerId.equals(clientApp.getUpstreamSystemId())) {
                throw bindingFailure(
                        "ROOT_AGENT_UPSTREAM_SYSTEM_MISMATCH",
                        "Agent upstream-system visibility mismatch: agentId=" + routeAgentId
                                + " expectedUpstreamSystemId=" + valueOrEmpty(clientApp != null ? clientApp.getUpstreamSystemId() : null)
                                + " ownerType=" + ownerType
                                + " ownerId=" + ownerId,
                        "Use a ClientApp from the same upstream system as this root agent, or create an upstream-system root agent for the current system.");
            }
            return;
        }
        throw bindingFailure(
                "ROOT_AGENT_OWNER_NOT_VISIBLE",
                "Agent owner is not visible to ClientApp runtime tokens: agentId=" + routeAgentId
                        + " ownerType=" + ownerType
                        + " ownerId=" + ownerId,
                "Use a ClientApp-owned or upstream-system-owned root agent for ClientApp runtime-token access.");
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

    private RootAgentBindingException bindingFailure(String errorCode, String message, String action) {
        return new RootAgentBindingException(errorCode, message, action);
    }

    private String valueOrEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "(empty)";
    }

    public static class RootAgentBindingException extends IllegalArgumentException {
        private final String errorCode;
        private final String action;

        public RootAgentBindingException(String errorCode, String message, String action) {
            super(message);
            this.errorCode = errorCode;
            this.action = action;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public String getAction() {
            return action;
        }
    }

    public record ResolvedOpenApiAgentRoute(
            String agentId,
            String skillId,
            String clientAppId,
            boolean agentFound,
            boolean legacySkillRoute) {
    }
}
