package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
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
import java.util.List;
import java.util.Map;
import com.foggy.navigator.common.util.IdGenerator;

/**
 * 工作目录管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingDirectoryService {

    private final WorkingDirectoryRepository directoryRepository;
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
            if (dirty) {
                directoryRepository.save(e);
                fixed++;
            }
        }
        if (fixed > 0) {
            log.info("Migrated {} working directory records (set default directoryType/worktree)", fixed);
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
        return directoryRepository.findByWorkerIdAndUserIdOrderByProjectNameAsc(workerId, userId).stream()
                .map(this::toDTO)
                .toList();
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

    private WorkingDirectoryDTO toDTO(WorkingDirectoryEntity entity) {
        return WorkingDirectoryDTO.builder()
                .directoryId(entity.getDirectoryId())
                .workerId(entity.getWorkerId())
                .projectName(entity.getProjectName())
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
                .lastSyncedAt(entity.getLastSyncedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
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
