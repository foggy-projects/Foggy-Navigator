package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.LlmModelCategory;
import com.foggy.navigator.common.util.ProviderRouteRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessAgentTaskService {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final String TASK_DIRECTORY_REQUIRED = "TASK_DIRECTORY_REQUIRED";
    private static final String TASK_DIRECTORY_REQUIRED_MESSAGE =
            TASK_DIRECTORY_REQUIRED + ": directoryId is required for Actor-owned BizWorker task";
    private static final String BACKEND_LANGGRAPH_BIZ = ProviderRouteRegistry.BACKEND_LANGGRAPH_BIZ;
    private static final String SOURCE_BIZ_WORKER_IDENTITY = "BIZ_WORKER_IDENTITY";

    private final BusinessAgentTaskRepository taskRepository;
    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final ClientAppService clientAppService;
    private final BizWorkerPoolService bizWorkerPoolService;
    private final A2AgentResourceResolver resourceResolver;
    private final ClientAppUserGrantService userGrantService;
    private final SkillRegistryService skillRegistryService;
    private final BusinessAgentSessionService businessAgentSessionService;
    private final BizWorkerIdentityRepository workerIdentityRepository;
    private final BusinessTaskScopedTokenLifecycleService tokenLifecycleService;
    private final List<BusinessAgentWorkerTaskLauncher> workerTaskLaunchers;

    @Transactional
    public CreatedBusinessAgentTaskDTO createTask(String tenantId, String actorUserId, CreateBusinessAgentTaskForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(tenantId, "tenantId is required");
        requireText(actorUserId, "actorUserId is required");
        requireText(form.getClientAppId(), "clientAppId is required");
        requireText(form.getSessionId(), "sessionId is required");
        requireText(form.getUpstreamUserId(), "upstreamUserId is required");
        requireText(form.getAgentId(), "agentId is required");

        // 2. 校验 clientAppId 存在、属于当前 tenant、状态可用
        ClientAppEntity clientApp = clientAppService.requireActiveClientApp(tenantId, form.getClientAppId());

        // 校验 upstream user grant
        userGrantService.checkUpstreamUserAccess(tenantId, form.getClientAppId(), form.getUpstreamUserId());

        rejectLegacyRuntimeResourceSelectors(form);
        rejectLegacyWorkspaceSelectors(form);

        A2AgentResourceResolver.ResolvedAgentResource agentResource = resourceResolver.resolveRequiredAgent(
                tenantId,
                form.getClientAppId(),
                form.getUpstreamUserId(),
                form.getAgentId());
        String skillName = resolveSkillName(agentResource.skillId(), form.getSkillName());

        // 3. 由 Agent 绑定解析 worker route。新模型优先支持 PhysicalWorker，旧 WorkerPool 路由继续兼容。
        BizWorkerPoolEntity workerPool = null;
        if (StringUtils.hasText(agentResource.workerPoolId())) {
            workerPool = bizWorkerPoolService.requireAvailablePool(tenantId, agentResource.workerPoolId());
        }

        // 校验 client app skill grant
        skillRegistryService.checkClientAppSkillAccess(tenantId, form.getClientAppId(), agentResource.skillId());

        String finalModelConfigId;
        String finalModelName;
        String finalVisionModelConfigId;
        A2AgentResourceResolver.ResolvedModelResource finalModelResource;
        BusinessAgentTaskEntity existingResumeTask = null;
        String explicitRequestedModelConfigId = trimToNull(form.getRequestedModelConfigId());
        String explicitRequestedModelVariant = trimToNull(form.getModelVariant());
        String requestedModelConfigId = resolveRequestedModelConfigId(form, agentResource);

        if (StringUtils.hasText(form.getResumeFromTaskId())) {
            existingResumeTask = taskRepository.findByTaskId(form.getResumeFromTaskId())
                    .orElseThrow(() -> new IllegalArgumentException("resume task not found: " + form.getResumeFromTaskId()));

            if (!tenantId.equals(existingResumeTask.getTenantId()) ||
                !form.getClientAppId().equals(existingResumeTask.getClientAppId()) ||
                !form.getSessionId().equals(existingResumeTask.getSessionId())) {
                throw new IllegalArgumentException("resume task context mismatch");
            }
            if (!agentResource.agentId().equals(existingResumeTask.getAgentId())) {
                throw new IllegalArgumentException("cannot change agentId when resuming task");
            }
            if (StringUtils.hasText(explicitRequestedModelConfigId) &&
                !explicitRequestedModelConfigId.equals(existingResumeTask.getModelConfigId())) {
                throw new IllegalArgumentException("cannot change modelConfigId when resuming task");
            }
            if (StringUtils.hasText(explicitRequestedModelVariant) &&
                StringUtils.hasText(existingResumeTask.getModel()) &&
                !explicitRequestedModelVariant.equals(existingResumeTask.getModel())) {
                throw new IllegalArgumentException("cannot change modelVariant when resuming task");
            }
            finalModelResource = resourceResolver.resolveRequiredModelForAgent(
                    tenantId,
                    form.getClientAppId(),
                    agentResource,
                    existingResumeTask.getModelConfigId(),
                    null,
                    LlmModelCategory.GENERAL);
            validateAgentBackendCompatibility(agentResource, finalModelResource);
            finalModelConfigId = existingResumeTask.getModelConfigId();
            finalModelName = existingResumeTask.getModel();
            finalVisionModelConfigId = resolveOptionalVisionModelConfigId(tenantId, form.getClientAppId(), agentResource);
        } else {
            // 4, 5, 6. 新建 task 时必须调用 resolveEffectiveModelConfigId
            finalModelResource = resourceResolver.resolveRequiredModelForAgent(
                    tenantId,
                    form.getClientAppId(),
                    agentResource,
                    requestedModelConfigId,
                    explicitRequestedModelVariant,
                    LlmModelCategory.GENERAL);
            validateAgentBackendCompatibility(agentResource, finalModelResource);
            finalModelConfigId = finalModelResource.modelConfigId();
            finalModelName = finalModelResource.modelName();
            finalVisionModelConfigId = resolveOptionalVisionModelConfigId(tenantId, form.getClientAppId(), agentResource);
        }
        A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource = resolveWorkspaceResource(
                tenantId,
                form,
                existingResumeTask,
                agentResource);
        String workerBackend = firstNonBlank(
                agentResource.workerBackend(),
                workerPool != null ? workerPool.getWorkerBackend() : null,
                finalModelResource.workerBackend());
        requireTaskDirectoryForBizWorker(workerBackend, workspaceResource);
        String contextId = businessAgentSessionService.resolveReusableContextId(
                tenantId,
                form.getClientAppId(),
                form.getUpstreamUserId(),
                form.getContextId(),
                form.getSessionId());
        businessAgentSessionService.validateContextResourceCompatibility(
                tenantId,
                form.getClientAppId(),
                form.getUpstreamUserId(),
                contextId,
                agentResource.agentId(),
                agentResource.skillId(),
                workspaceResource != null ? workspaceResource.directoryId() : null,
                finalModelConfigId);

        // 7. task 创建后固定最终 modelConfigId
        BusinessAgentTaskEntity task = new BusinessAgentTaskEntity();
        task.setTaskId("bt_" + UUID.randomUUID().toString().replace("-", ""));
        task.setSessionId(form.getSessionId());
        task.setTenantId(tenantId);
        task.setClientAppId(form.getClientAppId());
        task.setUpstreamUserId(form.getUpstreamUserId());
        task.setNavigatorEffectiveUserId(actorUserId);
        task.setAgentId(agentResource.agentId());
        task.setSkillId(agentResource.skillId());
        task.setWorkerPoolId(resolveInternalWorkerRouteId(agentResource));
        task.setDirectoryId(workspaceResource != null ? workspaceResource.directoryId() : null);
        task.setModelConfigId(finalModelConfigId);
        task.setRequestedModelConfigId(form.getRequestedModelConfigId());
        task.setModel(finalModelName);
        task.setRequestedModelVariant(explicitRequestedModelVariant);
        task.setStatus(STATUS_CREATED);
        task = taskRepository.save(task);

        // Token must exist before the worker task starts so it can be passed as hidden runtime context.
        String plainToken = SecretTokenSupport.randomToken("btt_");
        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_" + UUID.randomUUID().toString().replace("-", ""));
        token.setTokenHash(SecretTokenSupport.sha256(plainToken));
        token.setTaskId(task.getTaskId());
        token.setSessionId(task.getSessionId());
        token.setTenantId(task.getTenantId());
        token.setClientAppId(task.getClientAppId());
        token.setUpstreamUserId(task.getUpstreamUserId());
        token.setNavigatorEffectiveUserId(task.getNavigatorEffectiveUserId());
        token.setSkillId(task.getSkillId());
        token.setWorkerPoolId(task.getWorkerPoolId());
        token.setModelConfigId(task.getModelConfigId());
        token.setStatus(STATUS_ACTIVE);
        token = tokenLifecycleService.issueNewToken(token, plainToken);
        registerRollbackRevocation(token.getTenantId(), token.getTokenId());

        BusinessAgentWorkerTaskLaunchResult launchResult;
        try {
            launchResult = launchWorkerTaskIfAvailable(
                    tenantId, actorUserId, task, workerPool, agentResource, finalModelResource, plainToken,
                    finalVisionModelConfigId, contextId, skillName, form, workspaceResource, clientApp);
        } catch (RuntimeException e) {
            revokeAfterDispatchFailure(token, e);
            throw e;
        }
        if (launchResult != null) {
            if (StringUtils.hasText(launchResult.getContextId())) {
                contextId = launchResult.getContextId();
            }
            task.setWorkerTaskId(launchResult.getWorkerTaskId());
            task.setWorkerSessionId(launchResult.getWorkerSessionId());
            task.setWorkerId(launchResult.getWorkerId());
            task.setWorkerProviderType(launchResult.getProviderType());
        }

        contextId = businessAgentSessionService
                .bindTask(task, contextId, form.getClientContextJson())
                .getContextId();

        if (launchResult != null && StringUtils.hasText(launchResult.getWorkerTaskId())) {
            task = taskRepository.save(task);
            tokenLifecycleService.bindIssuedTokenToWorkerTask(
                    tenantId,
                    token.getTokenId(),
                    plainToken,
                    task.getWorkerTaskId(),
                    task.getWorkerSessionId(),
                    task.getWorkerId());
        }

        CreatedBusinessAgentTaskDTO dto = new CreatedBusinessAgentTaskDTO();
        BusinessAgentTaskDTO baseDto = BusinessAgentTaskDTO.fromEntity(task);
        dto.setTaskId(baseDto.getTaskId());
        dto.setSessionId(baseDto.getSessionId());
        dto.setContextId(contextId);
        dto.setTenantId(baseDto.getTenantId());
        dto.setClientAppId(baseDto.getClientAppId());
        dto.setUpstreamUserId(baseDto.getUpstreamUserId());
        dto.setNavigatorEffectiveUserId(baseDto.getNavigatorEffectiveUserId());
        dto.setAgentId(baseDto.getAgentId());
        dto.setSkillId(baseDto.getSkillId());
        dto.setWorkerPoolId(baseDto.getWorkerPoolId());
        dto.setDirectoryId(baseDto.getDirectoryId());
        dto.setWorkerTaskId(baseDto.getWorkerTaskId());
        dto.setWorkerSessionId(baseDto.getWorkerSessionId());
        dto.setWorkerId(baseDto.getWorkerId());
        dto.setWorkerProviderType(baseDto.getWorkerProviderType());
        dto.setModelConfigId(baseDto.getModelConfigId());
        dto.setRequestedModelConfigId(baseDto.getRequestedModelConfigId());
        dto.setModel(baseDto.getModel());
        dto.setRequestedModelVariant(baseDto.getRequestedModelVariant());
        dto.setStatus(baseDto.getStatus());
        dto.setCreatedAt(baseDto.getCreatedAt());
        dto.setUpdatedAt(baseDto.getUpdatedAt());
        dto.setTaskScopedToken(plainToken);

        return dto;
    }

    @Transactional
    public String issueOpenApiTaskScopedToken(
            String tenantId,
            String actorUserId,
            String clientAppId,
            String upstreamUserId,
            String skillId,
            String sessionId,
            String requestedModelConfigId) {
        requireText(tenantId, "tenantId is required");
        requireText(actorUserId, "actorUserId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(skillId, "skillId is required");
        requireText(sessionId, "sessionId is required");

        clientAppService.requireActiveClientApp(tenantId, clientAppId);
        userGrantService.checkUpstreamUserAccess(tenantId, clientAppId, upstreamUserId);
        skillRegistryService.checkClientAppSkillAccess(tenantId, clientAppId, skillId);

        String finalModelConfigId = resourceResolver.resolveRequiredModelConfigId(
                tenantId,
                clientAppId,
                requestedModelConfigId,
                LlmModelCategory.GENERAL);

        String taskId = "obt_" + UUID.randomUUID().toString().replace("-", "");
        String plainToken = SecretTokenSupport.randomToken("btt_");

        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setTokenId("tst_" + UUID.randomUUID().toString().replace("-", ""));
        token.setTokenHash(SecretTokenSupport.sha256(plainToken));
        token.setTaskId(taskId);
        token.setSessionId(sessionId);
        token.setTenantId(tenantId);
        token.setClientAppId(clientAppId);
        token.setUpstreamUserId(upstreamUserId);
        token.setNavigatorEffectiveUserId(actorUserId);
        token.setSkillId(skillId);
        token.setWorkerPoolId("OPEN_API");
        token.setModelConfigId(finalModelConfigId);
        token.setStatus(STATUS_ACTIVE);
        token = tokenLifecycleService.issueNewToken(token, plainToken);
        registerRollbackRevocation(token.getTenantId(), token.getTokenId());
        return plainToken;
    }

    @Transactional(readOnly = true)
    public boolean hasOpenApiTaskScopedTokenForContext(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String contextId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(upstreamUserId, "upstreamUserId is required");
        requireText(contextId, "contextId is required");
        return tokenRepository.existsByTenantIdAndClientAppIdAndUpstreamUserIdAndSessionIdAndStatusAndExpiresAtAfter(
                tenantId,
                clientAppId,
                upstreamUserId,
                contextId,
                STATUS_ACTIVE,
                LocalDateTime.now());
    }

    public void bindOpenApiTaskScopedTokenToWorkerTask(
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId) {
        tokenLifecycleService.bindOpenApiTokenToWorkerTask(
                tenantId, plainToken, workerTaskId, workerSessionId);
    }

    @Transactional(readOnly = true)
    public BusinessAgentTaskDTO getTask(String tenantId, String taskId) {
        requireText(tenantId, "tenantId is required");
        requireText(taskId, "taskId is required");
        BusinessAgentTaskEntity task = taskRepository.findByTaskIdAndTenantId(taskId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        return BusinessAgentTaskDTO.fromEntity(task);
    }

    @Transactional(readOnly = true)
    public List<BusinessAgentTaskDTO> listTasksBySession(String tenantId, String sessionId) {
        requireText(tenantId, "tenantId is required");
        requireText(sessionId, "sessionId is required");
        return taskRepository.findBySessionIdAndTenantIdOrderByCreatedAtDesc(sessionId, tenantId)
                .stream()
                .map(BusinessAgentTaskDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO resolveTaskScopedToken(String plainToken) {
        requireText(plainToken, "plainToken is required");
        String hash = SecretTokenSupport.sha256(plainToken);
        BusinessTaskScopedTokenEntity token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));

        if (token.getRevokedAt() != null) {
            throw new IllegalStateException("token is revoked");
        }
        if (!STATUS_ACTIVE.equals(token.getStatus())) {
            throw new IllegalStateException("token is not active");
        }
        if (token.getExpiresAt() == null || !token.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("token is expired");
        }

        return com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO.fromEntity(token);
    }

    public void revokeTaskScopedToken(String tenantId, String tokenId, String revokedBy, String reason) {
        tokenLifecycleService.revokeTaskScopedToken(tenantId, tokenId, revokedBy, reason);
    }

    public void revokeOpenApiTaskScopedToken(
            String tenantId, String plainToken, String revokedBy, String reason) {
        tokenLifecycleService.revokeTaskScopedTokenByPlainToken(
                tenantId, plainToken, revokedBy, reason);
    }

    public int revokeTaskScopedTokensForTask(
            String tenantId, String taskId, String revokedBy, String reason) {
        return tokenLifecycleService.revokeTaskScopedTokensForTask(
                tenantId, taskId, revokedBy, reason);
    }

    private void registerRollbackRevocation(String tenantId, String tokenId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    tokenLifecycleService.revokeTaskScopedToken(
                            tenantId, tokenId, "system", "task creation transaction rolled back");
                } catch (RuntimeException revokeError) {
                    log.error("Failed to revoke task token after task transaction rollback: tokenId={}",
                            tokenId, revokeError);
                }
            }
        });
    }

    private void revokeAfterDispatchFailure(
            BusinessTaskScopedTokenEntity token, RuntimeException dispatchError) {
        try {
            tokenLifecycleService.revokeTaskScopedToken(
                    token.getTenantId(), token.getTokenId(), "system", "worker dispatch failed");
        } catch (RuntimeException revokeError) {
            dispatchError.addSuppressed(revokeError);
            log.error("Failed to revoke task token after worker dispatch failure: tokenId={}",
                    token.getTokenId(), revokeError);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String resolveSkillName(String skillId, String skillName) {
        String normalizedSkillId = skillId != null ? skillId.trim() : null;
        if (!StringUtils.hasText(skillName)) {
            return normalizedSkillId;
        }
        String normalizedSkillName = skillName.trim();
        if (StringUtils.hasText(normalizedSkillId) && !normalizedSkillId.equals(normalizedSkillName)) {
            throw new IllegalArgumentException("skillName must match the agent-bound skillId");
        }
        return normalizedSkillName;
    }

    private BusinessAgentWorkerTaskLaunchResult launchWorkerTaskIfAvailable(
            String tenantId,
            String actorUserId,
            BusinessAgentTaskEntity task,
            BizWorkerPoolEntity workerPool,
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            String taskScopedToken,
            String visionModelConfigId,
            String contextId,
            String skillName,
            CreateBusinessAgentTaskForm form,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource,
            ClientAppEntity clientApp) {
        if (workerTaskLaunchers == null || workerTaskLaunchers.isEmpty()) {
            return null;
        }
        String workerBackend = resolveWorkerBackend(agentResource, workerPool, modelResource);
        return workerTaskLaunchers.stream()
                .filter(Objects::nonNull)
                .filter(launcher -> workerBackend.equals(launcher.getWorkerBackend()))
                .findFirst()
                .map(launcher -> launcher.launch(BusinessAgentWorkerTaskLaunchRequest.builder()
                        .tenantId(tenantId)
                        .actorUserId(actorUserId)
                        .businessTaskId(task.getTaskId())
                        .sessionId(task.getSessionId())
                        .contextId(contextId)
                        .clientAppId(task.getClientAppId())
                        .upstreamUserId(task.getUpstreamUserId())
                        .agentId(task.getAgentId())
                        .skillId(task.getSkillId())
                        .skillName(skillName)
                        .workerPoolId(task.getWorkerPoolId())
                        .physicalWorkerId(resolveLaunchPhysicalWorkerId(agentResource, modelResource, clientApp))
                        .workerBackend(workerBackend)
                        .modelConfigId(task.getModelConfigId())
                        .model(task.getModel())
                        .visionModelConfigId(visionModelConfigId)
                        .directoryId(workspaceResource != null ? workspaceResource.directoryId() : null)
                        .workspaceScope(workspaceResource != null ? workspaceResource.workspaceScope().name() : null)
                        .workspaceResolverType(workspaceResource != null ? workspaceResource.resolverType().name() : null)
                        .workspaceReadOnly(workspaceResource != null ? workspaceResource.readOnly() : null)
                        .workspaceQuotaPolicy(workspaceResource != null ? workspaceResource.quotaPolicy() : null)
                        .workspaceRetentionPolicy(workspaceResource != null ? workspaceResource.retentionPolicy() : null)
                        .workspaceConcurrencyPolicy(workspaceResource != null ? workspaceResource.concurrencyPolicy() : null)
                        .taskScopedToken(taskScopedToken)
                        .workdir(workspaceResource != null ? workspaceResource.workdir() : null)
                        .allowedDirs(workspaceResource != null ? workspaceResource.allowedDirs() : null)
                        .allowedTools(cleanStringList(form.getAllowedTools()))
                        .build()))
                .orElse(null);
    }

    private String resolveWorkerBackend(
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            BizWorkerPoolEntity workerPool,
            A2AgentResourceResolver.ResolvedModelResource modelResource) {
        String workerBackend = agentResource != null ? trimToNull(agentResource.workerBackend()) : null;
        if (workerBackend != null) {
            return workerBackend;
        }
        if (workerPool != null && StringUtils.hasText(workerPool.getWorkerBackend())) {
            return workerPool.getWorkerBackend().trim();
        }
        String modelWorkerBackend = modelResource != null ? trimToNull(modelResource.workerBackend()) : null;
        if (modelWorkerBackend != null) {
            return modelWorkerBackend;
        }
        throw new IllegalStateException("agent worker backend is not configured");
    }

    private void requireTaskDirectoryForBizWorker(
            String workerBackend,
            A2AgentResourceResolver.ResolvedWorkspaceResource workspaceResource) {
        if (isBackend(workerBackend, BACKEND_LANGGRAPH_BIZ) && workspaceResource == null) {
            throw new IllegalArgumentException(TASK_DIRECTORY_REQUIRED_MESSAGE);
        }
    }

    private String resolveLaunchPhysicalWorkerId(
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource,
            ClientAppEntity clientApp) {
        String agentWorkerId = agentResource != null ? trimToNull(agentResource.physicalWorkerId()) : null;
        String workerBackend = firstNonBlank(
                agentResource != null ? agentResource.workerBackend() : null,
                modelResource != null ? modelResource.workerBackend() : null);
        if (isBackend(workerBackend, BACKEND_LANGGRAPH_BIZ)
                && agentResource != null
                && !StringUtils.hasText(agentResource.workerPoolId())
                && StringUtils.hasText(agentWorkerId)
                && !SOURCE_BIZ_WORKER_IDENTITY.equals(trimToNull(agentResource.physicalWorkerSource()))) {
            String workerHostBizWorkerId = resolveLatestWorkerHostBizIdentity(clientApp);
            if (StringUtils.hasText(workerHostBizWorkerId)) {
                return workerHostBizWorkerId;
            }
        }
        // The workspace worker owns filesystem access. Execution routing must come from the agent route.
        return agentWorkerId;
    }

    private String resolveLatestWorkerHostBizIdentity(ClientAppEntity clientApp) {
        if (clientApp == null || !StringUtils.hasText(clientApp.getUpstreamSystemId())) {
            return null;
        }
        List<BizWorkerIdentityEntity> identities = workerIdentityRepository
                .findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        clientApp.getUpstreamSystemId(),
                        BACKEND_LANGGRAPH_BIZ,
                        BizWorkerPoolService.STATUS_ENABLED,
                        BizWorkerPoolService.HEALTHY);
        if (identities == null) {
            return null;
        }
        return identities.stream()
                .map(BizWorkerIdentityEntity::getWorkerId)
                .map(this::trimToNull)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String resolveInternalWorkerRouteId(A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        String workerPoolId = agentResource != null ? trimToNull(agentResource.workerPoolId()) : null;
        if (workerPoolId != null) {
            return workerPoolId;
        }
        String physicalWorkerId = agentResource != null ? trimToNull(agentResource.physicalWorkerId()) : null;
        if (physicalWorkerId != null) {
            return physicalWorkerId;
        }
        throw new IllegalStateException("agent worker route is not configured");
    }

    private void validateAgentBackendCompatibility(
            A2AgentResourceResolver.ResolvedAgentResource agentResource,
            A2AgentResourceResolver.ResolvedModelResource modelResource) {
        String agentBackend = agentResource != null ? trimToNull(agentResource.workerBackend()) : null;
        String modelBackend = modelResource != null ? trimToNull(modelResource.workerBackend()) : null;
        if (agentBackend != null && modelBackend != null && !agentBackend.equals(modelBackend)) {
            throw new IllegalStateException("model workerBackend " + modelBackend
                    + " does not match agent route backend " + agentBackend);
        }
    }

    private A2AgentResourceResolver.ResolvedWorkspaceResource resolveWorkspaceResource(
            String tenantId,
            CreateBusinessAgentTaskForm form,
            BusinessAgentTaskEntity existingResumeTask,
            A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        String requestedDirectoryId = trimToNull(form.getDirectoryId());
        String resumeDirectoryId = existingResumeTask != null ? trimToNull(existingResumeTask.getDirectoryId()) : null;
        if (requestedDirectoryId != null && resumeDirectoryId != null && !requestedDirectoryId.equals(resumeDirectoryId)) {
            throw new IllegalArgumentException("cannot change directoryId when resuming task");
        }
        String directoryId = requestedDirectoryId != null
                ? requestedDirectoryId
                : (resumeDirectoryId != null ? resumeDirectoryId : agentResource.defaultDirectoryId());
        return resourceResolver.resolveOptionalWorkspaceForAgent(
                        tenantId,
                        form.getClientAppId(),
                        form.getUpstreamUserId(),
                        agentResource,
                        directoryId)
                .orElse(null);
    }

    private void rejectLegacyWorkspaceSelectors(CreateBusinessAgentTaskForm form) {
        if (StringUtils.hasText(form.getWorkdir())
                || (form.getAllowedDirs() != null && !form.getAllowedDirs().isEmpty())) {
            throw new IllegalArgumentException("runtime workdir/allowedDirs are no longer accepted; use directoryId");
        }
    }

    private void rejectLegacyRuntimeResourceSelectors(CreateBusinessAgentTaskForm form) {
        if (StringUtils.hasText(form.getWorkerPoolId()) || StringUtils.hasText(form.getSkillId())) {
            throw new IllegalArgumentException("runtime skillId/workerPoolId are no longer accepted; use agentId");
        }
    }

    private String resolveRequestedModelConfigId(
            CreateBusinessAgentTaskForm form,
            A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        String requestedModelConfigId = trimToNull(form.getRequestedModelConfigId());
        if (requestedModelConfigId != null) {
            return requestedModelConfigId;
        }
        return agentResource.defaultModelConfigId();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
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

    private boolean isBackend(String actual, String expected) {
        return expected.equals(ProviderRouteRegistry.canonicalWorkerBackendOrNull(actual));
    }

    private List<String> cleanStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String resolveOptionalVisionModelConfigId(
            String tenantId,
            String clientAppId,
            A2AgentResourceResolver.ResolvedAgentResource agentResource) {
        String modelConfigId = resourceResolver.resolveOptionalModelForAgent(
                        tenantId,
                        clientAppId,
                        agentResource,
                        LlmModelCategory.VISION)
                .map(A2AgentResourceResolver.ResolvedModelResource::modelConfigId)
                .orElse(null);
        if (!StringUtils.hasText(modelConfigId)) {
            log.debug("Vision model config not resolved for clientAppId={}, agentId={}: default VISION model config grant and agent model binding are required",
                    clientAppId,
                    agentResource != null ? agentResource.agentId() : null);
            return null;
        }
        return modelConfigId;
    }
}
