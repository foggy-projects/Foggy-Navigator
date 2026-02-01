package com.foggy.navigator.agent.framework.tool.impl;

import com.foggy.navigator.agent.framework.tool.CredentialStore;
import com.foggy.navigator.agent.framework.tool.UserToolCredential;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存实现的凭证存储
 * MVP阶段使用，后续可替换为加密数据库实现
 */
@Component
public class InMemoryCredentialStore implements CredentialStore {

    private final ConcurrentHashMap<String, UserToolCredential> credentials = new ConcurrentHashMap<>();

    private String buildKey(String userId, String toolName) {
        return userId + ":" + toolName;
    }

    @Override
    public void save(UserToolCredential credential) {
        String key = buildKey(credential.getUserId(), credential.getToolName());
        credential.setUpdatedAt(LocalDateTime.now());
        if (credential.getCreatedAt() == null) {
            credential.setCreatedAt(LocalDateTime.now());
        }
        credentials.put(key, credential);
    }

    @Override
    public UserToolCredential find(String userId, String toolName) {
        return credentials.get(buildKey(userId, toolName));
    }

    @Override
    public List<UserToolCredential> findByUser(String userId) {
        return credentials.values().stream()
                .filter(c -> userId.equals(c.getUserId()))
                .collect(Collectors.toList());
    }

    @Override
    public void delete(String userId, String toolName) {
        credentials.remove(buildKey(userId, toolName));
    }

    @Override
    public boolean isValid(String userId, String toolName) {
        UserToolCredential credential = find(userId, toolName);
        return credential != null && !credential.isExpired();
    }

    @Override
    public UserToolCredential refresh(String userId, String toolName) {
        // MVP阶段暂不实现自动刷新，返回null表示需要重新授权
        return null;
    }
}
