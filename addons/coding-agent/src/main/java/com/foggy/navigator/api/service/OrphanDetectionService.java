package com.foggy.navigator.api.service;

import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "foggy.coding-agent.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrphanDetectionService {

    @Autowired
    private OpenHandsContainerManager containerManager;

    @Autowired
    private ConversationRepository conversationRepository;

    private int orphansCleanedCount = 0;

    /**
     * 检测并清理孤儿容器 - 每 10 分钟执行一次
     */
    @Scheduled(fixedDelayString = "${foggy.coding-agent.cleanup.orphan-interval:600000}", initialDelay = 300000)
    public void detectAndCleanupOrphans() {
        log.debug("开始检测孤儿容器");

        try {
            List<String> orphanContainers = detectOrphanContainers();

            if (!orphanContainers.isEmpty()) {
                log.warn("发现 {} 个孤儿容器", orphanContainers.size());
                int cleaned = cleanupOrphans(orphanContainers);
                log.info("清理孤儿容器完成: 清理了 {} 个容器", cleaned);
            }
        } catch (Exception e) {
            log.error("检测和清理孤儿容器失败", e);
        }
    }

    /**
     * 检测孤儿容器
     */
    public List<String> detectOrphanContainers() {
        List<String> orphans = new ArrayList<>();

        try {
            // 获取所有容器
            Set<String> allContainers = containerManager.listAllContainers();
            log.debug("找到 {} 个容器", allContainers.size());

            // 检查每个容器是否在数据库中
            for (String containerId : allContainers) {
                boolean existsInDb = conversationRepository.existsBySandboxId(containerId);
                if (!existsInDb) {
                    log.debug("发现孤儿容器: {}", containerId);
                    orphans.add(containerId);
                }
            }

            return orphans;
        } catch (Exception e) {
            log.error("检测孤儿容器失败", e);
            return orphans;
        }
    }

    /**
     * 清理孤儿容器
     */
    public int cleanupOrphans(List<String> orphanContainers) {
        int cleaned = 0;

        for (String containerId : orphanContainers) {
            try {
                log.info("清理孤儿容器: {}", containerId);
                containerManager.destroyContainer(containerId);
                cleaned++;
                orphansCleanedCount++;
            } catch (Exception e) {
                log.error("清理孤儿容器失败: containerId={}", containerId, e);
            }
        }

        return cleaned;
    }

    /**
     * 清理所有孤儿容器
     */
    public int cleanupOrphans() {
        List<String> orphans = detectOrphanContainers();
        return cleanupOrphans(orphans);
    }

    /**
     * 获取已清理的孤儿容器数量
     */
    public int getOrphansCleanedCount() {
        return orphansCleanedCount;
    }
}
