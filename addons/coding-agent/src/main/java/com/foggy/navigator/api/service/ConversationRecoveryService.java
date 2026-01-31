package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import com.foggy.navigator.foundation.git.model.v1.AppConversationInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationRecoveryService {

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private OpenHandsClientFactory clientFactory;

    @Value("${foggy.coding-agent.recovery.auto-recover-on-startup:false}")
    private boolean autoRecoverOnStartup;

    @Transactional
    public void recoverConversation(String conversationId) {
        log.info("开始恢复会话: conversationId={}", conversationId);

        ConversationEntity entity = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));

        if (entity.getStatus() == ConversationEntity.ConversationStatus.READY ||
            entity.getStatus() == ConversationEntity.ConversationStatus.RUNNING ||
            entity.getStatus() == ConversationEntity.ConversationStatus.IDLE) {
            log.info("会话状态正常，无需恢复: conversationId={}, status={}", conversationId, entity.getStatus());
            return;
        }

        if (entity.getStatus() == ConversationEntity.ConversationStatus.STOPPED ||
            entity.getStatus() == ConversationEntity.ConversationStatus.PAUSED) {
            log.info("会话已停止/暂停，尝试恢复 sandbox: conversationId={}", conversationId);
            resumeSandbox(entity);
        } else if (entity.getStatus() == ConversationEntity.ConversationStatus.ERROR) {
            log.warn("会话处于错误状态，尝试恢复: conversationId={}", conversationId);
            recoverFromError(entity);
        }
    }

    @Transactional
    public List<Conversation> recoverAllConversations() {
        log.info("开始恢复所有会话");

        List<ConversationEntity> entities = conversationRepository.findAll();

        List<Conversation> recovered = entities.stream()
                .filter(entity -> entity.getStatus() == ConversationEntity.ConversationStatus.STOPPED ||
                                 entity.getStatus() == ConversationEntity.ConversationStatus.PAUSED ||
                                 entity.getStatus() == ConversationEntity.ConversationStatus.ERROR)
                .map(entity -> {
                    try {
                        recoverConversation(entity.getConversationId());
                        return conversationService.getConversation(entity.getConversationId());
                    } catch (Exception e) {
                        log.error("恢复会话失败: conversationId={}", entity.getConversationId(), e);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        log.info("会话恢复完成，共恢复 {} 个会话", recovered.size());
        return recovered;
    }

    private void resumeSandbox(ConversationEntity entity) {
        try {
            String sandboxId = entity.getSandboxId();
            if (sandboxId == null || sandboxId.isEmpty()) {
                throw new IllegalStateException("SandboxId 为空，无法恢复");
            }

            log.info("恢复 sandbox: sandboxId={}", sandboxId);
            OpenHandsClient client = clientFactory.getClientForUser(entity.getUserId());
            client.resumeSandbox(sandboxId);

            entity.setStatus(ConversationEntity.ConversationStatus.READY);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            log.info("Sandbox 恢复成功: conversationId={}, sandboxId={}", entity.getConversationId(), sandboxId);

        } catch (Exception e) {
            log.error("恢复 sandbox 失败: conversationId={}", entity.getConversationId(), e);
            entity.setStatus(ConversationEntity.ConversationStatus.ERROR);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            throw new RuntimeException("恢复 sandbox 失败", e);
        }
    }

    private void recoverFromError(ConversationEntity entity) {
        try {
            log.info("尝试从错误状态恢复: conversationId={}", entity.getConversationId());

            String ohConversationId = entity.getOhConversationId();
            if (ohConversationId != null && entity.getUserId() != null) {
                OpenHandsClient client = clientFactory.getClientForUser(entity.getUserId());
                AppConversationInfo info = client.getConversationInfo(ohConversationId);
                if (info != null) {
                    String sandboxStatus = info.getSandboxStatus();
                    if ("READY".equalsIgnoreCase(sandboxStatus) || "running".equalsIgnoreCase(sandboxStatus)) {
                        entity.setStatus(ConversationEntity.ConversationStatus.READY);
                        entity.setUpdatedAt(LocalDateTime.now());
                        conversationRepository.save(entity);
                        log.info("OH 会话正常运行，恢复成功: conversationId={}", entity.getConversationId());
                        return;
                    }
                    if ("paused".equalsIgnoreCase(sandboxStatus)) {
                        client.resumeSandbox(entity.getSandboxId());
                        entity.setStatus(ConversationEntity.ConversationStatus.READY);
                        entity.setUpdatedAt(LocalDateTime.now());
                        conversationRepository.save(entity);
                        log.info("Sandbox 已恢复: conversationId={}", entity.getConversationId());
                        return;
                    }
                }
            }

            entity.setStatus(ConversationEntity.ConversationStatus.ERROR);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            throw new RuntimeException("无法从错误状态恢复，OH 会话不存在或已损坏");

        } catch (Exception e) {
            log.error("从错误状态恢复失败: conversationId={}", entity.getConversationId(), e);
            entity.setStatus(ConversationEntity.ConversationStatus.ERROR);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            throw new RuntimeException("从错误状态恢复失败", e);
        }
    }

    @Transactional
    public void cleanupStoppedConversations() {
        log.info("清理已停止的会话");

        List<ConversationEntity> stoppedEntities = conversationRepository.findByStatus(
                ConversationEntity.ConversationStatus.STOPPED
        );

        for (ConversationEntity entity : stoppedEntities) {
            try {
                if (entity.getOhConversationId() != null && entity.getUserId() != null) {
                    OpenHandsClient client = clientFactory.getClientForUser(entity.getUserId());
                    AppConversationInfo info = client.getConversationInfo(entity.getOhConversationId());
                    if (info == null || "ERROR".equalsIgnoreCase(info.getSandboxStatus())) {
                        log.info("清理不可达的会话: conversationId={}", entity.getConversationId());
                        conversationRepository.delete(entity);
                    }
                }
            } catch (Exception e) {
                log.error("清理会话失败: conversationId={}", entity.getConversationId(), e);
            }
        }

        log.info("清理完成");
    }

    @Transactional
    public void deleteConversation(String conversationId, boolean forceDelete) {
        log.info("删除会话: conversationId={}, forceDelete={}", conversationId, forceDelete);

        try {
            conversationService.deleteConversation(conversationId);
        } catch (Exception e) {
            if (forceDelete) {
                log.warn("正常删除失败，强制删除会话: conversationId={}", conversationId, e);
                conversationRepository.deleteByConversationId(conversationId);
            } else {
                throw e;
            }
        }
    }

    @Transactional
    public int deleteExpiredConversations(LocalDateTime cutoffTime) {
        log.info("删除过期会话: cutoffTime={}", cutoffTime);

        List<ConversationEntity> expiredEntities = conversationRepository.findByCreatedAtBefore(cutoffTime);
        int deletedCount = 0;

        for (ConversationEntity entity : expiredEntities) {
            try {
                log.info("删除过期会话: conversationId={}, createdAt={}",
                        entity.getConversationId(), entity.getCreatedAt());
                deleteConversation(entity.getConversationId(), true);
                deletedCount++;
            } catch (Exception e) {
                log.error("删除过期会话失败: conversationId={}", entity.getConversationId(), e);
            }
        }

        log.info("删除过期会话完成: 删除了 {} 个会话", deletedCount);
        return deletedCount;
    }

    public CleanupStatistics getCleanupStatistics() {
        long totalCount = conversationRepository.count();
        long stoppedCount = conversationRepository.findByStatus(ConversationEntity.ConversationStatus.STOPPED).size();
        long errorCount = conversationRepository.findByStatus(ConversationEntity.ConversationStatus.ERROR).size();
        long readyCount = conversationRepository.findByStatus(ConversationEntity.ConversationStatus.READY).size();

        return CleanupStatistics.builder()
                .totalCount(totalCount)
                .stoppedCount(stoppedCount)
                .errorCount(errorCount)
                .readyCount(readyCount)
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class CleanupStatistics {
        private long totalCount;
        private long stoppedCount;
        private long errorCount;
        private long readyCount;
    }
}
