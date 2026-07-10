package com.foggy.navigator.codex.worker.client;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Codex Worker Client 工厂
 * 缓存 Client 实例，避免重复创建
 */
@Component
public class CodexWorkerClientFactory {

    private final ConcurrentHashMap<String, CodexWorkerClient> clientCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 Client 实例
     */
    public CodexWorkerClient getOrCreate(String workerId, String baseUrl, String authToken) {
        return getOrCreate(workerId, baseUrl, authToken, null);
    }

    /**
     * App-server clients pin every request and response to the persisted runtime instance.
     */
    public CodexWorkerClient getOrCreate(String workerId, String baseUrl, String authToken,
                                          String expectedInstanceId) {
        return clientCache.compute(workerId, (id, existing) -> {
            // 每次都创建新实例以确保参数一致性
            return new CodexWorkerClient(baseUrl, authToken, expectedInstanceId);
        });
    }

    /**
     * 移除缓存的 Client
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
