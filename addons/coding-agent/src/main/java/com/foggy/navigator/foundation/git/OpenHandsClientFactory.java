package com.foggy.navigator.foundation.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OpenHandsClientFactory {

    private final RestTemplate restTemplate;

    @Value("${foggy.coding-agent.openhands.api-url:http://localhost:3000}")
    private String defaultOpenHandsApiUrl;

    @Value("${foggy.coding-agent.openhands.api-key:}")
    private String defaultOpenHandsApiKey;

    private final Map<String, OpenHandsClient> clients = new ConcurrentHashMap<>();

    public OpenHandsClientFactory(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public OpenHandsClient getClient(String containerId) {
        return clients.computeIfAbsent(containerId, id -> {
            log.info("创建 OpenHandsClient 实例: containerId={}", id);
            return new OpenHandsClient(restTemplate, defaultOpenHandsApiUrl, defaultOpenHandsApiKey);
        });
    }

    public OpenHandsClient getClient(String containerId, String apiUrl, String apiKey) {
        String key = containerId + ":" + apiUrl;
        return clients.computeIfAbsent(key, id -> {
            log.info("创建 OpenHandsClient 实例: containerId={}, apiUrl={}", containerId, apiUrl);
            return new OpenHandsClient(restTemplate, apiUrl, apiKey);
        });
    }

    public void removeClient(String containerId) {
        log.info("移除 OpenHandsClient 实例: containerId={}", containerId);
        clients.remove(containerId);
    }
}
