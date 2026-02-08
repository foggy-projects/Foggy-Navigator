package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClientFactory;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkerStatusDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.RegisterWorkerForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkerForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker CRUD + 加密服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeWorkerService {

    private final ClaudeWorkerRepository workerRepository;
    private final ClaudeWorkerClientFactory clientFactory;

    @Value("${navigator.security.credential-key:default-dev-key-change-in-prod}")
    private String credentialKey;

    @Value("${navigator.security.credential-salt:abcdef0123456789}")
    private String credentialSalt;

    private TextEncryptor textEncryptor;

    @PostConstruct
    void init() {
        this.textEncryptor = Encryptors.text(credentialKey, credentialSalt);
    }

    /**
     * 注册 Worker
     */
    @Transactional
    public WorkerDTO registerWorker(String userId, String tenantId, RegisterWorkerForm form) {
        ClaudeWorkerEntity entity = new ClaudeWorkerEntity();
        entity.setWorkerId(UUID.randomUUID().toString().substring(0, 8));
        entity.setUserId(userId);
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setBaseUrl(form.getBaseUrl());
        entity.setAuthToken(encrypt(form.getAuthToken()));
        entity.setAuthMode(form.getAuthMode() != null ? form.getAuthMode() : "SUBSCRIPTION");

        workerRepository.save(entity);
        log.info("Worker registered: workerId={}, name={}, userId={}", entity.getWorkerId(), entity.getName(), userId);
        return toDTO(entity);
    }

    /**
     * 更新 Worker
     */
    @Transactional
    public WorkerDTO updateWorker(String userId, String workerId, UpdateWorkerForm form) {
        ClaudeWorkerEntity entity = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));

        if (form.getName() != null) {
            entity.setName(form.getName());
        }
        if (form.getBaseUrl() != null) {
            entity.setBaseUrl(form.getBaseUrl());
        }
        if (form.getAuthToken() != null && !form.getAuthToken().isEmpty()) {
            entity.setAuthToken(encrypt(form.getAuthToken()));
        }
        if (form.getAuthMode() != null) {
            entity.setAuthMode(form.getAuthMode());
        }

        workerRepository.save(entity);
        clientFactory.remove(workerId);
        log.info("Worker updated: workerId={}, userId={}", workerId, userId);
        return toDTO(entity);
    }

    /**
     * 删除 Worker
     */
    @Transactional
    public void deleteWorker(String userId, String workerId) {
        workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        workerRepository.deleteByWorkerIdAndUserId(workerId, userId);
        clientFactory.remove(workerId);
        log.info("Worker deleted: workerId={}, userId={}", workerId, userId);
    }

    /**
     * 列出用户的所有 Worker
     */
    public List<WorkerDTO> listWorkers(String userId) {
        return workerRepository.findByUserId(userId).stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     * 获取 Worker（含权限校验）
     */
    public WorkerDTO getWorker(String userId, String workerId) {
        ClaudeWorkerEntity entity = workerRepository.findByWorkerIdAndUserId(workerId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        return toDTO(entity);
    }

    /**
     * 获取 Worker 实体（内部使用）
     */
    public ClaudeWorkerEntity getWorkerEntity(String workerId) {
        return workerRepository.findByWorkerId(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
    }

    /**
     * 获取解密后的 auth token
     */
    public String getDecryptedToken(ClaudeWorkerEntity entity) {
        return decrypt(entity.getAuthToken());
    }

    /**
     * 创建 Worker 客户端
     */
    public ClaudeWorkerClient createClient(ClaudeWorkerEntity entity) {
        return clientFactory.getOrCreate(entity.getWorkerId(), entity.getBaseUrl(), decrypt(entity.getAuthToken()));
    }

    /**
     * 更新 Worker 状态（心跳检查后调用）
     */
    @Transactional
    public void updateWorkerStatus(String workerId, String status, Map<String, Object> healthData) {
        workerRepository.findByWorkerId(workerId).ifPresent(entity -> {
            entity.setStatus(status);
            entity.setLastHeartbeat(LocalDateTime.now());
            if (healthData != null) {
                if (healthData.containsKey("hostname")) {
                    entity.setHostname((String) healthData.get("hostname"));
                }
                if (healthData.containsKey("version")) {
                    entity.setWorkerVersion((String) healthData.get("version"));
                }
            }
            workerRepository.save(entity);
        });
    }

    private String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        return textEncryptor.encrypt(plaintext);
    }

    private String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) return ciphertext;
        try {
            return textEncryptor.decrypt(ciphertext);
        } catch (Exception e) {
            log.warn("Failed to decrypt worker auth token, returning original value");
            return ciphertext;
        }
    }

    private WorkerDTO toDTO(ClaudeWorkerEntity entity) {
        return WorkerDTO.builder()
                .workerId(entity.getWorkerId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .authMode(entity.getAuthMode())
                .status(entity.getStatus())
                .hostname(entity.getHostname())
                .workerVersion(entity.getWorkerVersion())
                .lastHeartbeat(entity.getLastHeartbeat())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
