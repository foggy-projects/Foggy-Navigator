package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.client.ClaudeWorkerClient;
import com.foggy.navigator.claude.worker.client.ClaudeWorkerClientFactory;
import com.foggy.navigator.claude.worker.model.dto.WorkerDTO;
import com.foggy.navigator.claude.worker.model.dto.WorkerStatusDTO;
import com.foggy.navigator.claude.worker.model.entity.ClaudeWorkerEntity;
import com.foggy.navigator.claude.worker.model.form.RegisterWorkerForm;
import com.foggy.navigator.claude.worker.model.form.UpdateWorkerForm;
import com.foggy.navigator.claude.worker.repository.ClaudeWorkerRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final CredentialEncryptor credentialEncryptor;

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

        // SSH 凭证
        if (form.getSshUsername() != null && !form.getSshUsername().isEmpty()) {
            entity.setSshUsername(form.getSshUsername());
        }
        if (form.getSshPort() != null) {
            entity.setSshPort(form.getSshPort());
        }
        if (form.getSshPassword() != null && !form.getSshPassword().isEmpty()) {
            entity.setSshPassword(encrypt(form.getSshPassword()));
        }
        if (form.getCodeServerPublicUrl() != null && !form.getCodeServerPublicUrl().isEmpty()) {
            entity.setCodeServerPublicUrl(form.getCodeServerPublicUrl());
        }
        if (form.getCodeServerInternalUrl() != null && !form.getCodeServerInternalUrl().isEmpty()) {
            entity.setCodeServerInternalUrl(form.getCodeServerInternalUrl());
        }
        if (form.getCodeServerPassword() != null && !form.getCodeServerPassword().isEmpty()) {
            entity.setCodeServerPassword(encrypt(form.getCodeServerPassword()));
        }

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

        // SSH 凭证: null 不改，空串清除，有值则加密存入
        if (form.getSshUsername() != null) {
            entity.setSshUsername(form.getSshUsername().isEmpty() ? null : form.getSshUsername());
        }
        if (form.getSshPort() != null) {
            entity.setSshPort(form.getSshPort());
        }
        if (form.getSshPassword() != null) {
            if (form.getSshPassword().isEmpty()) {
                entity.setSshPassword(null);
            } else {
                entity.setSshPassword(encrypt(form.getSshPassword()));
            }
        }
        // codeServer URLs: null 不改，空串清除
        if (form.getCodeServerPublicUrl() != null) {
            entity.setCodeServerPublicUrl(form.getCodeServerPublicUrl().isEmpty() ? null : form.getCodeServerPublicUrl());
        }
        if (form.getCodeServerInternalUrl() != null) {
            entity.setCodeServerInternalUrl(form.getCodeServerInternalUrl().isEmpty() ? null : form.getCodeServerInternalUrl());
        }
        // codeServerPassword: null 不改，空串清除，有值则加密
        if (form.getCodeServerPassword() != null) {
            if (form.getCodeServerPassword().isEmpty()) {
                entity.setCodeServerPassword(null);
            } else {
                entity.setCodeServerPassword(encrypt(form.getCodeServerPassword()));
            }
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
     * 获取解密后的 SSH 密码
     */
    public String getDecryptedSshPassword(ClaudeWorkerEntity entity) {
        if (entity.getSshPassword() == null) return null;
        return decrypt(entity.getSshPassword());
    }

    /**
     * 获取解密后的 Code Server 密码
     */
    public String getDecryptedCodeServerPassword(ClaudeWorkerEntity entity) {
        if (entity.getCodeServerPassword() == null) return null;
        return decrypt(entity.getCodeServerPassword());
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
        return credentialEncryptor.encrypt(plaintext);
    }

    private String decrypt(String ciphertext) {
        return credentialEncryptor.decrypt(ciphertext);
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
                .sshUsername(entity.getSshUsername())
                .sshPort(entity.getSshPort())
                .sshPasswordConfigured(entity.getSshPassword() != null)
                .codeServerPublicUrl(entity.getCodeServerPublicUrl())
                .codeServerInternalUrl(entity.getCodeServerInternalUrl())
                .codeServerPasswordConfigured(entity.getCodeServerPassword() != null)
                .build();
    }
}
