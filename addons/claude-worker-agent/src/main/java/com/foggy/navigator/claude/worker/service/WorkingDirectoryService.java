package com.foggy.navigator.claude.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.MilestoneDeleteResultDTO;
import com.foggy.navigator.claude.worker.model.dto.MilestonePageDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import com.foggy.navigator.common.util.DirectoryAgentId;
import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.model.form.DirectoryMilestoneForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.repository.AgentTeamsConfigRepository;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.enums.WorkingDirectoryResolverType;
import com.foggy.navigator.common.enums.WorkspaceScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Optional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.claude.worker.repository.CodingAgentRepository;
import com.foggy.navigator.common.util.IdGenerator;
import java.util.HashMap;

/**
 * 工作目录管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingDirectoryService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Comparator<DirectoryMilestoneDTO> DEFAULT_MILESTONE_COMPARATOR =
            Comparator.comparing(
                            (DirectoryMilestoneDTO milestone) -> parseMilestoneTimeOrNull(milestone.getStartAt()),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(
                            DirectoryMilestoneDTO::getName,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()))
                    .thenComparing(
                            DirectoryMilestoneDTO::getId,
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    private final WorkingDirectoryRepository directoryRepository;
    private final AgentTeamsConfigRepository agentTeamsConfigRepository;
    private final CodingAgentRepository codingAgentRepository;
    private final SessionEntityRepository sessionEntityRepository;
    private final ClaudeWorkerService workerService;
    private final CredentialEncryptor credentialEncryptor;

    /**
     * 应用就绪后异步修复旧数据：补全 directoryType 和 worktree 默认值
     */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateOldRecords() {
        List<WorkingDirectoryEntity> all = directoryRepository.findAll();
        int fixed = 0;
        for (WorkingDirectoryEntity e : all) {
            boolean dirty = false;
            if (e.getDirectoryType() == null || e.getDirectoryType().isBlank()) {
                e.setDirectoryType("STANDARD");
                dirty = true;
            }
            if (e.getWorktree() == null) {
                e.setWorktree(false);
                dirty = true;
            }
            if (e.getEnabled() == null) {
                e.setEnabled(true);
                dirty = true;
            }
            if (e.getReadOnly() == null) {
                e.setReadOnly(false);
                dirty = true;
            }
            if (dirty) {
                directoryRepository.save(e);
                fixed++;
            }
        }
        if (fixed > 0) {
            log.info("Migrated {} working directory records (set default directoryType/worktree)", fixed);
        }

        // 将旧 agentTeamsConfig 迁移为 AgentTeamsConfigEntity（命名配置）
        int migrated = 0;
        for (WorkingDirectoryEntity e : all) {
            if (e.getAgentTeamsConfig() != null && !e.getAgentTeamsConfig().isBlank()) {
                if (!agentTeamsConfigRepository.existsByDirectoryIdAndUserId(e.getDirectoryId(), e.getUserId())) {
                    AgentTeamsConfigEntity config = new AgentTeamsConfigEntity();
                    config.setConfigId(IdGenerator.shortId());
                    config.setDirectoryId(e.getDirectoryId());
                    config.setUserId(e.getUserId());
                    config.setName("默认配置");
                    config.setConfig(e.getAgentTeamsConfig());
                    config.setIsDefault(true);
                    agentTeamsConfigRepository.save(config);
                    migrated++;
                }
            }
        }
        if (migrated > 0) {
            log.info("Migrated {} legacy agentTeamsConfig into named AgentTeamsConfig entities", migrated);
        }
    }

    /**
     * 创建工作目录
     */
    @Transactional
    public WorkingDirectoryDTO createDirectory(String userId, String tenantId, CreateWorkingDirectoryForm form) {
        // 验证 Worker 归属
        ClaudeWorkerEntity worker = workerService.getWorkerEntity(form.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + form.getWorkerId());
        }

        // 重复检查
        directoryRepository.findByWorkerIdAndPathAndUserId(form.getWorkerId(), form.getPath(), userId)
                .ifPresent(e -> {
                    throw new IllegalArgumentException("Directory already exists: " + form.getPath());
                });

        String directoryType = form.getDirectoryType() != null ? form.getDirectoryType() : "STANDARD";

        // PROJECT 类型不能有 parentProjectId
        if ("PROJECT".equals(directoryType) && form.getParentProjectId() != null) {
            throw new IllegalArgumentException("PROJECT directory cannot have a parentProjectId");
        }

        // 如果指定了 parentProjectId，验证目标存在且是 PROJECT 类型
        if (form.getParentProjectId() != null) {
            WorkingDirectoryEntity parent = directoryRepository
                    .findByDirectoryIdAndUserId(form.getParentProjectId(), userId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent project not found: " + form.getParentProjectId()));
            if (!"PROJECT".equals(parent.getDirectoryType())) {
                throw new IllegalArgumentException("Parent directory is not a PROJECT: " + form.getParentProjectId());
            }
        }

        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(IdGenerator.shortId());
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setProjectName(form.getProjectName());
        entity.setPath(form.getPath());
        entity.setDirectoryType(directoryType);
        entity.setParentProjectId(form.getParentProjectId());
        applyCreateResourcePolicy(entity, form, userId);

        // Auth 默认配置
        if (form.getDefaultAuthMode() != null && !form.getDefaultAuthMode().isEmpty()) {
            entity.setDefaultAuthMode(form.getDefaultAuthMode());
            if (form.getDefaultAuthToken() != null && !form.getDefaultAuthToken().isEmpty()) {
                entity.setDefaultAuthToken(credentialEncryptor.encrypt(form.getDefaultAuthToken()));
            }
            if (form.getDefaultBaseUrl() != null && !form.getDefaultBaseUrl().isEmpty()) {
                entity.setDefaultBaseUrl(form.getDefaultBaseUrl());
            }
        }

        directoryRepository.save(entity);
        log.info("Working directory created: directoryId={}, workerId={}, path={}, type={}",
                entity.getDirectoryId(), form.getWorkerId(), form.getPath(), directoryType);

        // 自动同步 Git 信息（非阻塞，失败不影响创建）
        try {
            syncGitInfoInternal(entity, worker);
        } catch (Exception e) {
            log.warn("Auto-sync git info failed for directoryId={}: {}", entity.getDirectoryId(), e.getMessage());
        }

        return toDTO(entity);
    }

    /**
     * 更新工作目录
     */
    @Transactional
    public WorkingDirectoryDTO updateDirectory(String userId, String directoryId, UpdateWorkingDirectoryForm form) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));

        if (form.getProjectName() != null) {
            entity.setProjectName(form.getProjectName());
        }
        if (form.getPath() != null) {
            entity.setPath(form.getPath());
        }
        if (form.getMilestones() != null) {
            entity.setMilestonesJson(writeMilestones(form.getMilestones()));
        }
        if (form.getAgentTeamsConfig() != null) {
            entity.setAgentTeamsConfig(form.getAgentTeamsConfig().isEmpty() ? null : form.getAgentTeamsConfig());
        }
        // projectTaskPrompt 仅 PROJECT 类型有效
        if (form.getProjectTaskPrompt() != null && "PROJECT".equals(entity.getDirectoryType())) {
            entity.setProjectTaskPrompt(form.getProjectTaskPrompt().isEmpty() ? null : form.getProjectTaskPrompt());
        }
        // parentProjectId 可修改
        if (form.getParentProjectId() != null) {
            if (form.getParentProjectId().isEmpty()) {
                entity.setParentProjectId(null);
            } else {
                // 验证目标是 PROJECT 类型
                WorkingDirectoryEntity parent = directoryRepository
                        .findByDirectoryIdAndUserId(form.getParentProjectId(), userId)
                        .orElseThrow(() -> new IllegalArgumentException("Parent project not found: " + form.getParentProjectId()));
                if (!"PROJECT".equals(parent.getDirectoryType())) {
                    throw new IllegalArgumentException("Parent directory is not a PROJECT: " + form.getParentProjectId());
                }
                entity.setParentProjectId(form.getParentProjectId());
            }
        }

        // 平台 LLM 配置优先
        if (form.getDefaultModelConfigId() != null) {
            if (form.getDefaultModelConfigId().isEmpty()) {
                entity.setDefaultModelConfigId(null);
            } else {
                entity.setDefaultModelConfigId(form.getDefaultModelConfigId());
                // 选中平台配置后清空手动 auth
                entity.setDefaultAuthMode(null);
                entity.setDefaultAuthToken(null);
                entity.setDefaultBaseUrl(null);
            }
        }

        // Auth 默认配置（仅在未设置平台 LLM 配置时有效）
        if (form.getDefaultAuthMode() != null && entity.getDefaultModelConfigId() == null) {
            if (form.getDefaultAuthMode().isEmpty()) {
                // 清空 auth
                entity.setDefaultAuthMode(null);
                entity.setDefaultAuthToken(null);
                entity.setDefaultBaseUrl(null);
            } else {
                entity.setDefaultAuthMode(form.getDefaultAuthMode());
                if (form.getDefaultAuthToken() != null && !form.getDefaultAuthToken().isEmpty()) {
                    entity.setDefaultAuthToken(credentialEncryptor.encrypt(form.getDefaultAuthToken()));
                }
                if (form.getDefaultBaseUrl() != null) {
                    entity.setDefaultBaseUrl(form.getDefaultBaseUrl().isEmpty() ? null : form.getDefaultBaseUrl());
                }
            }
        }
        applyUpdateResourcePolicy(entity, form);

        directoryRepository.save(entity);
        log.info("Working directory updated: directoryId={}", directoryId);
        return toDTO(entity);
    }

    /**
     * 删除工作目录
     */
    @Transactional
    public void deleteDirectory(String userId, String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        directoryRepository.delete(entity);
        log.info("Working directory deleted: directoryId={}", directoryId);
    }

    /**
     * 列出 Worker 下的工作目录
     */
    public List<WorkingDirectoryDTO> listByWorker(String userId, String workerId) {
        List<WorkingDirectoryEntity> directories =
                directoryRepository.findByWorkerIdAndUserIdOrderByProjectNameAsc(workerId, userId);

        // 批量解析：directoryId → 关联的 CodingAgent（一次查询，不 N+1）
        Map<String, CodingAgentEntity> agentByDirId = buildAgentMap(userId, workerId);

        return directories.stream()
                .map(entity -> toDTO(entity, agentByDirId.get(entity.getDirectoryId())))
                .toList();
    }

    private Map<String, CodingAgentEntity> buildAgentMap(String userId, String workerId) {
        Map<String, CodingAgentEntity> map = new HashMap<>();
        for (CodingAgentEntity agent : codingAgentRepository.findByWorkerIdAndUserId(workerId, userId)) {
            if (agent.getDefaultDirectoryId() != null) {
                map.put(agent.getDefaultDirectoryId(), agent);
            }
        }
        return map;
    }

    /**
     * 列出 PROJECT 目录的子目录
     */
    public List<WorkingDirectoryDTO> listChildDirectories(String userId, String projectDirectoryId) {
        return directoryRepository.findByParentProjectIdAndUserIdOrderByProjectNameAsc(projectDirectoryId, userId)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 获取工作目录
     */
    public WorkingDirectoryDTO getDirectory(String userId, String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        return toDTO(entity);
    }

    /**
     * 获取工作目录实体（内部使用）
     */
    public WorkingDirectoryEntity getDirectoryEntity(String userId, String directoryId) {
        return directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
    }

    /**
     * 同步 Git 信息
     */
    @Transactional
    public WorkingDirectoryDTO syncGitInfo(String userId, String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(entity.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + entity.getWorkerId());
        }

        syncGitInfoInternal(entity, worker);
        return toDTO(entity);
    }

    /**
     * 通过 git worktree 创建临时工作目录
     */
    @Transactional
    public WorkingDirectoryDTO createWorktree(String userId, String tenantId,
                                               String sourceDirectoryId, String branch) {
        WorkingDirectoryEntity source = directoryRepository.findByDirectoryIdAndUserId(sourceDirectoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Source directory not found: " + sourceDirectoryId));

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(source.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + source.getWorkerId());
        }

        // 检查是否已存在相同的 worktree（相同源目录和分支）
        Optional<WorkingDirectoryEntity> existing = directoryRepository
                .findBySourceDirectoryIdAndGitBranch(sourceDirectoryId, branch);
        if (existing.isPresent()) {
            WorkingDirectoryEntity existingWorktree = existing.get();
            throw new IllegalArgumentException(
                String.format("Worktree 已存在: 分支 '%s' 的 worktree 在目录 '%s' 中已创建 (directoryId: %s)",
                    branch, existingWorktree.getPath(), existingWorktree.getDirectoryId())
            );
        }

        // 调用 Worker API 创建 worktree
        ClaudeWorkerClient client = workerService.createClient(worker);
        Map<String, Object> result;
        try {
            result = client.createWorktree(source.getPath(), branch, null)
                    .block(Duration.ofSeconds(90));
        } catch (WebClientResponseException e) {
            // 从 Worker 响应体中提取真实的 git 错误信息
            String body = e.getResponseBodyAsString();
            String detail = extractWorkerErrorDetail(body, e.getMessage());
            throw new IllegalArgumentException("创建 worktree 失败: " + detail);
        } catch (Exception e) {
            throw new IllegalArgumentException("创建 worktree 失败: " + e.getMessage());
        }
        if (result == null) {
            throw new IllegalArgumentException("创建 worktree 失败: Worker 返回空响应");
        }

        String worktreePath = (String) result.get("path");
        String actualBranch = (String) result.get("branch");

        // 创建 WorkingDirectoryEntity
        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(IdGenerator.shortId());
        entity.setWorkerId(source.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setProjectName(source.getProjectName() + " [" + actualBranch + "]");
        entity.setPath(worktreePath);
        entity.setDirectoryType("STANDARD");
        entity.setParentProjectId(source.getParentProjectId());
        entity.setWorktree(true);
        entity.setSourceDirectoryId(sourceDirectoryId);
        entity.setGitBranch(actualBranch);
        // 继承源目录的 auth 配置
        entity.setDefaultAuthMode(source.getDefaultAuthMode());
        entity.setDefaultAuthToken(source.getDefaultAuthToken());
        entity.setDefaultBaseUrl(source.getDefaultBaseUrl());
        entity.setDefaultModelConfigId(source.getDefaultModelConfigId());
        entity.setMilestonesJson(source.getMilestonesJson());
        copyResourcePolicy(source, entity);

        directoryRepository.save(entity);
        log.info("Worktree created: directoryId={}, branch={}, path={}",
                entity.getDirectoryId(), actualBranch, worktreePath);

        // 同步 Git 信息
        try {
            syncGitInfoInternal(entity, worker);
        } catch (Exception e) {
            log.warn("Auto-sync git info failed for worktree directoryId={}: {}", entity.getDirectoryId(), e.getMessage());
        }

        return toDTO(entity);
    }

    /**
     * 清理 worktree 目录
     */
    @Transactional
    public void removeWorktree(String userId, String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));

        if (!Boolean.TRUE.equals(entity.getWorktree())) {
            throw new IllegalArgumentException("Directory is not a worktree: " + directoryId);
        }

        ClaudeWorkerEntity worker = workerService.getWorkerEntity(entity.getWorkerId());
        if (!worker.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Worker not found: " + entity.getWorkerId());
        }

        // 调用 Worker API 删除 worktree
        ClaudeWorkerClient client = workerService.createClient(worker);
        try {
            client.removeWorktree(entity.getPath()).block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("Failed to remove worktree on worker: {}", e.getMessage());
            // Continue to remove DB record even if remote removal fails
        }

        directoryRepository.delete(entity);
        log.info("Worktree removed: directoryId={}, path={}", directoryId, entity.getPath());
    }

    /**
     * 解密目录默认 auth 配置
     * @return [authMode, plainToken, baseUrl]
     */
    public String[] getDecryptedDefaultAuth(WorkingDirectoryEntity entity) {
        if (entity.getDefaultAuthMode() == null || entity.getDefaultAuthToken() == null) {
            return new String[]{entity.getDefaultAuthMode(), null, entity.getDefaultBaseUrl()};
        }
        return new String[]{
                entity.getDefaultAuthMode(),
                credentialEncryptor.decrypt(entity.getDefaultAuthToken()),
                entity.getDefaultBaseUrl()
        };
    }

    private void syncGitInfoInternal(WorkingDirectoryEntity entity, ClaudeWorkerEntity worker) {
        ClaudeWorkerClient client = workerService.createClient(worker);
        try {
            Map<String, Object> gitInfo = client.getGitInfo(entity.getPath())
                    .block(Duration.ofSeconds(10));
            if (gitInfo != null) {
                Boolean isGitRepo = (Boolean) gitInfo.get("is_git_repo");
                if (Boolean.TRUE.equals(isGitRepo)) {
                    entity.setGitBranch((String) gitInfo.get("branch"));
                    entity.setGitRemoteUrl((String) gitInfo.get("remote_url"));
                    entity.setGitProvider((String) gitInfo.get("provider"));
                    entity.setGitStatus((String) gitInfo.get("status"));
                } else {
                    entity.setGitBranch(null);
                    entity.setGitRemoteUrl(null);
                    entity.setGitProvider(null);
                    entity.setGitStatus(null);
                }
                entity.setLastSyncedAt(LocalDateTime.now());
                directoryRepository.save(entity);
                log.info("Git info synced: directoryId={}, branch={}, status={}",
                        entity.getDirectoryId(), entity.getGitBranch(), entity.getGitStatus());
            }
        } catch (Exception e) {
            log.warn("Failed to sync git info for directoryId={}: {}", entity.getDirectoryId(), e.getMessage());
            throw new RuntimeException("Git sync failed: " + e.getMessage());
        }
    }

    private WorkingDirectoryDTO toDTO(WorkingDirectoryEntity entity, CodingAgentEntity agent) {
        WorkingDirectoryDTO dto = toDTO(entity);
        if (agent != null) {
            dto.setAgentId(agent.getAgentId());
            dto.setAgentName(agent.getName());
        } else {
            // 无绑定 Agent 时合成隐式 agentId，前端统一按 agentId 路由
            dto.setAgentId(DirectoryAgentId.of(entity.getDirectoryId()));
            dto.setAgentName(entity.getProjectName());
        }
        return dto;
    }

    private WorkingDirectoryDTO toDTO(WorkingDirectoryEntity entity) {
        return WorkingDirectoryDTO.builder()
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .projectName(entity.getProjectName())
                .ownerType(entity.getOwnerType())
                .ownerId(entity.getOwnerId())
                .clientAppId(entity.getClientAppId())
                .upstreamUserId(entity.getUpstreamUserId())
                .workspaceScope(entity.getWorkspaceScope())
                .resolverType(entity.getResolverType())
                .rootRef(entity.getRootRef())
                .resolverKey(entity.getResolverKey())
                .readOnly(entity.getReadOnly())
                .allowedPathPrefixes(parseStringList(entity.getAllowedPathPrefixesJson()))
                .quotaJson(entity.getQuotaJson())
                .retentionPolicyJson(entity.getRetentionPolicyJson())
                .concurrencyPolicyJson(entity.getConcurrencyPolicyJson())
                .enabled(entity.getEnabled())
                .path(entity.getPath())
                .gitBranch(entity.getGitBranch())
                .gitRemoteUrl(entity.getGitRemoteUrl())
                .gitProvider(entity.getGitProvider())
                .gitStatus(entity.getGitStatus())
                .agentTeamsConfig(entity.getAgentTeamsConfig())
                .directoryType(entity.getDirectoryType())
                .parentProjectId(entity.getParentProjectId())
                .projectTaskPrompt(entity.getProjectTaskPrompt())
                .worktree(entity.getWorktree())
                .sourceDirectoryId(entity.getSourceDirectoryId())
                .defaultAuthMode(entity.getDefaultAuthMode())
                .defaultAuthConfigured(entity.getDefaultAuthToken() != null || entity.getDefaultModelConfigId() != null)
                .defaultBaseUrl(entity.getDefaultBaseUrl())
                .maskedDefaultAuthToken(maskToken(entity))
                .defaultModelConfigId(entity.getDefaultModelConfigId())
                .milestones(parseMilestones(entity.getMilestonesJson()))
                .lastSyncedAt(entity.getLastSyncedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private void applyCreateResourcePolicy(WorkingDirectoryEntity entity,
                                           CreateWorkingDirectoryForm form,
                                           String fallbackOwnerId) {
        entity.setOwnerType(form.getOwnerType() != null ? form.getOwnerType() : ResourceOwnerType.UPSTREAM_USER);
        entity.setClientAppId(blankToNull(form.getClientAppId()));
        entity.setUpstreamUserId(blankToNull(form.getUpstreamUserId()));
        entity.setWorkspaceScope(form.getWorkspaceScope() != null ? form.getWorkspaceScope() : WorkspaceScope.USER_PRIVATE);
        entity.setResolverType(form.getResolverType() != null ? form.getResolverType() : WorkingDirectoryResolverType.DELEGATED);
        entity.setRootRef(blankToNull(form.getRootRef()));
        entity.setResolverKey(blankToNull(form.getResolverKey()));
        entity.setReadOnly(Boolean.TRUE.equals(form.getReadOnly()));
        entity.setAllowedPathPrefixesJson(writeStringList(form.getAllowedPathPrefixes()));
        entity.setQuotaJson(blankToNull(form.getQuotaJson()));
        entity.setRetentionPolicyJson(blankToNull(form.getRetentionPolicyJson()));
        entity.setConcurrencyPolicyJson(blankToNull(form.getConcurrencyPolicyJson()));
        entity.setEnabled(form.getEnabled() == null || form.getEnabled());
        String ownerId = blankToNull(form.getOwnerId());
        if (ownerId == null) {
            ownerId = defaultOwnerId(entity, fallbackOwnerId);
        }
        entity.setOwnerId(ownerId);
        validateResourcePolicy(entity);
    }

    private void applyUpdateResourcePolicy(WorkingDirectoryEntity entity,
                                           UpdateWorkingDirectoryForm form) {
        if (form.getOwnerType() != null) {
            entity.setOwnerType(form.getOwnerType());
        }
        if (form.getOwnerId() != null) {
            entity.setOwnerId(blankToNull(form.getOwnerId()));
        }
        if (form.getClientAppId() != null) {
            entity.setClientAppId(blankToNull(form.getClientAppId()));
        }
        if (form.getUpstreamUserId() != null) {
            entity.setUpstreamUserId(blankToNull(form.getUpstreamUserId()));
        }
        if (form.getWorkspaceScope() != null) {
            entity.setWorkspaceScope(form.getWorkspaceScope());
        }
        if (form.getResolverType() != null) {
            entity.setResolverType(form.getResolverType());
        }
        if (form.getRootRef() != null) {
            entity.setRootRef(blankToNull(form.getRootRef()));
        }
        if (form.getResolverKey() != null) {
            entity.setResolverKey(blankToNull(form.getResolverKey()));
        }
        if (form.getReadOnly() != null) {
            entity.setReadOnly(form.getReadOnly());
        }
        if (form.getAllowedPathPrefixes() != null) {
            entity.setAllowedPathPrefixesJson(writeStringList(form.getAllowedPathPrefixes()));
        }
        if (form.getQuotaJson() != null) {
            entity.setQuotaJson(blankToNull(form.getQuotaJson()));
        }
        if (form.getRetentionPolicyJson() != null) {
            entity.setRetentionPolicyJson(blankToNull(form.getRetentionPolicyJson()));
        }
        if (form.getConcurrencyPolicyJson() != null) {
            entity.setConcurrencyPolicyJson(blankToNull(form.getConcurrencyPolicyJson()));
        }
        if (form.getEnabled() != null) {
            entity.setEnabled(form.getEnabled());
        }
        if (entity.getOwnerId() == null) {
            entity.setOwnerId(defaultOwnerId(entity, entity.getUserId()));
        }
        validateResourcePolicy(entity);
    }

    private void copyResourcePolicy(WorkingDirectoryEntity source, WorkingDirectoryEntity target) {
        target.setOwnerType(source.getOwnerType());
        target.setOwnerId(source.getOwnerId());
        target.setClientAppId(source.getClientAppId());
        target.setUpstreamUserId(source.getUpstreamUserId());
        target.setWorkspaceScope(source.getWorkspaceScope());
        target.setResolverType(source.getResolverType());
        target.setRootRef(source.getRootRef());
        target.setResolverKey(source.getResolverKey());
        target.setReadOnly(source.getReadOnly());
        target.setAllowedPathPrefixesJson(source.getAllowedPathPrefixesJson());
        target.setQuotaJson(source.getQuotaJson());
        target.setRetentionPolicyJson(source.getRetentionPolicyJson());
        target.setConcurrencyPolicyJson(source.getConcurrencyPolicyJson());
        target.setEnabled(source.getEnabled());
    }

    private String defaultOwnerId(WorkingDirectoryEntity entity, String fallbackOwnerId) {
        ResourceOwnerType ownerType = entity.getOwnerType();
        if (ownerType == ResourceOwnerType.CLIENT_APP) {
            return entity.getClientAppId();
        }
        if (ownerType == ResourceOwnerType.UPSTREAM_USER && blankToNull(entity.getUpstreamUserId()) != null) {
            return entity.getUpstreamUserId();
        }
        if (ownerType == ResourceOwnerType.PLATFORM) {
            return "platform";
        }
        return fallbackOwnerId;
    }

    private void validateResourcePolicy(WorkingDirectoryEntity entity) {
        if (entity.getOwnerType() == ResourceOwnerType.PLATFORM) {
            throw new IllegalArgumentException("WorkingDirectory cannot be owned by PLATFORM");
        }
        if (entity.getOwnerType() == null) {
            throw new IllegalArgumentException("ownerType is required");
        }
        if (blankToNull(entity.getOwnerId()) == null) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (entity.getWorkspaceScope() == null) {
            throw new IllegalArgumentException("workspaceScope is required");
        }
        if (entity.getResolverType() == null) {
            throw new IllegalArgumentException("resolverType is required");
        }
        if (entity.getWorkspaceScope() == WorkspaceScope.CLIENT_APP_SHARED
                && (entity.getOwnerType() != ResourceOwnerType.CLIENT_APP || blankToNull(entity.getClientAppId()) == null)) {
            throw new IllegalArgumentException("CLIENT_APP_SHARED workspace requires CLIENT_APP owner and clientAppId");
        }
        if (entity.getWorkspaceScope() == WorkspaceScope.UPSTREAM_SYSTEM_SHARED
                && entity.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM) {
            throw new IllegalArgumentException("UPSTREAM_SYSTEM_SHARED workspace requires UPSTREAM_SYSTEM owner");
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse string list JSON: {}", json);
            return List.of();
        }
    }

    private String writeStringList(List<String> values) {
        if (values == null) {
            return null;
        }
        List<String> cleaned = values.stream()
                .map(this::blankToNull)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid string list format", e);
        }
    }

    private List<DirectoryMilestoneDTO> parseMilestones(String milestonesJson) {
        if (milestonesJson == null || milestonesJson.isBlank()) {
            return List.of();
        }
        try {
            return sortMilestones(OBJECT_MAPPER.readValue(milestonesJson, new TypeReference<List<DirectoryMilestoneDTO>>() {}));
        } catch (Exception e) {
            log.warn("Failed to parse milestones JSON: {}", milestonesJson);
            return List.of();
        }
    }

    private String writeMilestones(List<DirectoryMilestoneForm> milestones) {
        if (milestones == null) {
            return null;
        }
        List<DirectoryMilestoneDTO> normalized = sortMilestones(milestones.stream()
                .map(this::normalizeMilestone)
                .filter(milestone -> milestone.getName() != null)
                .toList());
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid milestones format", e);
        }
    }

    private DirectoryMilestoneDTO normalizeMilestone(DirectoryMilestoneForm form) {
        String name = blankToNull(form.getName());
        String id = blankToNull(form.getId());
        String status = blankToNull(form.getStatus());
        String startAt = normalizeMilestoneTime(form.getStartAt(), "startAt");
        String endAt = normalizeMilestoneTime(form.getEndAt(), "endAt");
        validateMilestoneRange(startAt, endAt);
        return DirectoryMilestoneDTO.builder()
                .id(id != null ? id : IdGenerator.shortId())
                .name(name)
                .status(status != null ? status : "PLANNED")
                .docPath(blankToNull(form.getDocPath()))
                .startAt(startAt)
                .endAt(endAt)
                .build();
    }

    // ========== Milestone CRUD ==========

    public List<DirectoryMilestoneDTO> listMilestones(String userId, String directoryId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        return parseMilestones(entity.getMilestonesJson());
    }

    public MilestonePageDTO listMilestonesPaged(
            String userId,
            String directoryId,
            int page,
            int size,
            String sortBy,
            String sortDir) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedSortBy = normalizeMilestoneSortBy(sortBy);
        String normalizedSortDir = normalizeMilestoneSortDir(sortDir);
        List<DirectoryMilestoneDTO> sorted = sortMilestones(
                parseMilestones(entity.getMilestonesJson()),
                normalizedSortBy,
                normalizedSortDir);
        int fromIndex = Math.min(safePage * safeSize, sorted.size());
        int toIndex = Math.min(fromIndex + safeSize, sorted.size());
        return MilestonePageDTO.builder()
                .content(sorted.subList(fromIndex, toIndex))
                .total(sorted.size())
                .page(safePage)
                .size(safeSize)
                .sortBy(normalizedSortBy)
                .sortDir(normalizedSortDir)
                .build();
    }

    @Transactional
    public DirectoryMilestoneDTO addMilestone(String userId, String directoryId, DirectoryMilestoneForm form) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        List<DirectoryMilestoneDTO> milestones = new java.util.ArrayList<>(parseMilestones(entity.getMilestonesJson()));
        DirectoryMilestoneDTO newMilestone = normalizeMilestone(form);
        if (newMilestone.getName() == null) {
            throw new IllegalArgumentException("Milestone name is required");
        }
        milestones.add(newMilestone);
        entity.setMilestonesJson(serializeMilestones(sortMilestones(milestones)));
        directoryRepository.save(entity);
        return newMilestone;
    }

    @Transactional
    public DirectoryMilestoneDTO updateSingleMilestone(String userId, String directoryId, String milestoneId, DirectoryMilestoneForm form) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        List<DirectoryMilestoneDTO> milestones = new java.util.ArrayList<>(parseMilestones(entity.getMilestonesJson()));
        DirectoryMilestoneDTO target = milestones.stream()
                .filter(m -> milestoneId.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Milestone not found: " + milestoneId));
        String name = blankToNull(form.getName());
        if (name != null) target.setName(name);
        String status = blankToNull(form.getStatus());
        if (status != null) target.setStatus(status);
        if (form.getDocPath() != null) target.setDocPath(blankToNull(form.getDocPath()));
        if (form.getStartAt() != null) target.setStartAt(normalizeMilestoneTime(form.getStartAt(), "startAt"));
        if (form.getEndAt() != null) target.setEndAt(normalizeMilestoneTime(form.getEndAt(), "endAt"));
        validateMilestoneRange(target.getStartAt(), target.getEndAt());
        entity.setMilestonesJson(serializeMilestones(sortMilestones(milestones)));
        directoryRepository.save(entity);
        return target;
    }

    @Transactional
    public MilestoneDeleteResultDTO deleteMilestone(String userId, String directoryId, String milestoneId, boolean force) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryIdAndUserId(directoryId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        List<DirectoryMilestoneDTO> milestones = new java.util.ArrayList<>(parseMilestones(entity.getMilestonesJson()));
        boolean existed = milestones.removeIf(m -> milestoneId.equals(m.getId()));
        if (!existed) {
            throw new IllegalArgumentException("Milestone not found: " + milestoneId);
        }
        long sessionCount = sessionEntityRepository.countByMilestoneIdAndUserId(milestoneId, userId);
        if (sessionCount > 0 && !force) {
            return MilestoneDeleteResultDTO.builder()
                    .milestoneId(milestoneId)
                    .sessionCount(sessionCount)
                    .deleted(false)
                    .build();
        }
        if (sessionCount > 0) {
            sessionEntityRepository.clearMilestoneIdByMilestoneIdAndUserId(milestoneId, userId);
        }
        entity.setMilestonesJson(milestones.isEmpty() ? null : serializeMilestones(sortMilestones(milestones)));
        directoryRepository.save(entity);
        return MilestoneDeleteResultDTO.builder()
                .milestoneId(milestoneId)
                .sessionCount(sessionCount)
                .deleted(true)
                .build();
    }

    public long countSessionsByMilestone(String userId, String milestoneId) {
        return sessionEntityRepository.countByMilestoneIdAndUserId(milestoneId, userId);
    }

    private String serializeMilestones(List<DirectoryMilestoneDTO> milestones) {
        try {
            return OBJECT_MAPPER.writeValueAsString(milestones);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid milestones format", e);
        }
    }

    private List<DirectoryMilestoneDTO> sortMilestones(List<DirectoryMilestoneDTO> milestones) {
        return sortMilestones(milestones, "startAt", "desc");
    }

    private List<DirectoryMilestoneDTO> sortMilestones(List<DirectoryMilestoneDTO> milestones, String sortBy, String sortDir) {
        Comparator<DirectoryMilestoneDTO> comparator = milestoneComparator(sortBy);
        if ("asc".equalsIgnoreCase(sortDir)) {
            comparator = comparator.reversed();
        }
        return milestones.stream().sorted(comparator).toList();
    }

    private Comparator<DirectoryMilestoneDTO> milestoneComparator(String sortBy) {
        return switch (normalizeMilestoneSortBy(sortBy)) {
            case "name" -> Comparator
                    .comparing(DirectoryMilestoneDTO::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()))
                    .thenComparing(DEFAULT_MILESTONE_COMPARATOR);
            case "status" -> Comparator
                    .comparing(DirectoryMilestoneDTO::getStatus, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER.reversed()))
                    .thenComparing(DEFAULT_MILESTONE_COMPARATOR);
            case "endAt" -> Comparator
                    .comparing(WorkingDirectoryService::parseMilestoneEndForSort, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(DEFAULT_MILESTONE_COMPARATOR);
            case "startAt" -> DEFAULT_MILESTONE_COMPARATOR;
            default -> throw new IllegalArgumentException("Unsupported milestone sortBy: " + sortBy);
        };
    }

    private String normalizeMilestoneSortBy(String sortBy) {
        String value = blankToNull(sortBy);
        if (value == null) {
            return "startAt";
        }
        return switch (value) {
            case "startAt", "endAt", "name", "status" -> value;
            default -> throw new IllegalArgumentException("Unsupported milestone sortBy: " + sortBy);
        };
    }

    private String normalizeMilestoneSortDir(String sortDir) {
        String value = blankToNull(sortDir);
        if (value == null) {
            return "desc";
        }
        if ("asc".equalsIgnoreCase(value)) {
            return "asc";
        }
        if ("desc".equalsIgnoreCase(value)) {
            return "desc";
        }
        throw new IllegalArgumentException("Unsupported milestone sortDir: " + sortDir);
    }

    private String normalizeMilestoneTime(String value, String fieldName) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(normalized).toString();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid milestone " + fieldName + ": " + value, ex);
        }
    }

    private void validateMilestoneRange(String startAt, String endAt) {
        LocalDateTime start = parseMilestoneTimeOrNull(startAt);
        LocalDateTime end = parseMilestoneTimeOrNull(endAt);
        if (start != null && end != null && end.isBefore(start)) {
            throw new IllegalArgumentException("Milestone endAt must be greater than or equal to startAt");
        }
    }

    private static LocalDateTime parseMilestoneEndForSort(DirectoryMilestoneDTO milestone) {
        return milestone == null ? null : parseMilestoneTimeOrNull(milestone.getEndAt());
    }

    private static LocalDateTime parseMilestoneTimeOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private String maskToken(WorkingDirectoryEntity entity) {
        if (entity.getDefaultAuthToken() == null) return null;
        try {
            String plain = credentialEncryptor.decrypt(entity.getDefaultAuthToken());
            if (plain == null || plain.isEmpty()) return null;
            if (plain.length() <= 10) {
                return plain.substring(0, 2) + "****" + plain.substring(plain.length() - 2);
            }
            return plain.substring(0, 6) + "****" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "***";
        }
    }

    private String blankToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * 更新工作目录的所有者（Open API Provisioning 用）
     */
    @Transactional
    public void updateDirectoryOwner(String directoryId, String newUserId) {
        WorkingDirectoryEntity entity = directoryRepository.findByDirectoryId(directoryId)
                .orElseThrow(() -> new IllegalArgumentException("Directory not found: " + directoryId));
        entity.setUserId(newUserId);
        directoryRepository.save(entity);
        log.info("Directory owner updated: directoryId={}, newUserId={}", directoryId, newUserId);
    }

    /**
     * 从 Worker HTTP 响应体中提取 detail 字段，格式通常为 {"detail": "..."}
     */
    private String extractWorkerErrorDetail(String responseBody, String fallback) {
        if (responseBody != null && !responseBody.isBlank()) {
            try {
                // 简单解析 JSON {"detail": "..."} 结构，避免引入额外依赖
                int idx = responseBody.indexOf("\"detail\"");
                if (idx >= 0) {
                    int colon = responseBody.indexOf(':', idx);
                    if (colon >= 0) {
                        int start = responseBody.indexOf('"', colon + 1);
                        int end = responseBody.lastIndexOf('"');
                        if (start >= 0 && end > start) {
                            return responseBody.substring(start + 1, end);
                        }
                    }
                }
                return responseBody;
            } catch (Exception ex) {
                log.warn("Failed to parse worker error body: {}", ex.getMessage());
            }
        }
        return fallback;
    }
}
