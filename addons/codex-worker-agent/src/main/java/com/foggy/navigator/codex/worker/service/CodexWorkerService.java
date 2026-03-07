package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexWorkerDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexWorkerEntity;
import com.foggy.navigator.codex.worker.model.form.RegisterCodexWorkerForm;
import com.foggy.navigator.codex.worker.repository.CodexWorkerRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Codex Worker CRUD + 健康检查服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodexWorkerService {

    private final CodexWorkerRepository workerRepository;
    private final CodexWorkerClientFactory clientFactory;
    private final CredentialEncryptor credentialEncryptor;

    /**
     * 列出用户的所有 Codex Worker
     */
    public List<CodexWorkerDTO> listWorkers(String userId) {
        return workerRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 获取 Worker 详情
     */
    public CodexWorkerDTO getWorker(String userId, String workerId) {
        CodexWorkerEntity entity = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Codex Worker not found: " + workerId));
        return toDTO(entity);
    }

    /**
     * 获取 Worker Entity（内部使用）
     */
    public CodexWorkerEntity getWorkerEntity(String workerId) {
        return workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Codex Worker not found: " + workerId));
    }

    /**
     * 注册新 Codex Worker
     */
    @Transactional
    public CodexWorkerDTO registerWorker(String userId, String tenantId, RegisterCodexWorkerForm form) {
        if (form.getName() == null || form.getName().isBlank()) {
            throw new IllegalArgumentException("Worker name is required");
        }
        if (form.getBaseUrl() == null || form.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Worker baseUrl is required");
        }

        CodexWorkerEntity entity = new CodexWorkerEntity();
        entity.setWorkerId(IdGenerator.shortId());
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setBaseUrl(form.getBaseUrl().replaceAll("/+$", ""));

        if (form.getAuthToken() != null && !form.getAuthToken().isBlank()) {
            entity.setAuthToken(credentialEncryptor.encrypt(form.getAuthToken()));
        }

        entity.setStatus("UNKNOWN");
        workerRepository.save(entity);
        log.info("Registered Codex Worker: workerId={}, name={}, baseUrl={}",
                entity.getWorkerId(), entity.getName(), entity.getBaseUrl());

        return toDTO(entity);
    }

    /**
     * 更新 Worker 信息
     */
    @Transactional
    public CodexWorkerDTO updateWorker(String userId, String workerId, RegisterCodexWorkerForm form) {
        CodexWorkerEntity entity = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Codex Worker not found: " + workerId));

        if (form.getName() != null && !form.getName().isBlank()) {
            entity.setName(form.getName());
        }
        if (form.getBaseUrl() != null && !form.getBaseUrl().isBlank()) {
            entity.setBaseUrl(form.getBaseUrl().replaceAll("/+$", ""));
            clientFactory.remove(workerId);
        }
        if (form.getAuthToken() != null) {
            if (form.getAuthToken().isBlank()) {
                entity.setAuthToken(null);
            } else {
                entity.setAuthToken(credentialEncryptor.encrypt(form.getAuthToken()));
            }
            clientFactory.remove(workerId);
        }

        workerRepository.save(entity);
        log.info("Updated Codex Worker: workerId={}", workerId);
        return toDTO(entity);
    }

    /**
     * 删除 Worker
     */
    @Transactional
    public void deleteWorker(String userId, String workerId) {
        workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Codex Worker not found: " + workerId));
        workerRepository.deleteByWorkerIdAndUserId(workerId, userId);
        clientFactory.remove(workerId);
        log.info("Deleted Codex Worker: workerId={}", workerId);
    }

    /**
     * 执行健康检查
     */
    @Transactional
    public CodexWorkerDTO triggerHealthCheck(String userId, String workerId) {
        CodexWorkerEntity entity = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Codex Worker not found: " + workerId));

        CodexWorkerClient client = createClient(entity);

        try {
            Map<String, Object> health = client.healthCheck().block();
            if (health != null && "ok".equals(health.get("status"))) {
                entity.setStatus("ONLINE");
                if (health.get("hostname") != null) {
                    entity.setHostname((String) health.get("hostname"));
                }
                if (health.get("version") != null) {
                    entity.setWorkerVersion((String) health.get("version"));
                }
                entity.setLastHeartbeat(java.time.LocalDateTime.now());
            } else {
                entity.setStatus("OFFLINE");
            }
        } catch (Exception e) {
            log.warn("Health check failed for Codex Worker {}: {}", workerId, e.getMessage());
            entity.setStatus("OFFLINE");
        }

        workerRepository.save(entity);
        return toDTO(entity);
    }

    /**
     * 创建 HTTP Client
     */
    public CodexWorkerClient createClient(CodexWorkerEntity worker) {
        String decryptedToken = null;
        if (worker.getAuthToken() != null && !worker.getAuthToken().isBlank()) {
            decryptedToken = credentialEncryptor.decrypt(worker.getAuthToken());
        }
        return clientFactory.getOrCreate(worker.getWorkerId(), worker.getBaseUrl(), decryptedToken);
    }

    private CodexWorkerDTO toDTO(CodexWorkerEntity entity) {
        return CodexWorkerDTO.builder()
                .workerId(entity.getWorkerId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .status(entity.getStatus())
                .hostname(entity.getHostname())
                .workerVersion(entity.getWorkerVersion())
                .lastHeartbeat(entity.getLastHeartbeat())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
