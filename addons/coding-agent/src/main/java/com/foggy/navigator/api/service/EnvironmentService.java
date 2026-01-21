package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.api.model.Environment;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import com.foggy.navigator.foundation.git.ValidationServiceClient;
import com.foggy.navigator.foundation.git.model.ContainerConfig;
import com.foggy.navigator.foundation.git.util.NamespaceGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 环境管理服务
 */
@Service
@Slf4j
public class EnvironmentService {

    @Autowired
    private OpenHandsContainerManager containerManager;

    @Autowired
    private ValidationServiceClient validationClient;

    @Value("${foggy.coding-agent.openhands.workspace-base:/workspace}")
    private String workspaceBase;

    // 内存存储（生产环境应使用数据库）
    private final Map<String, Environment> environments = new ConcurrentHashMap<>();

    /**
     * 创建编辑环境
     */
    public Environment createEnvironment(CreateEnvironmentRequest request) {
        log.info("创建编辑环境: userId={}, projectId={}, branch={}",
                request.getUserId(), request.getProjectId(), request.getBranchName());

        // 生成环境ID和会话ID
        String environmentId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        // 生成 namespace
        String namespace = NamespaceGenerator.generateForSession(
                request.getUserId(),
                sessionId
        );

        // 生成工作空间路径
        String workspacePath = workspaceBase + "/" + request.getUserId() + "/" + sessionId;

        // 创建环境对象
        Environment environment = Environment.builder()
                .id(environmentId)
                .userId(request.getUserId())
                .sessionId(sessionId)
                .projectId(request.getProjectId())
                .gitRepoUrl(request.getGitRepoUrl())
                .branchName(request.getBranchName())
                .workspacePath(workspacePath)
                .namespace(namespace)
                .validationServiceUrl(request.getValidationServiceUrl())
                .status(Environment.EnvironmentStatus.CREATING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        // 保存环境
        environments.put(environmentId, environment);

        try {
            // 创建 OpenHands 容器
            ContainerConfig containerConfig = ContainerConfig.builder()
                    .apiKey(request.getApiKey())
                    .modelName(request.getModelName() != null ? request.getModelName() : "gpt-4")
                    .build();

            String containerId = containerManager.createContainer(
                    request.getUserId(),
                    sessionId,
                    containerConfig
            );

            environment.setContainerId(containerId);

            // 等待容器就绪
            boolean ready = containerManager.waitForContainerReady(containerId, 60);
            if (!ready) {
                throw new RuntimeException("容器启动超时");
            }

            // 更新状态为就绪
            environment.setStatus(Environment.EnvironmentStatus.READY);
            environment.setUpdatedAt(LocalDateTime.now());

            log.info("环境创建成功: environmentId={}, containerId={}, namespace={}",
                    environmentId, containerId, namespace);

            return environment;

        } catch (Exception e) {
            log.error("创建环境失败: environmentId={}", environmentId, e);
            environment.setStatus(Environment.EnvironmentStatus.ERROR);
            environment.setUpdatedAt(LocalDateTime.now());
            throw new RuntimeException("创建环境失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取环境信息
     */
    public Environment getEnvironment(String environmentId) {
        Environment environment = environments.get(environmentId);
        if (environment == null) {
            throw new RuntimeException("环境不存在: " + environmentId);
        }
        return environment;
    }

    /**
     * 销毁环境
     */
    public void destroyEnvironment(String environmentId) {
        log.info("销毁环境: environmentId={}", environmentId);

        Environment environment = environments.get(environmentId);
        if (environment == null) {
            log.warn("环境不存在: environmentId={}", environmentId);
            return;
        }

        try {
            // 销毁容器
            if (environment.getContainerId() != null) {
                containerManager.destroyContainer(environment.getContainerId());
            }

            // 更新状态
            environment.setStatus(Environment.EnvironmentStatus.DESTROYED);
            environment.setUpdatedAt(LocalDateTime.now());

            // 从内存中移除
            environments.remove(environmentId);

            log.info("环境已销毁: environmentId={}", environmentId);

        } catch (Exception e) {
            log.error("销毁环境失败: environmentId={}", environmentId, e);
            throw new RuntimeException("销毁环境失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检查环境是否存在
     */
    public boolean exists(String environmentId) {
        return environments.containsKey(environmentId);
    }
}
