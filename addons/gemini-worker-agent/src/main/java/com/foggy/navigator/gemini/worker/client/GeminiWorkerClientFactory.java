package com.foggy.navigator.gemini.worker.client;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Gemini Worker Client 工厂
 */
@Component
public class GeminiWorkerClientFactory {

    private final ConcurrentHashMap<String, GeminiWorkerClient> clientCache = new ConcurrentHashMap<>();

    public GeminiWorkerClient getOrCreate(String workerId, String baseUrl, String authToken) {
        return clientCache.compute(workerId, (id, existing) -> new GeminiWorkerClient(baseUrl, authToken));
    }

    public void remove(String workerId) {
        clientCache.remove(workerId);
    }
}
