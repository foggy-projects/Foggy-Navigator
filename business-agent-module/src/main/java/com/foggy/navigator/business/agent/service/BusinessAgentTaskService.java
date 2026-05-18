package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchRequest;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLaunchResult;
import com.foggy.navigator.business.agent.service.worker.BusinessAgentWorkerTaskLauncher;
import com.foggy.navigator.common.enums.LlmModelCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    private final BusinessAgentTaskRepository taskRepository;
    private final BusinessTaskScopedTokenRepository tokenRepository;
    private final ClientAppService clientAppService;
    private final BizWorkerPoolService bizWorkerPoolService;
    private final ClientAppModelConfigGrantService grantService;
    private final ClientAppUserGrantService userGrantService;
    private final SkillRegistryService skillRegistryService;
    private final BusinessAgentTaskScopedTokenRuntimeStore tokenRuntimeStore;
    private final BusinessAgentSessionService businessAgentSessionService;
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
        requireText(form.getWorkerPoolId(), "workerPoolId is required");
        requireText(form.getUpstreamUserId(), "upstreamUserId is required");
        requireText(form.getSkillId(), "skillId is required");

        // 2. 校验 clientAppId 存在、属于当前 tenant、状态可用
        clientAppService.requireActiveClientApp(tenantId, form.getClientAppId());

        // 3. 校验 workerPoolId 存在、属于当前 tenant、状态可用
        BizWorkerPoolEntity workerPool = bizWorkerPoolService.requireAvailablePool(tenantId, form.getWorkerPoolId());

        // 校验 upstream user grant
        userGrantService.checkUpstreamUserAccess(tenantId, form.getClientAppId(), form.getUpstreamUserId());

        // 校验 client app skill grant
        skillRegistryService.checkClientAppSkillAccess(tenantId, form.getClientAppId(), form.getSkillId());

        String finalModelConfigId;
        String finalVisionModelConfigId;

        if (StringUtils.hasText(form.getResumeFromTaskId())) {
            BusinessAgentTaskEntity existingTask = taskRepository.findByTaskId(form.getResumeFromTaskId())
                    .orElseThrow(() -> new IllegalArgumentException("resume task not found: " + form.getResumeFromTaskId()));

            if (!tenantId.equals(existingTask.getTenantId()) ||
                !form.getClientAppId().equals(existingTask.getClientAppId()) ||
                !form.getSessionId().equals(existingTask.getSessionId())) {
                throw new IllegalArgumentException("resume task context mismatch");
            }
            if (StringUtils.hasText(form.getRequestedModelConfigId()) &&
                !form.getRequestedModelConfigId().equals(existingTask.getModelConfigId())) {
                throw new IllegalArgumentException("cannot change modelConfigId when resuming task");
            }
            finalModelConfigId = existingTask.getModelConfigId();
            finalVisionModelConfigId = resolveOptionalVisionModelConfigId(tenantId, form.getClientAppId());
        } else {
            // 4, 5, 6. 新建 task 时必须调用 resolveEffectiveModelConfigId
            finalModelConfigId = grantService.resolveEffectiveModelConfigId(tenantId, form.getClientAppId(), form.getRequestedModelConfigId());
            finalVisionModelConfigId = resolveOptionalVisionModelConfigId(tenantId, form.getClientAppId());
        }

        // 7. task 创建后固定最终 modelConfigId
        BusinessAgentTaskEntity task = new BusinessAgentTaskEntity();
        task.setTaskId("bt_" + UUID.randomUUID().toString().replace("-", ""));
        task.setSessionId(form.getSessionId());
        task.setTenantId(tenantId);
        task.setClientAppId(form.getClientAppId());
        task.setUpstreamUserId(form.getUpstreamUserId());
        task.setNavigatorEffectiveUserId(actorUserId);
        task.setSkillId(form.getSkillId());
        task.setWorkerPoolId(form.getWorkerPoolId());
        task.setModelConfigId(finalModelConfigId);
        task.setRequestedModelConfigId(form.getRequestedModelConfigId());
        task.setStatus(STATUS_CREATED);
        task = taskRepository.save(task);

        // Token must exist before the worker task starts so it can be passed as hidden runtime context.
        String plainToken = "btt_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);
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
        token.setExpiresAt(expiresAt);
        token = tokenRepository.save(token);
        tokenRuntimeStore.registerToken(tenantId, task.getSessionId(), task.getTaskId(), plainToken, expiresAt);

        String contextId = businessAgentSessionService
                .bindTask(task, form.getContextId(), form.getClientContextJson())
                .getContextId();

        BusinessAgentWorkerTaskLaunchResult launchResult = launchWorkerTaskIfAvailable(
                tenantId, actorUserId, task, workerPool, plainToken, finalVisionModelConfigId, contextId);
        if (launchResult != null && StringUtils.hasText(launchResult.getWorkerTaskId())) {
            task.setWorkerTaskId(launchResult.getWorkerTaskId());
            task.setWorkerSessionId(launchResult.getWorkerSessionId());
            task.setWorkerId(launchResult.getWorkerId());
            task.setWorkerProviderType(launchResult.getProviderType());
            task = taskRepository.save(task);
            token.setWorkerTaskId(task.getWorkerTaskId());
            token.setWorkerSessionId(task.getWorkerSessionId());
            tokenRepository.save(token);
            tokenRuntimeStore.registerToken(tenantId, task.getSessionId(), task.getWorkerTaskId(), plainToken, expiresAt);
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
        dto.setSkillId(baseDto.getSkillId());
        dto.setWorkerPoolId(baseDto.getWorkerPoolId());
        dto.setWorkerTaskId(baseDto.getWorkerTaskId());
        dto.setWorkerSessionId(baseDto.getWorkerSessionId());
        dto.setWorkerId(baseDto.getWorkerId());
        dto.setWorkerProviderType(baseDto.getWorkerProviderType());
        dto.setModelConfigId(baseDto.getModelConfigId());
        dto.setRequestedModelConfigId(baseDto.getRequestedModelConfigId());
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

        String finalModelConfigId = StringUtils.hasText(requestedModelConfigId)
                ? grantService.resolveEffectiveModelConfigId(tenantId, clientAppId, requestedModelConfigId)
                : grantService.resolveEffectiveModelConfigId(tenantId, clientAppId, null);

        String taskId = "obt_" + UUID.randomUUID().toString().replace("-", "");
        String plainToken = "btt_" + UUID.randomUUID().toString().replace("-", "");
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);

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
        token.setExpiresAt(expiresAt);
        tokenRepository.save(token);

        tokenRuntimeStore.registerToken(tenantId, sessionId, taskId, plainToken, expiresAt);
        return plainToken;
    }

    @Transactional
    public void bindOpenApiTaskScopedTokenToWorkerTask(
            String tenantId,
            String plainToken,
            String workerTaskId,
            String workerSessionId) {
        requireText(tenantId, "tenantId is required");
        requireText(plainToken, "plainToken is required");
        requireText(workerTaskId, "workerTaskId is required");

        String hash = SecretTokenSupport.sha256(plainToken);
        BusinessTaskScopedTokenEntity token = tokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new IllegalArgumentException("invalid token"));
        if (!tenantId.equals(token.getTenantId())) {
            throw new SecurityException("token tenant mismatch");
        }
        if (!STATUS_ACTIVE.equals(token.getStatus())) {
            throw new IllegalStateException("token is not active");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("token is expired");
        }
        if (StringUtils.hasText(token.getWorkerTaskId()) && !workerTaskId.equals(token.getWorkerTaskId())) {
            throw new IllegalStateException("token already bound to another worker task");
        }

        String resolvedWorkerSessionId = StringUtils.hasText(workerSessionId) ? workerSessionId : token.getSessionId();
        token.setWorkerTaskId(workerTaskId);
        token.setWorkerSessionId(resolvedWorkerSessionId);
        tokenRepository.save(token);

        tokenRuntimeStore.registerToken(tenantId, token.getSessionId(), workerTaskId, plainToken, token.getExpiresAt());
        if (StringUtils.hasText(resolvedWorkerSessionId) && !resolvedWorkerSessionId.equals(token.getSessionId())) {
            tokenRuntimeStore.registerToken(tenantId, resolvedWorkerSessionId, workerTaskId, plainToken, token.getExpiresAt());
        }
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

        if (!STATUS_ACTIVE.equals(token.getStatus())) {
            throw new IllegalStateException("token is not active");
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("token is expired");
        }

        return com.foggy.navigator.business.agent.model.dto.BusinessTaskScopedTokenDTO.fromEntity(token);
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private BusinessAgentWorkerTaskLaunchResult launchWorkerTaskIfAvailable(
            String tenantId,
            String actorUserId,
            BusinessAgentTaskEntity task,
            BizWorkerPoolEntity workerPool,
            String taskScopedToken,
            String visionModelConfigId,
            String contextId) {
        if (workerTaskLaunchers == null || workerTaskLaunchers.isEmpty()) {
            return null;
        }
        String markdownBody = skillRegistryService.buildMaterializedPublicSkillMarkdown(
                tenantId,
                task.getSkillId(),
                task.getClientAppId()
        );

        return workerTaskLaunchers.stream()
                .filter(Objects::nonNull)
                .filter(launcher -> workerPool.getWorkerBackend().equals(launcher.getWorkerBackend()))
                .findFirst()
                .map(launcher -> launcher.launch(BusinessAgentWorkerTaskLaunchRequest.builder()
                        .tenantId(tenantId)
                        .actorUserId(actorUserId)
                        .businessTaskId(task.getTaskId())
                        .sessionId(task.getSessionId())
                        .contextId(contextId)
                        .clientAppId(task.getClientAppId())
                        .upstreamUserId(task.getUpstreamUserId())
                        .skillId(task.getSkillId())
                        .workerPoolId(task.getWorkerPoolId())
                        .workerBackend(workerPool.getWorkerBackend())
                        .modelConfigId(task.getModelConfigId())
                        .visionModelConfigId(visionModelConfigId)
                        .markdownBody(markdownBody)
                        .taskScopedToken(taskScopedToken)
                        .build()))
                .orElse(null);
    }

    private String resolveOptionalVisionModelConfigId(String tenantId, String clientAppId) {
        try {
            return grantService.resolveEffectiveModelConfigId(tenantId, clientAppId, null, LlmModelCategory.VISION);
        } catch (Exception e) {
            log.debug("Vision model config not resolved for clientAppId={}: {}", clientAppId, e.getMessage());
            return null;
        }
    }
}
