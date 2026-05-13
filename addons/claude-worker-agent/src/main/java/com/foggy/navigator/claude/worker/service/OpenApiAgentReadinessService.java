package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.model.dto.AgentReadinessCheckDTO;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactLinkDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class OpenApiAgentReadinessService {

    private final UnifiedAgentResolver agentResolver;
    private final ClientAppService clientAppService;
    private final SkillRegistryService skillRegistryService;
    private final ClientAppUserGrantService userGrantService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;

    public AgentReadinessDTO verify(
            String agentId,
            AgentReadinessPreflightForm form,
            ResolvedClientAppCredentialDTO credential,
            String baseUrl) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        if (credential == null) {
            throw new IllegalArgumentException("client app access token is required");
        }
        AgentReadinessPreflightForm safeForm = form == null ? new AgentReadinessPreflightForm() : form;
        String requestedSkillId = resolveSkillId(agentId, safeForm.getContext());

        AgentReadinessDTO result = new AgentReadinessDTO();
        result.setBaseUrl(baseUrl);
        result.setClientAppId(credential.getClientAppId());
        result.setAgentCode(agentId);
        result.setUpstreamUserId(safeForm.getUpstreamUserId());
        result.setRequestedModelConfigId(safeForm.getModelConfigId());

        ClientAppEntity app = clientAppService.requireActiveClientApp(
                credential.getTenantId(), credential.getClientAppId());
        result.setClientAppName(app.getName());

        if (!agentId.equals(requestedSkillId)) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "ROUTE_SKILL_MISMATCH",
                    "URL agentId must match context.skillId"));
            result.refreshOverallStatus();
            return result;
        }

        addCheck(result, "AGENT_REGISTERED", () -> requireAgent(
                agentId, credential.getTenantId(), safeForm.getModelConfigId()));
        addCheck(result, "CLIENT_APP_SKILL_GRANT", () -> skillRegistryService.checkClientAppSkillAccess(
                credential.getTenantId(), credential.getClientAppId(), agentId));
        if (StringUtils.hasText(safeForm.getUpstreamUserId())) {
            addCheck(result, "UPSTREAM_USER_GRANT", () -> userGrantService.checkUpstreamUserAccess(
                    credential.getTenantId(), credential.getClientAppId(), safeForm.getUpstreamUserId()));
        } else {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "UPSTREAM_USER_ID_REQUIRED",
                    "upstreamUserId is required"));
        }
        addCheck(result, "MODEL_CONFIG_GRANT", () -> {
            String effective = modelConfigGrantService.resolveEffectiveModelConfigId(
                    credential.getTenantId(), credential.getClientAppId(), safeForm.getModelConfigId());
            result.setEffectiveModelConfigId(effective);
        });

        if (isCheckOk(result, "CLIENT_APP_SKILL_GRANT")) {
            SkillArtifactLinkDTO link = new SkillArtifactLinkDTO();
            link.setAvailable(true);
            link.setTreeUrl("/api/v1/open/skills/" + agentId + "/files/tree");
            link.setSliceUrlTemplate("/api/v1/open/skills/" + agentId
                    + "/files/slice?path={path}&startLine={startLine}&startColumn={startColumn}&maxChars={maxChars}");
            result.setSkillArtifact(link);
        }

        result.refreshOverallStatus();
        return result;
    }

    private String resolveSkillId(String agentId, Map<String, Object> context) {
        if (context == null) {
            return agentId;
        }
        Object value = context.get("skillId");
        if (value == null) {
            return agentId;
        }
        String skillId = String.valueOf(value);
        return StringUtils.hasText(skillId) ? skillId : agentId;
    }

    private void requireAgent(String agentId, String tenantId, String modelConfigId) {
        AgentResolveContext ctx = AgentResolveContext.builder()
                .tenantId(tenantId)
                .modelConfigId(modelConfigId)
                .requestSource("OPEN_API")
                .build();
        agentResolver.resolveAgent(agentId, ctx)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found: " + agentId));
    }

    private void addCheck(AgentReadinessDTO result, String code, Runnable action) {
        try {
            action.run();
            result.getChecks().add(AgentReadinessCheckDTO.ok(code));
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(code, sanitize(e.getMessage())));
        }
    }

    private boolean isCheckOk(AgentReadinessDTO result, String code) {
        return result.getChecks().stream()
                .anyMatch(check -> code.equals(check.getCode()) && "OK".equals(check.getStatus()));
    }

    private String sanitize(String message) {
        if (!StringUtils.hasText(message)) {
            return "check failed";
        }
        return message.replace('\n', ' ').replace('\r', ' ');
    }
}
