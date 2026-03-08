package com.foggy.navigator.claude.worker.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 客户端缓存工厂
 * 避免重复创建 WebClient 实例
 */
@Slf4j
@Component
public class ClaudeWorkerClientFactory {

    private final ConcurrentHashMap<String, ClaudeWorkerClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 Worker 客户端
     */
    public ClaudeWorkerClient getOrCreate(String workerId, String baseUrl, String authToken) {
        return clientCache.compute(workerId, (key, existing) -> {
            // Always create a new client if params changed
            return new ClaudeWorkerClient(workerId, baseUrl, authToken);
        });
    }

    /**
     * 移除缓存的客户端
     */
    public void remove(String workerId) {
        clientCache.remove(workerId);
    }

    /**
     * 清空所有缓存
     */
    public void clearAll() {
        clientCache.clear();
    }
}
