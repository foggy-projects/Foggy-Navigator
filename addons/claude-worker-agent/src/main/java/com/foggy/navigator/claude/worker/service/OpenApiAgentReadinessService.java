package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.business.agent.model.dto.AgentReadinessCheckDTO;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactLinkDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OpenApiAgentReadinessService {

    private static final String UPSTREAM_PROPERTY_PREFIX = "foggy.navigator.business.agent.upstreams.";

    private final UnifiedAgentResolver agentResolver;
    private final ClientAppService clientAppService;
    private final SkillRegistryService skillRegistryService;
    private final ClientAppUserGrantService userGrantService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final ClientAppUpstreamRouteService upstreamRouteService;
    private final OpenApiAgentRouteService agentRouteService;
    private final Environment environment;

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

        AgentReadinessDTO result = new AgentReadinessDTO();
        result.setBaseUrl(baseUrl);
        result.setClientAppId(credential.getClientAppId());
        result.setAgentCode(agentId);
        result.setUpstreamUserId(safeForm.getUpstreamUserId());
        result.setRequestedModelConfigId(safeForm.getModelConfigId());

        ClientAppEntity app = clientAppService.requireActiveClientApp(
                credential.getTenantId(), credential.getClientAppId());
        result.setClientAppName(app.getName());

        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route;
        try {
            route = agentRouteService.resolve(agentId, credential);
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    "ROOT_AGENT_BINDING",
                    route.legacySkillRoute() ? "legacy skill route resolved" : "root agent binding resolved"));
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "ROOT_AGENT_BINDING",
                    sanitize(e.getMessage())));
            result.refreshOverallStatus();
            return result;
        }

        addCheck(result, "AGENT_REGISTERED", () -> requireAgent(
                route.agentId(), credential.getTenantId(), safeForm.getModelConfigId()));
        addCheck(result, "CLIENT_APP_SKILL_GRANT", () -> skillRegistryService.checkClientAppSkillAccess(
                credential.getTenantId(), credential.getClientAppId(), route.skillId()));
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
        addRequiredUpstreamRouteChecks(result, credential, safeForm);

        if (isCheckOk(result, "CLIENT_APP_SKILL_GRANT")) {
            SkillArtifactLinkDTO link = new SkillArtifactLinkDTO();
            link.setAvailable(true);
            link.setTreeUrl("/api/v1/open/skills/" + route.skillId() + "/files/tree");
            link.setSliceUrlTemplate("/api/v1/open/skills/" + route.skillId()
                    + "/files/slice?path={path}&startLine={startLine}&startColumn={startColumn}&maxChars={maxChars}");
            result.setSkillArtifact(link);
        }

        result.refreshOverallStatus();
        return result;
    }

    private void addRequiredUpstreamRouteChecks(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            AgentReadinessPreflightForm form) {
        for (String upstreamRef : resolveRequiredUpstreamRefs(form.getContext())) {
            RequiredUpstreamRoute route = addRequiredUpstreamRouteCheck(result, credential, upstreamRef);
            if (route != null && StringUtils.hasText(route.userTokenHeader())) {
                addRequiredUpstreamUserTokenCheck(result, credential, form.getUpstreamUserId(), upstreamRef);
            }
        }
    }

    private RequiredUpstreamRoute addRequiredUpstreamRouteCheck(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            String upstreamRef) {
        try {
            RequiredUpstreamRoute route = resolveRequiredUpstreamRoute(
                    credential.getTenantId(), credential.getClientAppId(), upstreamRef);
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    "UPSTREAM_ROUTE:" + upstreamRef,
                    route.source() + " upstream route resolved"));
            return route;
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "UPSTREAM_ROUTE:" + upstreamRef,
                    sanitize(e.getMessage())));
            return null;
        }
    }

    private void addRequiredUpstreamUserTokenCheck(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            String upstreamUserId,
            String upstreamRef) {
        String checkCode = "UPSTREAM_USER_TOKEN:" + upstreamRef;
        if (!StringUtils.hasText(upstreamUserId)) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    checkCode,
                    "upstreamUserId is required for upstream token binding"));
            return;
        }
        addCheck(result, checkCode, () -> userGrantService.resolveUpstreamUserToken(
                credential.getTenantId(), credential.getClientAppId(), upstreamUserId));
    }

    private RequiredUpstreamRoute resolveRequiredUpstreamRoute(
            String tenantId,
            String clientAppId,
            String upstreamRef) {
        String baseUrl = environment.getProperty(UPSTREAM_PROPERTY_PREFIX + upstreamRef + ".url");
        String userTokenHeader = environment.getProperty(UPSTREAM_PROPERTY_PREFIX + upstreamRef + ".user-token-header");
        if (StringUtils.hasText(baseUrl)) {
            return new RequiredUpstreamRoute(baseUrl, trimToNull(userTokenHeader), "environment");
        }

        Optional<ClientAppUpstreamRouteService.ResolvedUpstreamRoute> route =
                upstreamRouteService.resolveEnabledRoute(tenantId, clientAppId, upstreamRef);
        if (route.isPresent()) {
            return new RequiredUpstreamRoute(
                    route.get().getBaseUrl(),
                    trimToNull(route.get().getUserTokenHeader()),
                    "client-app");
        }
        throw new IllegalArgumentException("Unauthorized or unconfigured upstream_ref: " + upstreamRef);
    }

    private Set<String> resolveRequiredUpstreamRefs(Map<String, Object> context) {
        Set<String> refs = new LinkedHashSet<>();
        if (context == null) {
            return refs;
        }
        addUpstreamRefs(refs, context.get("requiredUpstreamRefs"));
        addUpstreamRefs(refs, context.get("required_upstream_refs"));
        addUpstreamRefs(refs, context.get("upstreamRefs"));
        addUpstreamRefs(refs, context.get("upstream_ref"));
        return refs;
    }

    private void addUpstreamRefs(Set<String> refs, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addUpstreamRef(refs, item);
            }
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addUpstreamRef(refs, Array.get(value, i));
            }
            return;
        }
        addUpstreamRef(refs, value);
    }

    private void addUpstreamRef(Set<String> refs, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!StringUtils.hasText(text)) {
            return;
        }
        for (String part : text.split(",")) {
            if (StringUtils.hasText(part)) {
                refs.add(part.trim());
            }
        }
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

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record RequiredUpstreamRoute(String baseUrl, String userTokenHeader, String source) {
    }
}
