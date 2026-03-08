package com.foggy.navigator.coding.agent.git;

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

    @Autowired
    private OpenHandsInstanceManager instanceManager;

    @Value("${foggy.coding-agent.openhands.api-key:}")
    private String defaultOpenHandsApiKey;

    private final Map<String, OpenHandsClient> clients = new ConcurrentHashMap<>();

    public OpenHandsClientFactory(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public OpenHandsClient getClientForUser(String userId) {
        return clients.computeIfAbsent(userId, id -> {
            OpenHandsInstanceManager.UserInstance instance = instanceManager.ensureUserInstance(id);
            log.info("创建 OpenHandsClient 实例: userId={}, baseUrl={}", id, instance.getBaseUrl());
            return new OpenHandsClient(restTemplate, instance.getBaseUrl(), defaultOpenHandsApiKey);
        });
    }

    public void removeClient(String userId) {
        log.info("移除 OpenHandsClient 实例: userId={}", userId);
        clients.remove(userId);
    }
}
