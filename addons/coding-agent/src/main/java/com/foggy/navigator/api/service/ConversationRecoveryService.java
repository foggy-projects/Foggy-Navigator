package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
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
    private OpenHandsContainerManager containerManager;

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

        if (entity.getStatus() == ConversationEntity.ConversationStatus.STOPPED) {
            log.info("会话已停止，尝试重启容器: conversationId={}", conversationId);
            restartContainer(entity);
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

    private void restartContainer(ConversationEntity entity) {
        try {
            String containerId = entity.getSandboxId();
            if (containerId == null || containerId.isEmpty()) {
                throw new IllegalStateException("容器ID为空，无法重启");
            }

            log.info("重启容器: containerId={}", containerId);
            boolean started = containerManager.startContainer(containerId);

            if (started) {
                boolean ready = containerManager.waitForContainerReady(containerId, 60);
                if (ready) {
                    entity.setStatus(ConversationEntity.ConversationStatus.READY);
                    entity.setUpdatedAt(LocalDateTime.now());
                    conversationRepository.save(entity);
                    log.info("容器重启成功: conversationId={}, containerId={}", entity.getConversationId(), containerId);
                } else {
                    throw new RuntimeException("容器启动超时");
                }
            } else {
                throw new RuntimeException("容器启动失败");
            }
        } catch (Exception e) {
            log.error("重启容器失败: conversationId={}", entity.getConversationId(), e);
            entity.setStatus(ConversationEntity.ConversationStatus.ERROR);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            throw new RuntimeException("重启容器失败", e);
        }
    }

    private void recoverFromError(ConversationEntity entity) {
        try {
            log.info("尝试从错误状态恢复: conversationId={}", entity.getConversationId());

            String containerId = entity.getSandboxId();
            if (containerId != null && !containerId.isEmpty()) {
                boolean exists = containerManager.containerExists(containerId);
                if (exists) {
                    boolean running = containerManager.isContainerRunning(containerId);
                    if (running) {
                        entity.setStatus(ConversationEntity.ConversationStatus.READY);
                        entity.setUpdatedAt(LocalDateTime.now());
                        conversationRepository.save(entity);
                        log.info("容器正在运行，恢复成功: conversationId={}", entity.getConversationId());
                        return;
                    } else {
                        restartContainer(entity);
                        return;
                    }
                }
            }

            entity.setStatus(ConversationEntity.ConversationStatus.ERROR);
            entity.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(entity);
            throw new RuntimeException("无法从错误状态恢复，容器不存在或已损坏");

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
                String containerId = entity.getSandboxId();
                if (containerId != null && !containerId.isEmpty()) {
                    boolean exists = containerManager.containerExists(containerId);
                    if (!exists) {
                        log.info("清理不存在的容器: conversationId={}, containerId={}", entity.getConversationId(), containerId);
                        conversationRepository.delete(entity);
                    }
                }
            } catch (Exception e) {
                log.error("清理会话失败: conversationId={}", entity.getConversationId(), e);
            }
        }

        log.info("清理完成");
    }
}
