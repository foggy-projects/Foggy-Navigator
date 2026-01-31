package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import com.foggy.navigator.foundation.git.model.v1.AppConversationInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "foggy.coding-agent.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConversationCleanupService {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ConversationRecoveryService recoveryService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private OpenHandsClientFactory clientFactory;

    @Value("${foggy.coding-agent.cleanup.idle-timeout:3600000}")
    private long idleTimeout;

    @Value("${foggy.coding-agent.cleanup.max-age:86400000}")
    private long maxAge;

    private int cleanedIdleCount = 0;
    private int cleanedExpiredCount = 0;

    @Scheduled(fixedDelayString = "${foggy.coding-agent.cleanup.interval:300000}", initialDelay = 60000)
    @Transactional
    public void cleanupIdleSessions() {
        log.debug("开始清理空闲会话");

        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(idleTimeout / 1000);

            List<ConversationEntity> idleSessions = conversationRepository
                    .findByStatusAndUpdatedAtBefore(
                            ConversationEntity.ConversationStatus.IDLE,
                            cutoffTime
                    );

            for (ConversationEntity session : idleSessions) {
                try {
                    log.info("清理空闲会话: conversationId={}, updatedAt={}",
                            session.getConversationId(), session.getUpdatedAt());
                    conversationService.deleteConversation(session.getConversationId());
                    cleanedIdleCount++;
                } catch (Exception e) {
                    log.error("清理空闲会话失败: conversationId={}", session.getConversationId(), e);
                }
            }

            if (!idleSessions.isEmpty()) {
                log.info("清理空闲会话完成: 清理了 {} 个会话", idleSessions.size());
            }
        } catch (Exception e) {
            log.error("清理空闲会话任务失败", e);
        }
    }

    @Scheduled(fixedDelayString = "${foggy.coding-agent.cleanup.max-age-check-interval:3600000}", initialDelay = 120000)
    @Transactional
    public void cleanupExpiredSessions() {
        log.debug("开始清理过期会话");

        try {
            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(maxAge / 1000);

            List<ConversationEntity> expiredSessions = conversationRepository
                    .findByCreatedAtBefore(cutoffTime);

            for (ConversationEntity session : expiredSessions) {
                try {
                    log.info("清理过期会话: conversationId={}, createdAt={}",
                            session.getConversationId(), session.getCreatedAt());
                    conversationService.deleteConversation(session.getConversationId());
                    cleanedExpiredCount++;
                } catch (Exception e) {
                    log.error("清理过期会话失败: conversationId={}", session.getConversationId(), e);
                }
            }

            if (!expiredSessions.isEmpty()) {
                log.info("清理过期会话完成: 清理了 {} 个会话", expiredSessions.size());
            }
        } catch (Exception e) {
            log.error("清理过期会话任务失败", e);
        }
    }

    @Scheduled(fixedDelayString = "${foggy.coding-agent.health.check-interval:60000}", initialDelay = 30000)
    @Transactional
    public void performHealthChecks() {
        log.debug("开始执行健康检查");

        try {
            List<ConversationEntity> runningSessions = conversationRepository
                    .findByStatus(ConversationEntity.ConversationStatus.READY);

            int syncedCount = 0;
            for (ConversationEntity session : runningSessions) {
                try {
                    if (session.getOhConversationId() != null && session.getUserId() != null) {
                        OpenHandsClient client = clientFactory.getClientForUser(session.getUserId());
                        AppConversationInfo info = client.getConversationInfo(session.getOhConversationId());
                        if (info == null || "ERROR".equalsIgnoreCase(info.getSandboxStatus())) {
                            log.warn("OH 会话异常，同步状态: conversationId={}, ohConversationId={}",
                                    session.getConversationId(), session.getOhConversationId());
                            session.setStatus(ConversationEntity.ConversationStatus.ERROR);
                            session.setUpdatedAt(LocalDateTime.now());
                            conversationRepository.save(session);
                            syncedCount++;
                        }
                    }
                } catch (Exception e) {
                    log.error("健康检查失败: conversationId={}", session.getConversationId(), e);
                }
            }

            if (syncedCount > 0) {
                log.info("健康检查完成: 同步了 {} 个会话状态", syncedCount);
            }
        } catch (Exception e) {
            log.error("健康检查任务失败", e);
        }
    }

    public CleanupStatistics getCleanupStatistics() {
        return CleanupStatistics.builder()
                .cleanedIdleCount(cleanedIdleCount)
                .cleanedExpiredCount(cleanedExpiredCount)
                .build();
    }

    @lombok.Builder
    @lombok.Data
    public static class CleanupStatistics {
        private int cleanedIdleCount;
        private int cleanedExpiredCount;
    }
}
