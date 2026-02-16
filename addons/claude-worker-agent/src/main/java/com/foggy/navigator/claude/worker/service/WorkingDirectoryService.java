package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.model.dto.WorkingDirectoryDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.entity.WorkingDirectoryEntity;
import com.foggy.navigator.claude.worker.model.form.CreateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkingDirectoryForm;
import com.foggy.navigator.claude.worker.repository.WorkingDirectoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作目录管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingDirectoryService {

    private final WorkingDirectoryRepository directoryRepository;
    private final ClaudeWorkerService workerService;

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

        WorkingDirectoryEntity entity = new WorkingDirectoryEntity();
        entity.setDirectoryId(UUID.randomUUID().toString().substring(0, 8));
        entity.setWorkerId(form.getWorkerId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setProjectName(form.getProjectName());
        entity.setPath(form.getPath());

        directoryRepository.save(entity);
        log.info("Working directory created: directoryId={}, workerId={}, path={}", entity.getDirectoryId(), form.getWorkerId(), form.getPath());

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
                .lastSyncedAt(entity.getLastSyncedAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
