package com.foggy.navigator.foundation.git;

import com.foggy.navigator.foundation.git.model.ContainerConfig;
import com.foggy.navigator.foundation.git.model.ContainerStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 测试容器管理器 - 仅在模拟模式下使用
 * 启用条件: foggy.coding-agent.test-mode=true AND foggy.coding-agent.mock-docker=true
 */
@Profile("test")
@Component
@ConditionalOnProperty(name = "foggy.coding-agent.mock-docker", havingValue = "true", matchIfMissing = false)
public class TestContainerManager implements ContainerManagerInterface {
    
    @Override
    public String createContainer(String userId, String sessionId, ContainerConfig config) {
        return "test-container-" + userId + "-" + sessionId;
    }

    @Override
    public void destroyContainer(String containerId) {
        // Do nothing in test
    }

    @Override
    public ContainerStatus getContainerStatus(String containerId) {
        return ContainerStatus.builder()
                .id(containerId)
                .name("test-container")
                .running(true)
                .status("running")
                .build();
    }

    @Override
    public boolean waitForContainerReady(String containerId, int timeoutSeconds) {
        return true;
    }

    @Override
    public boolean startContainer(String containerId) {
        return true;
    }

    @Override
    public boolean containerExists(String containerId) {
        return true;
    }

    @Override
    public boolean isContainerRunning(String containerId) {
        return true;
    }
}