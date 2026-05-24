package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Central resolver for upstream-visible A2Agent runtime resources.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class A2AgentResourceResolver {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final LlmModelManager llmModelManager;
    private final ClientAppService clientAppService;
    private final WorkingDirectoryRepository workingDirectoryRepository;
    private final BusinessCodingAgentRepository agentRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final BusinessAgentDirectoryBindingRepository agentDirectoryBindingRepository;
    private final BusinessAgentModelBindingRepository agentModelBindingRepository;

    public record ResolvedModelResource(
            String modelConfigId,
            String requestedModelConfigId,
            String requestedModelVariant,
            LlmModelCategory category,
            String modelName,
            String modelNameSource,
            String workerBackend,
            String source) {
    }

    public record ResolvedWorkspaceResource(
            String directoryId,
            String physicalWorkerId,
            WorkspaceScope workspaceScope,
            WorkingDirectoryResolverType resolverType,
            String workdir,
            List<String> allowedDirs,
            boolean readOnly,
            Object quotaPolicy,
            Object retentionPolicy,
            Object concurrencyPolicy,
            String source) {
    }

    public record ResolvedAgentResource(
            String agentId,
            ResourceOwnerType ownerType,
            String ownerId,
            String clientAppId,
            String skillId,
            String workerPoolId,
            ResourceOwnerType workerPoolOwnerType,
            String workerPoolOwnerId,
            String workerPoolSource,
            String workerBackend,
            String defaultModelConfigId,
            String defaultModelName,
            String defaultDirectoryId,
            String source) {
    }

    @Transactional(readOnly = true)
    public ResolvedAgentResource resolveRequiredAgent(String tenantId,
                                                     String clientAppId,
                                                     String upstreamUserId,
                                                     String agentId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(agentId, "agentId is required");

        ClientAppEntity clientApp = clientAppService.requireClientApp(tenantId, clientAppId);
        CodingAgentEntity agent = agentRepository.findByAgentIdAndTenantId(agentId.trim(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + agentId));
        validateAgentVisibility(tenantId, clientApp, agent);

        String skillId = resolveAgentSkillId(agent);
        String workerPoolId = trimToNull(agent.getWorkerId());
        if (workerPoolId == null) {
            throw new IllegalStateException("agent worker binding is not configured: " + agent.getAgentId());
        }
        BizWorkerPoolEntity workerPool = requireVisibleWorkerPool(tenantId, clientApp, agent, workerPoolId);

        ResolvedAgentResource resolved = new ResolvedAgentResource(
                agent.getAgentId(),
                agent.getOwnerType(),
                agent.getOwnerId(),
                agent.getClientAppId(),
                skillId,
                workerPoolId,
                workerPool.getOwnerType(),
                workerPool.getOwnerId(),
                "WORKER_POOL:" + workerPool.getOwnerType(),
                trimToNull(workerPool.getWorkerBackend()),
                trimToNull(agent.getDefaultModelConfigId()),
                trimToNull(agent.getDefaultModel()),
                trimToNull(agent.getDefaultDirectoryId()),
                "AGENT:" + agent.getOwnerType());
        log.info("Resolved A2Agent resource: tenantId={}, clientAppId={}, upstreamUserId={}, agentId={}, ownerType={}, source={}, skillId={}, workerPoolId={}, workerPoolOwnerType={}, workerPoolSource={}, workerBackend={}, defaultModelConfigId={}, defaultModelName={}, defaultDirectoryId={}",
                tenantId,
                clientAppId,
                upstreamUserId,
                resolved.agentId(),
                resolved.ownerType(),
                resolved.source(),
                resolved.skillId(),
                resolved.workerPoolId(),
                resolved.workerPoolOwnerType(),
                resolved.workerPoolSource(),
                resolved.workerBackend(),
                resolved.defaultModelConfigId(),
                resolved.defaultModelName(),
                resolved.defaultDirectoryId());
        return resolved;
    }

    @Transactional(readOnly = true)
    public String resolveRequiredModelConfigId(String tenantId,
                                               String clientAppId,
                                               String requestedModelConfigId,
                                               LlmModelCategory category) {
        return resolveRequiredModel(tenantId, clientAppId, requestedModelConfigId, category).modelConfigId();
    }

    @Transactional(readOnly = true)
    public ResolvedModelResource resolveRequiredModel(String tenantId,
                                                      String clientAppId,
                                                      String requestedModelConfigId,
                                                      LlmModelCategory category) {
        return resolveRequiredModel(tenantId, clientAppId, requestedModelConfigId, null, null, category);
    }

    @Transactional(readOnly = true)
    public ResolvedModelResource resolveRequiredModel(String tenantId,
                                                      String clientAppId,
                                                      String requestedModelConfigId,
                                                      String requestedModelVariant,
                                                      String defaultModelName,
                                                      LlmModelCategory category) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        String normalizedRequestedModelConfigId = trimToNull(requestedModelConfigId);
        String normalizedRequestedModelVariant = trimToNull(requestedModelVariant);
        String resolvedModelConfigId = modelConfigGrantService.resolveEffectiveModelConfigId(
                tenantId,
                clientAppId,
                normalizedRequestedModelConfigId,
                category);
        LlmModelConfigDTO modelConfig = requireResolvedModelConfig(resolvedModelConfigId);
        ModelNameResolution modelName = resolveModelName(
                modelConfig,
                normalizedRequestedModelVariant,
                defaultModelName);
        ResolvedModelResource resolved = new ResolvedModelResource(
                resolvedModelConfigId,
                normalizedRequestedModelConfigId,
                normalizedRequestedModelVariant,
                category,
                modelName.modelName(),
                modelName.source(),
                trimToNull(modelConfig.getWorkerBackend()),
                StringUtils.hasText(normalizedRequestedModelConfigId)
                        ? "REQUESTED_MODEL_GRANT"
                        : "DEFAULT_MODEL_GRANT");
        log.info("Resolved A2Agent model resource: tenantId={}, clientAppId={}, category={}, source={}, modelConfigId={}, requestedModelConfigId={}, requestedModelVariant={}, modelName={}, modelNameSource={}, workerBackend={}",
                tenantId,
                clientAppId,
                category,
                resolved.source(),
                resolved.modelConfigId(),
                resolved.requestedModelConfigId(),
                resolved.requestedModelVariant(),
                resolved.modelName(),
                resolved.modelNameSource(),
                resolved.workerBackend());
        return resolved;
    }

    @Transactional(readOnly = true)
    public String resolveRequiredModelConfigIdForAgent(String tenantId,
                                                       String clientAppId,
                                                       ResolvedAgentResource agentResource,
                                                       String requestedModelConfigId,
                                                       LlmModelCategory category) {
        return resolveRequiredModelForAgent(
                tenantId,
                clientAppId,
                agentResource,
                requestedModelConfigId,
                category).modelConfigId();
    }

    @Transactional(readOnly = true)
    public ResolvedModelResource resolveRequiredModelForAgent(String tenantId,
                                                              String clientAppId,
                                                              ResolvedAgentResource agentResource,
                                                              String requestedModelConfigId,
                                                              LlmModelCategory category) {
        return resolveRequiredModelForAgent(
                tenantId,
                clientAppId,
                agentResource,
                requestedModelConfigId,
                null,
                category);
    }

    @Transactional(readOnly = true)
    public ResolvedModelResource resolveRequiredModelForAgent(String tenantId,
                                                              String clientAppId,
                                                              ResolvedAgentResource agentResource,
                                                              String requestedModelConfigId,
                                                              String requestedModelVariant,
                                                              LlmModelCategory category) {
        requireAgentResource(agentResource);
        ResolvedModelResource modelResource = resolveRequiredModel(
                tenantId,
                clientAppId,
                requestedModelConfigId,
                requestedModelVariant,
                agentResource.defaultModelName(),
                category);
        String source = resolveAgentModelSource(
                tenantId,
                agentResource,
                modelResource.modelConfigId(),
                modelResource.source());
        ResolvedModelResource resolved = new ResolvedModelResource(
                modelResource.modelConfigId(),
                modelResource.requestedModelConfigId(),
                modelResource.requestedModelVariant(),
                modelResource.category(),
                modelResource.modelName(),
                modelResource.modelNameSource(),
                modelResource.workerBackend(),
                source);
        log.info("Resolved A2Agent agent model binding: tenantId={}, clientAppId={}, agentId={}, modelConfigId={}, modelName={}, modelNameSource={}, source={}",
                tenantId,
                clientAppId,
                agentResource.agentId(),
                resolved.modelConfigId(),
                resolved.modelName(),
                resolved.modelNameSource(),
                resolved.source());
        return resolved;
    }

    @Transactional(readOnly = true)
    public String resolveOptionalModelConfigId(String tenantId,
                                               String clientAppId,
                                               LlmModelCategory category) {
        return resolveOptionalModel(tenantId, clientAppId, category)
                .map(ResolvedModelResource::modelConfigId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedModelResource> resolveOptionalModel(String tenantId,
                                                               String clientAppId,
                                                               LlmModelCategory category) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        return modelConfigGrantService.tryResolveEffectiveModelConfigId(tenantId, clientAppId, null, category)
                .map(modelConfigId -> {
                    LlmModelConfigDTO modelConfig = requireResolvedModelConfig(modelConfigId);
                    ResolvedModelResource resolved = new ResolvedModelResource(
                            modelConfigId,
                            null,
                            null,
                            category,
                            trimToNull(modelConfig.getModelName()),
                            "MODEL_CONFIG_DEFAULT",
                            trimToNull(modelConfig.getWorkerBackend()),
                            "DEFAULT_MODEL_GRANT");
                    log.info("Resolved optional A2Agent model resource: tenantId={}, clientAppId={}, category={}, source={}, modelConfigId={}, modelName={}, workerBackend={}",
                            tenantId,
                            clientAppId,
                            category,
                            resolved.source(),
                            resolved.modelConfigId(),
                            resolved.modelName(),
                            resolved.workerBackend());
                    return resolved;
                });
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedModelResource> resolveOptionalModelForAgent(String tenantId,
                                                                       String clientAppId,
                                                                       ResolvedAgentResource agentResource,
                                                                       LlmModelCategory category) {
        requireAgentResource(agentResource);
        return resolveOptionalModel(tenantId, clientAppId, category)
                .flatMap(modelResource -> {
                    try {
                        String source = resolveAgentModelSource(
                                tenantId,
                                agentResource,
                                modelResource.modelConfigId(),
                                modelResource.source());
                        return Optional.of(new ResolvedModelResource(
                                modelResource.modelConfigId(),
                                modelResource.requestedModelConfigId(),
                                modelResource.requestedModelVariant(),
                                modelResource.category(),
                                modelResource.modelName(),
                                modelResource.modelNameSource(),
                                modelResource.workerBackend(),
                                source));
                    } catch (SecurityException e) {
                        log.debug("Optional A2Agent model is not bound to agent: tenantId={}, clientAppId={}, agentId={}, modelConfigId={}",
                                tenantId,
                                clientAppId,
                                agentResource.agentId(),
                                modelResource.modelConfigId());
                        return Optional.empty();
                    }
                });
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedWorkspaceResource> resolveOptionalWorkspace(String tenantId,
                                                                       String clientAppId,
                                                                       String upstreamUserId,
                                                                       String requestedDirectoryId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        String normalizedDirectoryId = trimToNull(requestedDirectoryId);
        if (!StringUtils.hasText(normalizedDirectoryId)) {
            return Optional.empty();
        }
        return Optional.of(resolveRequiredWorkspace(tenantId, clientAppId, upstreamUserId, normalizedDirectoryId));
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedWorkspaceResource> resolveOptionalWorkspaceForAgent(String tenantId,
                                                                               String clientAppId,
                                                                               String upstreamUserId,
                                                                               ResolvedAgentResource agentResource,
                                                                               String requestedDirectoryId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireAgentResource(agentResource);
        String normalizedDirectoryId = trimToNull(requestedDirectoryId);
        if (!StringUtils.hasText(normalizedDirectoryId)) {
            return Optional.empty();
        }
        return Optional.of(resolveRequiredWorkspaceForAgent(
                tenantId,
                clientAppId,
                upstreamUserId,
                agentResource,
                normalizedDirectoryId));
    }

    @Transactional(readOnly = true)
    public ResolvedWorkspaceResource resolveRequiredWorkspace(String tenantId,
                                                             String clientAppId,
                                                             String upstreamUserId,
                                                             String directoryId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(directoryId, "directoryId is required");

        ClientAppEntity clientApp = clientAppService.requireClientApp(tenantId, clientAppId);
        WorkingDirectoryEntity directory = workingDirectoryRepository.findByDirectoryId(directoryId.trim())
                .orElseThrow(() -> new IllegalArgumentException("working directory not found: " + directoryId));
        validateWorkspaceVisibility(tenantId, clientApp, upstreamUserId, directory);

        String workdir = resolveWorkdir(directory);
        List<String> allowedDirs = resolveAllowedDirs(directory, workdir);
        Object quotaPolicy = parseOptionalJsonPolicy(directory.getQuotaJson(), "quotaJson", directory.getDirectoryId());
        Object retentionPolicy = parseOptionalJsonPolicy(
                directory.getRetentionPolicyJson(),
                "retentionPolicyJson",
                directory.getDirectoryId());
        Object concurrencyPolicy = parseOptionalJsonPolicy(
                directory.getConcurrencyPolicyJson(),
                "concurrencyPolicyJson",
                directory.getDirectoryId());
        ResolvedWorkspaceResource resolved = new ResolvedWorkspaceResource(
                directory.getDirectoryId(),
                trimToNull(directory.getWorkerId()),
                directory.getWorkspaceScope(),
                directory.getResolverType(),
                workdir,
                allowedDirs,
                Boolean.TRUE.equals(directory.getReadOnly()),
                quotaPolicy,
                retentionPolicy,
                concurrencyPolicy,
                "WORKING_DIRECTORY:" + directory.getWorkspaceScope());
        log.info("Resolved A2Agent workspace resource: tenantId={}, clientAppId={}, upstreamUserId={}, directoryId={}, physicalWorkerId={}, scope={}, resolverType={}, readOnly={}",
                tenantId,
                clientAppId,
                upstreamUserId,
                resolved.directoryId(),
                resolved.physicalWorkerId(),
                resolved.workspaceScope(),
                resolved.resolverType(),
                resolved.readOnly());
        return resolved;
    }

    @Transactional(readOnly = true)
    public ResolvedWorkspaceResource resolveRequiredWorkspaceForAgent(String tenantId,
                                                                     String clientAppId,
                                                                     String upstreamUserId,
                                                                     ResolvedAgentResource agentResource,
                                                                     String directoryId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(directoryId, "directoryId is required");
        requireAgentResource(agentResource);

        ResolvedWorkspaceResource workspaceResource = resolveRequiredWorkspace(
                tenantId,
                clientAppId,
                upstreamUserId,
                directoryId);
        String source = resolveAgentWorkspaceSource(tenantId, agentResource, workspaceResource.directoryId());
        ResolvedWorkspaceResource resolved = withWorkspaceSource(workspaceResource, source);
        log.info("Resolved A2Agent agent workspace binding: tenantId={}, clientAppId={}, upstreamUserId={}, agentId={}, directoryId={}, source={}",
                tenantId,
                clientAppId,
                upstreamUserId,
                agentResource.agentId(),
                resolved.directoryId(),
                resolved.source());
        return resolved;
    }

    private void validateWorkspaceVisibility(String tenantId,
                                             ClientAppEntity clientApp,
                                             String upstreamUserId,
                                             WorkingDirectoryEntity directory) {
        if (!tenantId.equals(directory.getTenantId())) {
            throw new SecurityException("working directory tenant mismatch: " + directory.getDirectoryId());
        }
        if (!Boolean.TRUE.equals(directory.getEnabled())) {
            throw new IllegalStateException("working directory is disabled: " + directory.getDirectoryId());
        }
        if (directory.getOwnerType() == null || !StringUtils.hasText(directory.getOwnerId())) {
            throw new IllegalStateException("working directory owner is not configured: " + directory.getDirectoryId());
        }
        if (directory.getWorkspaceScope() == null) {
            throw new IllegalStateException("working directory workspaceScope is not configured: " + directory.getDirectoryId());
        }
        if (directory.getResolverType() == null) {
            throw new IllegalStateException("working directory resolverType is not configured: " + directory.getDirectoryId());
        }

        String clientAppId = clientApp.getClientAppId();
        switch (directory.getWorkspaceScope()) {
            case USER_PRIVATE -> {
                requireOwner(directory, ResourceOwnerType.UPSTREAM_USER);
                if (!clientAppId.equals(directory.getClientAppId())
                        || !upstreamUserId.equals(directory.getUpstreamUserId())) {
                    throw new SecurityException("working directory is not visible to upstream user: " + directory.getDirectoryId());
                }
            }
            case CLIENT_APP_SHARED -> {
                requireOwner(directory, ResourceOwnerType.CLIENT_APP);
                if (!clientAppId.equals(directory.getClientAppId())
                        || !clientAppId.equals(directory.getOwnerId())) {
                    throw new SecurityException("working directory is not visible to client app: " + directory.getDirectoryId());
                }
            }
            case UPSTREAM_SYSTEM_SHARED -> {
                requireOwner(directory, ResourceOwnerType.UPSTREAM_SYSTEM);
                if (!StringUtils.hasText(clientApp.getUpstreamSystemId())
                        || !clientApp.getUpstreamSystemId().equals(directory.getOwnerId())) {
                    throw new SecurityException("working directory is not visible to upstream system: " + directory.getDirectoryId());
                }
            }
        }
    }

    private void validateAgentVisibility(String tenantId, ClientAppEntity clientApp, CodingAgentEntity agent) {
        if (!tenantId.equals(agent.getTenantId())) {
            throw new SecurityException("agent tenant mismatch: " + agent.getAgentId());
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new IllegalStateException("agent is disabled: " + agent.getAgentId());
        }
        if (agent.getOwnerType() == null || !StringUtils.hasText(agent.getOwnerId())) {
            throw new IllegalStateException("agent owner is not configured: " + agent.getAgentId());
        }

        String clientAppId = clientApp.getClientAppId();
        switch (agent.getOwnerType()) {
            case CLIENT_APP -> {
                if (!clientAppId.equals(agent.getOwnerId())
                        || !clientAppId.equals(agent.getClientAppId())) {
                    throw new SecurityException("agent is not visible to client app: " + agent.getAgentId());
                }
            }
            case UPSTREAM_SYSTEM -> {
                if (!StringUtils.hasText(clientApp.getUpstreamSystemId())
                        || !clientApp.getUpstreamSystemId().equals(agent.getOwnerId())) {
                    throw new SecurityException("agent is not visible to upstream system: " + agent.getAgentId());
                }
            }
            case PLATFORM, UPSTREAM_USER -> throw new SecurityException(
                    "agent ownerType is not allowed for upstream runtime: " + agent.getAgentId());
        }
    }

    private BizWorkerPoolEntity requireVisibleWorkerPool(String tenantId,
                                                         ClientAppEntity clientApp,
                                                         CodingAgentEntity agent,
                                                         String workerPoolId) {
        BizWorkerPoolEntity pool = workerPoolRepository.findByPoolIdAndTenantId(workerPoolId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("agent worker pool not found: " + workerPoolId));
        validateWorkerPoolVisibility(tenantId, clientApp, pool);
        return pool;
    }

    private void validateWorkerPoolVisibility(String tenantId,
                                              ClientAppEntity clientApp,
                                              BizWorkerPoolEntity pool) {
        if (!tenantId.equals(pool.getTenantId())) {
            throw new SecurityException("worker pool tenant mismatch: " + pool.getPoolId());
        }
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(pool.getStatus())) {
            throw new IllegalStateException("worker pool is disabled: " + pool.getPoolId());
        }
        if (pool.getOwnerType() == null || !StringUtils.hasText(pool.getOwnerId())) {
            throw new IllegalStateException("worker pool owner is not configured: " + pool.getPoolId());
        }

        switch (pool.getOwnerType()) {
            case PLATFORM -> {
                // Platform-owned pools are tenant-scoped shared infrastructure.
            }
            case UPSTREAM_SYSTEM -> {
                if (!StringUtils.hasText(clientApp.getUpstreamSystemId())
                        || !clientApp.getUpstreamSystemId().equals(pool.getOwnerId())) {
                    throw new SecurityException("worker pool is not visible to upstream system: " + pool.getPoolId());
                }
            }
            case CLIENT_APP, UPSTREAM_USER -> throw new SecurityException(
                    "worker pool ownerType is not allowed for runtime: " + pool.getPoolId());
        }
    }

    private void requireOwner(WorkingDirectoryEntity directory, ResourceOwnerType expectedOwnerType) {
        if (directory.getOwnerType() != expectedOwnerType) {
            throw new SecurityException("working directory ownerType mismatch: " + directory.getDirectoryId());
        }
    }

    private void requireAgentResource(ResolvedAgentResource agentResource) {
        if (agentResource == null || !StringUtils.hasText(agentResource.agentId())) {
            throw new IllegalArgumentException("agentResource is required");
        }
    }

    private String resolveAgentWorkspaceSource(String tenantId,
                                               ResolvedAgentResource agentResource,
                                               String directoryId) {
        String normalizedDirectoryId = trimToNull(directoryId);
        String defaultDirectoryId = trimToNull(agentResource.defaultDirectoryId());
        if (defaultDirectoryId != null && defaultDirectoryId.equals(normalizedDirectoryId)) {
            return "AGENT_DEFAULT_DIRECTORY";
        }
        boolean bound = agentDirectoryBindingRepository.findByTenantIdAndAgentIdAndDirectoryId(
                tenantId,
                agentResource.agentId(),
                normalizedDirectoryId).isPresent();
        if (!bound) {
            throw new SecurityException("working directory is not bound to agent: " + normalizedDirectoryId);
        }
        return "AGENT_WORKSPACE_BINDING";
    }

    private String resolveAgentModelSource(String tenantId,
                                           ResolvedAgentResource agentResource,
                                           String modelConfigId,
                                           String modelGrantSource) {
        String normalizedModelConfigId = trimToNull(modelConfigId);
        if (normalizedModelConfigId == null) {
            throw new IllegalArgumentException("modelConfigId is required");
        }
        String defaultModelConfigId = trimToNull(agentResource.defaultModelConfigId());
        if (defaultModelConfigId != null && defaultModelConfigId.equals(normalizedModelConfigId)) {
            return "AGENT_DEFAULT_MODEL:" + modelGrantSource;
        }
        boolean bound = agentModelBindingRepository.findByTenantIdAndAgentIdAndModelConfigId(
                tenantId,
                agentResource.agentId(),
                normalizedModelConfigId).isPresent();
        if (!bound) {
            throw new SecurityException("model config is not bound to agent: " + normalizedModelConfigId);
        }
        return "AGENT_MODEL_BINDING:" + modelGrantSource;
    }

    private ResolvedWorkspaceResource withWorkspaceSource(ResolvedWorkspaceResource workspaceResource, String source) {
        return new ResolvedWorkspaceResource(
                workspaceResource.directoryId(),
                workspaceResource.physicalWorkerId(),
                workspaceResource.workspaceScope(),
                workspaceResource.resolverType(),
                workspaceResource.workdir(),
                workspaceResource.allowedDirs(),
                workspaceResource.readOnly(),
                workspaceResource.quotaPolicy(),
                workspaceResource.retentionPolicy(),
                workspaceResource.concurrencyPolicy(),
                source + ":" + workspaceResource.workspaceScope());
    }

    private LlmModelConfigDTO requireResolvedModelConfig(String modelConfigId) {
        return llmModelManager.getModelConfig(modelConfigId)
                .orElseThrow(() -> new IllegalStateException("resolved model config not found: " + modelConfigId));
    }

    private ModelNameResolution resolveModelName(
            LlmModelConfigDTO modelConfig,
            String requestedModelVariant,
            String defaultModelName) {
        String requested = trimToNull(requestedModelVariant);
        String agentDefault = trimToNull(defaultModelName);
        String configDefault = trimToNull(modelConfig.getModelName());
        String effective = requested != null
                ? requested
                : (agentDefault != null ? agentDefault : configDefault);
        String source = requested != null
                ? "REQUESTED_MODEL_VARIANT"
                : (agentDefault != null ? "AGENT_DEFAULT_MODEL" : "MODEL_CONFIG_DEFAULT");
        validateModelNameAllowed(modelConfig, effective);
        return new ModelNameResolution(effective, source);
    }

    private void validateModelNameAllowed(LlmModelConfigDTO modelConfig, String effectiveModelName) {
        String normalized = trimToNull(effectiveModelName);
        if (normalized == null) {
            return;
        }
        List<String> availableModels = modelConfig.getAvailableModels();
        if (availableModels == null || availableModels.isEmpty()) {
            return;
        }
        boolean allowed = availableModels.stream()
                .map(this::trimToNull)
                .anyMatch(normalized::equals);
        if (!allowed) {
            String configDefault = trimToNull(modelConfig.getModelName());
            allowed = normalized.equals(configDefault);
        }
        if (!allowed) {
            throw new IllegalArgumentException("modelVariant is not allowed by model config availableModels: "
                    + normalized);
        }
    }

    private record ModelNameResolution(String modelName, String source) {
    }

    private String resolveWorkdir(WorkingDirectoryEntity directory) {
        String root = trimToNull(directory.getRootRef());
        if (root == null) {
            root = trimToNull(directory.getPath());
        }
        if (root == null) {
            throw new IllegalStateException("working directory root is not configured: " + directory.getDirectoryId());
        }
        return root;
    }

    private String resolveAgentSkillId(CodingAgentEntity agent) {
        String fromProfile = resolveAgentSkillIdFromProfile(agent);
        if (fromProfile != null) {
            return fromProfile;
        }
        String fromSkills = resolveAgentSkillIdFromSkills(agent);
        if (fromSkills != null) {
            return fromSkills;
        }
        return agent.getAgentId();
    }

    private String resolveAgentSkillIdFromProfile(CodingAgentEntity agent) {
        String raw = trimToNull(agent.getAgentProfile());
        if (raw == null) {
            return null;
        }
        try {
            java.util.Map<String, Object> profile = OBJECT_MAPPER.readValue(raw, new TypeReference<java.util.Map<String, Object>>() {});
            Object skillId = profile.get("skillId");
            if (skillId == null) {
                skillId = profile.get("skill_id");
            }
            return skillId instanceof String text ? trimToNull(text) : null;
        } catch (Exception e) {
            throw new IllegalStateException("agent profile is invalid: " + agent.getAgentId(), e);
        }
    }

    private String resolveAgentSkillIdFromSkills(CodingAgentEntity agent) {
        String raw = trimToNull(agent.getSkills());
        if (raw == null) {
            return null;
        }
        try {
            List<java.util.Map<String, Object>> skills = OBJECT_MAPPER.readValue(raw, new TypeReference<List<java.util.Map<String, Object>>>() {});
            for (java.util.Map<String, Object> skill : skills) {
                Object id = skill.get("id");
                if (id == null) {
                    id = skill.get("skillId");
                }
                if (id instanceof String text && StringUtils.hasText(text)) {
                    return text.trim();
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("agent skills are invalid: " + agent.getAgentId(), e);
        }
    }

    private List<String> resolveAllowedDirs(WorkingDirectoryEntity directory, String workdir) {
        String raw = trimToNull(directory.getAllowedPathPrefixesJson());
        if (raw == null) {
            return List.of(workdir);
        }
        try {
            List<String> values = OBJECT_MAPPER.readValue(raw, new TypeReference<List<String>>() {});
            List<String> cleaned = values.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
            return cleaned.isEmpty() ? List.of(workdir) : cleaned;
        } catch (Exception e) {
            throw new IllegalStateException("working directory allowedPathPrefixesJson is invalid: "
                    + directory.getDirectoryId(), e);
        }
    }

    private Object parseOptionalJsonPolicy(String raw, String fieldName, String directoryId) {
        String normalized = trimToNull(raw);
        if (normalized == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(normalized, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("working directory " + fieldName + " is invalid: " + directoryId, e);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
