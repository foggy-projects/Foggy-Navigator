package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessCheckDTO;
import com.foggy.navigator.business.agent.model.dto.AgentReadinessDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.PhysicalWorkerDiagnosticDTO;
import com.foggy.navigator.business.agent.model.dto.ResolvedClientAppCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.SkillArtifactLinkDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.AgentReadinessPreflightForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService;
import com.foggy.navigator.business.agent.service.A2AgentResourceResolver;
import com.foggy.navigator.business.agent.service.ClientAppService;
import com.foggy.navigator.business.agent.service.ClientAppUpstreamRouteService;
import com.foggy.navigator.business.agent.service.ClientAppUserGrantService;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.model.CodexConfig;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OpenApiAgentReadinessService {

    private static final String UPSTREAM_PROPERTY_PREFIX = "foggy.navigator.business.agent.upstreams.";
    private static final Pattern UPSTREAM_REF_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String BACKEND_CLAUDE_CODE = "CLAUDE_CODE";
    private static final String BACKEND_OPENAI_CODEX = "OPENAI_CODEX";
    private static final String BACKEND_LANGGRAPH_BIZ = "LANGGRAPH_BIZ";
    private static final String ROLE_CLAUDE_CODE = "claudeCode";
    private static final String ROLE_CODEX = "codex";
    private static final String ROLE_BIZ = "biz";
    private static final String SOURCE_CLAUDE_CODEX_CONFIG = "CLAUDE_WORKER_CODEX_CONFIG";

    private final UnifiedAgentResolver agentResolver;
    private final ClientAppService clientAppService;
    private final SkillRegistryService skillRegistryService;
    private final ClientAppUserGrantService userGrantService;
    private final A2AgentResourceResolver resourceResolver;
    private final ClientAppUpstreamRouteService upstreamRouteService;
    private final BusinessFunctionRegistryService functionRegistryService;
    private final OpenApiAgentRouteService agentRouteService;
    private final BizWorkerIdentityRepository workerIdentityRepository;
    private final ClaudeWorkerRepository claudeWorkerRepository;
    private final Environment environment;
    private final ObjectMapper objectMapper;

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
        result.setRequestedModelVariant(safeForm.getModelVariant());

        ClientAppEntity app = clientAppService.requireActiveClientApp(
                credential.getTenantId(), credential.getClientAppId());
        result.setClientAppName(app.getName());

        OpenApiAgentRouteService.ResolvedOpenApiAgentRoute route;
        try {
            route = agentRouteService.resolve(agentId, credential);
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    "ROOT_AGENT_BINDING",
                    "root agent binding resolved"));
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "ROOT_AGENT_BINDING",
                    sanitize(e.getMessage())));
            result.refreshOverallStatus();
            return result;
        }

        A2AgentResourceResolver.ResolvedAgentResource[] agentResourceRef =
                new A2AgentResourceResolver.ResolvedAgentResource[1];
        A2AgentResourceResolver.ResolvedWorkspaceResource[] workspaceResourceRef =
                new A2AgentResourceResolver.ResolvedWorkspaceResource[1];
        addCheck(result, "AGENT_REGISTERED", () -> requireAgent(
                route.agentId(), credential.getTenantId(), safeForm.getModelConfigId()));
        addCheck(result, "RUNTIME_AGENT_RESOURCE", () -> {
            if (!StringUtils.hasText(safeForm.getUpstreamUserId())) {
                throw new IllegalArgumentException("upstreamUserId is required");
            }
            A2AgentResourceResolver.ResolvedAgentResource agentResource = resourceResolver.resolveRequiredAgent(
                    credential.getTenantId(),
                    credential.getClientAppId(),
                    safeForm.getUpstreamUserId(),
                    route.agentId());
            agentResourceRef[0] = agentResource;
            applyAgentResource(result, agentResource);
        });
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
            String requestedModelConfigId = resolveRequestedModelConfigId(safeForm, agentResourceRef[0]);
            A2AgentResourceResolver.ResolvedModelResource modelResource = resourceResolver.resolveRequiredModelForAgent(
                    credential.getTenantId(),
                    credential.getClientAppId(),
                    agentResourceRef[0],
                    requestedModelConfigId,
                    safeForm.getModelVariant(),
                    LlmModelCategory.GENERAL);
            result.setEffectiveModelConfigId(modelResource.modelConfigId());
            result.setEffectiveModelName(modelResource.modelName());
            if (StringUtils.hasText(modelResource.workerBackend())) {
                result.setEffectiveWorkerBackend(modelResource.workerBackend());
            }
            result.setModelConfigSource(modelResource.source());
            result.setModelCategory(modelResource.category().name());
            validateAgentBackendCompatibility(agentResourceRef[0], modelResource);
        });
        addWorkspaceResourceCheckIfPossible(result, credential, safeForm, agentResourceRef[0], workspaceResourceRef);
        applyPhysicalWorkerDiagnostic(result, agentResourceRef[0], workspaceResourceRef[0]);
        addRequiredUpstreamRouteChecks(result, credential, safeForm);
        addBusinessFunctionAdapterChecks(result, credential);
        addOwnerAwareRuntimeResourceCheck(result);

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

    private void applyAgentResource(
            AgentReadinessDTO result,
            A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        if (agentResource == null) {
            throw new IllegalStateException("agent resource was not resolved");
        }
        result.setAgentId(agentResource.agentId());
        result.setAgentOwnerType(agentResource.ownerType() != null ? agentResource.ownerType().name() : null);
        result.setAgentOwnerId(agentResource.ownerId());
        result.setAgentSource(agentResource.source());
        result.setSkillId(agentResource.skillId());
        result.setWorkerPoolId(agentResource.workerPoolId());
        result.setWorkerPoolOwnerType(agentResource.workerPoolOwnerType() != null
                ? agentResource.workerPoolOwnerType().name()
                : null);
        result.setWorkerPoolOwnerId(agentResource.workerPoolOwnerId());
        result.setWorkerPoolSource(agentResource.workerPoolSource());
        result.setInternalWorkerPoolId(agentResource.workerPoolId());
        result.setInternalWorkerPoolOwnerType(agentResource.workerPoolOwnerType() != null
                ? agentResource.workerPoolOwnerType().name()
                : null);
        result.setInternalWorkerPoolOwnerId(agentResource.workerPoolOwnerId());
        result.setInternalWorkerPoolSource(agentResource.workerPoolSource());
        if (StringUtils.hasText(agentResource.workerBackend())) {
            result.setEffectiveWorkerBackend(agentResource.workerBackend());
        }
        if (StringUtils.hasText(agentResource.physicalWorkerId())) {
            result.setEffectivePhysicalWorkerId(agentResource.physicalWorkerId());
        }
        result.setDefaultModelConfigId(agentResource.defaultModelConfigId());
        result.setDefaultModelName(agentResource.defaultModelName());
        result.setDefaultDirectoryId(agentResource.defaultDirectoryId());
    }

    private void validateAgentBackendCompatibility(
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource) {
        if (agentResource == null || modelResource == null) {
            return;
        }
        String agentBackend = trimToNull(agentResource.workerBackend());
        String modelBackend = trimToNull(modelResource.workerBackend());
        if (agentBackend != null && modelBackend != null && !agentBackend.equals(modelBackend)) {
            throw new IllegalStateException("model workerBackend " + modelBackend
                    + " does not match agent route backend " + agentBackend);
        }
    }

    private String resolveRequestedModelConfigId(
            AgentReadinessPreflightForm form,
            A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        String requested = trimToNull(form.getModelConfigId());
        if (requested != null) {
            return requested;
        }
        return agentResource != null ? trimToNull(agentResource.defaultModelConfigId()) : null;
    }

    private void addWorkspaceResourceCheckIfPossible(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            AgentReadinessPreflightForm form,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource[] workspaceResourceRef) {
        if (agentResource == null) {
            return;
        }
        String requestedDirectoryId = trimToNull(form.getDirectoryId());
        String effectiveDirectoryId = requestedDirectoryId != null
                ? requestedDirectoryId
                : trimToNull(agentResource.defaultDirectoryId());
        result.setRequestedDirectoryId(requestedDirectoryId);
        result.setEffectiveDirectoryId(effectiveDirectoryId);
        if (!StringUtils.hasText(effectiveDirectoryId)) {
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    "WORKSPACE_RESOURCE",
                    "no working directory requested or bound"));
            return;
        }
        addCheck(result, "WORKSPACE_RESOURCE", () -> {
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource = resourceResolver.resolveRequiredWorkspaceForAgent(
                    credential.getTenantId(),
                    credential.getClientAppId(),
                    form.getUpstreamUserId(),
                    agentResource,
                    effectiveDirectoryId);
            workspaceResourceRef[0] = workspaceResource;
            result.setEffectiveDirectoryId(workspaceResource.directoryId());
            if (StringUtils.hasText(workspaceResource.physicalWorkerId())) {
                result.setEffectivePhysicalWorkerId(workspaceResource.physicalWorkerId());
            }
            result.setWorkspaceScope(workspaceResource.workspaceScope() != null
                    ? workspaceResource.workspaceScope().name()
                    : null);
            result.setWorkspaceResolverType(workspaceResource.resolverType() != null
                    ? workspaceResource.resolverType().name()
                    : null);
            result.setWorkspaceReadOnly(workspaceResource.readOnly());
            result.setWorkspaceSource(workspaceResource.source());
        });
    }

    private void applyPhysicalWorkerDiagnostic(
            AgentReadinessDTO result,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        String workerId = trimToNull(result.getEffectivePhysicalWorkerId());
        PhysicalWorkerDiagnosticDTO diagnostic = null;
        if (workerId != null) {
            diagnostic = buildPhysicalWorkerDiagnostic(
                    workerId,
                    firstNonBlank(result.getEffectiveWorkerBackend(),
                            agentResource != null ? agentResource.workerBackend() : null),
                    resolvePhysicalWorkerSource(workerId, agentResource, workspaceResource, result),
                    isExecutionWorker(workerId, agentResource, workspaceResource),
                    workspaceResource != null && workerId.equals(trimToNull(workspaceResource.physicalWorkerId())));
            result.setPhysicalWorkerDiagnostic(diagnostic);
        }
        result.setPhysicalWorkerDiagnostics(buildPhysicalWorkerRoleDiagnostics(
                result, agentResource, workspaceResource, diagnostic));
    }

    private PhysicalWorkerDiagnosticDTO buildPhysicalWorkerDiagnostic(
            String workerId,
            String workerBackend,
            String source,
            boolean executionWorker,
            boolean directoryWorker) {
        PhysicalWorkerDiagnosticDTO diagnostic = new PhysicalWorkerDiagnosticDTO();
        diagnostic.setPhysicalWorkerId(workerId);
        diagnostic.setWorkerBackend(workerBackend);
        diagnostic.setSource(source);
        diagnostic.setExecutionWorker(executionWorker);
        diagnostic.setDirectoryWorker(directoryWorker);
        enrichFromWorkerIdentity(diagnostic, workerId);
        enrichFromClaudeWorker(diagnostic, workerId);
        return diagnostic;
    }

    private List<PhysicalWorkerDiagnosticDTO> buildPhysicalWorkerRoleDiagnostics(
            AgentReadinessDTO result,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            PhysicalWorkerDiagnosticDTO primaryDiagnostic) {
        List<PhysicalWorkerDiagnosticDTO> diagnostics = new ArrayList<>();
        String directoryWorkerId = workspaceResource != null
                ? trimToNull(workspaceResource.physicalWorkerId())
                : null;
        if (directoryWorkerId != null) {
            appendDirectoryWorkerDiagnostic(diagnostics, result, agentResource, workspaceResource, directoryWorkerId);
        }

        String executionWorkerId = agentResource != null
                ? trimToNull(agentResource.physicalWorkerId())
                : null;
        if (executionWorkerId == null) {
            executionWorkerId = trimToNull(result.getEffectivePhysicalWorkerId());
        }
        if (executionWorkerId != null) {
            appendExecutionWorkerDiagnostic(diagnostics, result, agentResource, workspaceResource, executionWorkerId);
        }

        if (diagnostics.isEmpty() && primaryDiagnostic != null) {
            PhysicalWorkerDiagnosticDTO fallback = copyDiagnostic(primaryDiagnostic);
            fallback.setRole(resolveRole(fallback));
            appendOrMergeDiagnostic(diagnostics, fallback);
        }
        return diagnostics;
    }

    private void appendDirectoryWorkerDiagnostic(
            List<PhysicalWorkerDiagnosticDTO> diagnostics,
            AgentReadinessDTO result,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            String workerId) {
        PhysicalWorkerDiagnosticDTO diagnostic = buildPhysicalWorkerDiagnostic(
                workerId,
                null,
                resolvePhysicalWorkerSource(workerId, agentResource, workspaceResource, result),
                false,
                true);
        diagnostic.setRole(resolveRole(diagnostic));
        if (ROLE_CLAUDE_CODE.equals(diagnostic.getRole())) {
            diagnostic.setWorkerBackend(BACKEND_CLAUDE_CODE);
        }
        appendOrMergeDiagnostic(diagnostics, diagnostic);
    }

    private void appendExecutionWorkerDiagnostic(
            List<PhysicalWorkerDiagnosticDTO> diagnostics,
            AgentReadinessDTO result,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            String workerId) {
        String workerBackend = firstNonBlank(result.getEffectiveWorkerBackend(),
                agentResource != null ? agentResource.workerBackend() : null);
        if (isBackend(workerBackend, BACKEND_OPENAI_CODEX)
                && appendCodexConfigDiagnostic(diagnostics, workerId)) {
            return;
        }
        PhysicalWorkerDiagnosticDTO diagnostic = buildPhysicalWorkerDiagnostic(
                workerId,
                workerBackend,
                resolvePhysicalWorkerSource(workerId, agentResource, workspaceResource, result),
                true,
                workspaceResource != null && workerId.equals(trimToNull(workspaceResource.physicalWorkerId())));
        diagnostic.setRole(resolveRole(diagnostic));
        appendOrMergeDiagnostic(diagnostics, diagnostic);
    }

    private boolean appendCodexConfigDiagnostic(List<PhysicalWorkerDiagnosticDTO> diagnostics, String workerId) {
        Optional<ClaudeWorkerEntity> worker = claudeWorkerRepository.findByWorkerId(workerId);
        if (worker.isEmpty()) {
            return false;
        }
        CodexConfig codexConfig = worker.get().getCodexConfig();
        if (codexConfig == null || !StringUtils.hasText(codexConfig.getBaseUrl())) {
            return false;
        }
        PhysicalWorkerDiagnosticDTO diagnostic = buildPhysicalWorkerDiagnostic(
                workerId,
                BACKEND_OPENAI_CODEX,
                SOURCE_CLAUDE_CODEX_CONFIG,
                true,
                false);
        diagnostic.setRole(ROLE_CODEX);
        diagnostic.setBaseUrl(scrubUrl(codexConfig.getBaseUrl()));
        appendOrMergeDiagnostic(diagnostics, diagnostic);
        return true;
    }

    private void appendOrMergeDiagnostic(
            List<PhysicalWorkerDiagnosticDTO> diagnostics,
            PhysicalWorkerDiagnosticDTO candidate) {
        for (PhysicalWorkerDiagnosticDTO existing : diagnostics) {
            if (sameText(existing.getRole(), candidate.getRole())
                    && sameText(existing.getPhysicalWorkerId(), candidate.getPhysicalWorkerId())
                    && sameNullableText(existing.getBaseUrl(), candidate.getBaseUrl())) {
                existing.setExecutionWorker(Boolean.TRUE.equals(existing.getExecutionWorker())
                        || Boolean.TRUE.equals(candidate.getExecutionWorker()));
                existing.setDirectoryWorker(Boolean.TRUE.equals(existing.getDirectoryWorker())
                        || Boolean.TRUE.equals(candidate.getDirectoryWorker()));
                existing.setWorkerBackend(firstNonBlank(existing.getWorkerBackend(), candidate.getWorkerBackend()));
                existing.setWorkerName(firstNonBlank(existing.getWorkerName(), candidate.getWorkerName()));
                existing.setBaseUrl(firstNonBlank(existing.getBaseUrl(), candidate.getBaseUrl()));
                existing.setStatus(firstNonBlank(existing.getStatus(), candidate.getStatus()));
                existing.setHealthStatus(firstNonBlank(existing.getHealthStatus(), candidate.getHealthStatus()));
                existing.setVersion(firstNonBlank(existing.getVersion(), candidate.getVersion()));
                existing.setHostname(firstNonBlank(existing.getHostname(), candidate.getHostname()));
                existing.setLastHeartbeat(firstNonBlank(existing.getLastHeartbeat(), candidate.getLastHeartbeat()));
                existing.setSource(firstNonBlank(existing.getSource(), candidate.getSource()));
                return;
            }
        }
        diagnostics.add(candidate);
    }

    private String resolveRole(PhysicalWorkerDiagnosticDTO diagnostic) {
        String backend = trimToNull(diagnostic.getWorkerBackend());
        if (isBackend(backend, BACKEND_OPENAI_CODEX)) {
            return ROLE_CODEX;
        }
        if (isBackend(backend, BACKEND_LANGGRAPH_BIZ)) {
            return ROLE_BIZ;
        }
        if (isBackend(backend, BACKEND_CLAUDE_CODE)
                || claudeWorkerRepository.findByWorkerId(diagnostic.getPhysicalWorkerId()).isPresent()) {
            return ROLE_CLAUDE_CODE;
        }
        return "physicalWorker";
    }

    private PhysicalWorkerDiagnosticDTO copyDiagnostic(PhysicalWorkerDiagnosticDTO source) {
        PhysicalWorkerDiagnosticDTO copy = new PhysicalWorkerDiagnosticDTO();
        copy.setRole(source.getRole());
        copy.setPhysicalWorkerId(source.getPhysicalWorkerId());
        copy.setWorkerName(source.getWorkerName());
        copy.setWorkerBackend(source.getWorkerBackend());
        copy.setBaseUrl(source.getBaseUrl());
        copy.setStatus(source.getStatus());
        copy.setHealthStatus(source.getHealthStatus());
        copy.setVersion(source.getVersion());
        copy.setHostname(source.getHostname());
        copy.setLastHeartbeat(source.getLastHeartbeat());
        copy.setSource(source.getSource());
        copy.setExecutionWorker(source.getExecutionWorker());
        copy.setDirectoryWorker(source.getDirectoryWorker());
        return copy;
    }

    private boolean isBackend(String actual, String expected) {
        return expected.equalsIgnoreCase(trimToNull(actual));
    }

    private boolean sameText(String left, String right) {
        String normalizedLeft = trimToNull(left);
        String normalizedRight = trimToNull(right);
        return normalizedLeft != null && normalizedLeft.equals(normalizedRight);
    }

    private boolean sameNullableText(String left, String right) {
        String normalizedLeft = trimToNull(left);
        String normalizedRight = trimToNull(right);
        if (normalizedLeft == null || normalizedRight == null) {
            return true;
        }
        return normalizedLeft.equals(normalizedRight);
    }

    private void enrichFromWorkerIdentity(PhysicalWorkerDiagnosticDTO diagnostic, String workerId) {
        Optional<BizWorkerIdentityEntity> identity = workerIdentityRepository.findByWorkerId(workerId);
        if (identity.isEmpty()) {
            return;
        }
        BizWorkerIdentityEntity entity = identity.get();
        diagnostic.setWorkerBackend(firstNonBlank(diagnostic.getWorkerBackend(), entity.getWorkerBackend()));
        diagnostic.setBaseUrl(scrubUrl(entity.getBaseUrl()));
        diagnostic.setStatus(entity.getStatus());
        diagnostic.setHealthStatus(entity.getHealthStatus());
        diagnostic.setVersion(entity.getVersion());
        diagnostic.setWorkerName(firstNonBlank(
                diagnostic.getWorkerName(),
                labelValue(entity.getLabelsJson(), "workerName", "name")));
        diagnostic.setHostname(firstNonBlank(
                diagnostic.getHostname(),
                labelValue(entity.getLabelsJson(), "hostname", "host")));
    }

    private void enrichFromClaudeWorker(PhysicalWorkerDiagnosticDTO diagnostic, String workerId) {
        Optional<ClaudeWorkerEntity> worker = claudeWorkerRepository.findByWorkerId(workerId);
        if (worker.isEmpty()) {
            return;
        }
        ClaudeWorkerEntity entity = worker.get();
        diagnostic.setWorkerName(firstNonBlank(diagnostic.getWorkerName(), entity.getName()));
        diagnostic.setBaseUrl(firstNonBlank(diagnostic.getBaseUrl(), scrubUrl(entity.getBaseUrl())));
        diagnostic.setStatus(firstNonBlank(diagnostic.getStatus(), entity.getStatus()));
        diagnostic.setVersion(firstNonBlank(diagnostic.getVersion(), entity.getWorkerVersion()));
        diagnostic.setHostname(firstNonBlank(diagnostic.getHostname(), entity.getHostname()));
        if (entity.getLastHeartbeat() != null) {
            diagnostic.setLastHeartbeat(entity.getLastHeartbeat().toString());
        }
    }

    private String resolvePhysicalWorkerSource(
            String workerId,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            AgentReadinessDTO result) {
        if (workspaceResource != null && workerId.equals(trimToNull(workspaceResource.physicalWorkerId()))) {
            return workspaceResource.source();
        }
        if (agentResource != null && workerId.equals(trimToNull(agentResource.physicalWorkerId()))) {
            return agentResource.physicalWorkerSource();
        }
        return result.getWorkspaceSource();
    }

    private boolean isExecutionWorker(
            String workerId,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        String agentWorkerId = agentResource != null ? trimToNull(agentResource.physicalWorkerId()) : null;
        if (agentWorkerId != null) {
            return workerId.equals(agentWorkerId);
        }
        return workspaceResource != null && workerId.equals(trimToNull(workspaceResource.physicalWorkerId()));
    }

    private String labelValue(String labelsJson, String... keys) {
        if (!StringUtils.hasText(labelsJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(labelsJson);
            for (String key : keys) {
                String value = root.path(key).asText(null);
                if (StringUtils.hasText(value)) {
                    return value.trim();
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private String scrubUrl(String rawUrl) {
        String value = trimToNull(rawUrl);
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            URI safe = new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null);
            return safe.toString();
        } catch (Exception e) {
            return value
                    .replaceAll("(?i)(://)[^/@\\s]+@", "$1")
                    .replaceAll("(?i)([?&](?:token|access_token|api_key|key|secret|authorization)=)[^&#\\s]+", "$1[REDACTED]");
        }
    }

    private void addOwnerAwareRuntimeResourceCheck(AgentReadinessDTO result) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(result.getAgentId())) {
            missing.add("agentId");
        }
        if (!StringUtils.hasText(result.getEffectiveModelConfigId())) {
            missing.add("effectiveModelConfigId");
        }
        if (!StringUtils.hasText(result.getEffectiveWorkerBackend())) {
            missing.add("effectiveWorkerBackend");
        }
        if (StringUtils.hasText(result.getEffectiveDirectoryId())
                && !StringUtils.hasText(result.getEffectivePhysicalWorkerId())) {
            missing.add("effectivePhysicalWorkerId");
        }
        if (missing.isEmpty()) {
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    "OWNER_AWARE_RUNTIME_RESOURCES",
                    "owner-aware runtime resources resolved"));
            return;
        }
        result.getChecks().add(AgentReadinessCheckDTO.fail(
                "OWNER_AWARE_RUNTIME_RESOURCES",
                "missing owner-aware runtime resources: " + String.join(",", missing)));
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

    private void addBusinessFunctionAdapterChecks(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential) {
        List<BusinessFunctionSummaryDTO> summaries;
        try {
            summaries = functionRegistryService.listClientAppVisibleFunctionSummaries(
                    credential.getTenantId(), credential.getClientAppId());
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    "BUSINESS_FUNCTION_ADAPTER_SCAN",
                    sanitize(e.getMessage())));
            return;
        }
        if (summaries == null) {
            return;
        }
        for (BusinessFunctionSummaryDTO summary : summaries) {
            if (summary == null || !StringUtils.hasText(summary.getFunctionId())
                    || !StringUtils.hasText(summary.getVersion())) {
                continue;
            }
            addBusinessFunctionAdapterCheck(result, credential, summary);
        }
    }

    private void addBusinessFunctionAdapterCheck(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            BusinessFunctionSummaryDTO summary) {
        String functionId = summary.getFunctionId();
        String adapterCheckCode = "BUSINESS_FUNCTION_ADAPTER:" + functionId;
        try {
            BusinessFunctionRuntimeContextDTO context = functionRegistryService.resolveClientAppFunction(
                    credential.getTenantId(), credential.getClientAppId(), functionId, summary.getVersion());
            String upstreamRef = extractRestAdapterUpstreamRef(context.getAdapterConfigJson(), functionId);
            if (!StringUtils.hasText(upstreamRef)) {
                return;
            }
            if (!UPSTREAM_REF_PATTERN.matcher(upstreamRef).matches()) {
                result.getChecks().add(AgentReadinessCheckDTO.fail(
                        adapterCheckCode,
                        "BusinessFunction " + functionId + " adapter upstream_ref \"" + upstreamRef
                                + "\" is invalid; expected [A-Za-z0-9._-]{1,128}"));
                return;
            }
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    adapterCheckCode,
                    "REST adapter upstream_ref \"" + upstreamRef + "\" is valid"));
            addBusinessFunctionUpstreamRouteCheck(result, credential, functionId, upstreamRef);
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    adapterCheckCode,
                    sanitize(e.getMessage())));
        }
    }

    private String extractRestAdapterUpstreamRef(String adapterConfigJson, String functionId) throws Exception {
        if (!StringUtils.hasText(adapterConfigJson)) {
            return null;
        }
        JsonNode root = objectMapper.readTree(adapterConfigJson);
        String type = root.path("type").asText(null);
        boolean restAdapter = "rest".equalsIgnoreCase(type) || root.has("upstream_ref");
        if (!restAdapter) {
            return null;
        }
        String upstreamRef = root.path("upstream_ref").asText(null);
        if (!StringUtils.hasText(upstreamRef)) {
            throw new IllegalArgumentException("BusinessFunction " + functionId + " REST adapter requires upstream_ref");
        }
        return upstreamRef.trim();
    }

    private void addBusinessFunctionUpstreamRouteCheck(
            AgentReadinessDTO result,
            ResolvedClientAppCredentialDTO credential,
            String functionId,
            String upstreamRef) {
        String checkCode = "BUSINESS_FUNCTION_UPSTREAM_ROUTE:" + upstreamRef;
        try {
            RequiredUpstreamRoute route = resolveRequiredUpstreamRoute(
                    credential.getTenantId(), credential.getClientAppId(), upstreamRef);
            result.getChecks().add(AgentReadinessCheckDTO.ok(
                    checkCode,
                    "BusinessFunction " + functionId + " " + route.source() + " upstream route resolved"));
        } catch (Exception e) {
            result.getChecks().add(AgentReadinessCheckDTO.fail(
                    checkCode,
                    "BusinessFunction " + functionId + " upstream_ref \"" + upstreamRef
                            + "\" is not configured as enabled ClientApp route or JVM allowlist property: "
                            + sanitize(e.getMessage())));
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

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private record RequiredUpstreamRoute(String baseUrl, String userTokenHeader, String source) {
    }
}
