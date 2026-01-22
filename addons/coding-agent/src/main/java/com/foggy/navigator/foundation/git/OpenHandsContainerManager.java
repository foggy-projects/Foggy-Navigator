package com.foggy.navigator.foundation.git;

import com.foggy.navigator.foundation.git.model.ContainerConfig;
import com.foggy.navigator.foundation.git.model.ContainerStatus;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenHands 容器管理服务
 * 负责动态创建和销毁 OpenHands 容器
 */
@Service
@Slf4j
public class OpenHandsContainerManager {

    private DockerClient dockerClient;
    private boolean useMock = false;

    @Value("${foggy.coding-agent.openhands.image:ghcr.io/all-hands-ai/openhands:main}")
    private String openHandsImage;

    @Value("${foggy.coding-agent.openhands.workspace-base:/workspace}")
    private String workspaceBase;

    public OpenHandsContainerManager() {
        try {
            String dockerHost = System.getenv("DOCKER_HOST");
            if (dockerHost == null || dockerHost.isEmpty()) {
                dockerHost = System.getProperty("docker.host", "tcp://localhost:2375");
            }

            log.info("连接到Docker: {}", dockerHost);

            DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost(dockerHost)
                    .build();

            ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .sslConfig(config.getSSLConfig())
                    .maxConnections(100)
                    .connectionTimeout(Duration.ofSeconds(30))
                    .responseTimeout(Duration.ofSeconds(45))
                    .build();

            this.dockerClient = DockerClientBuilder.getInstance(config)
                    .withDockerHttpClient(httpClient)
                    .build();
        } catch (Exception e) {
            log.warn("无法连接到Docker，使用模拟模式: {}", e.getMessage());
            this.useMock = true;
        }
    }

    /**
     * 动态创建 OpenHands 容器
     *
     * @param userId    用户ID
     * @param sessionId 会话ID
     * @param config    容器配置
     * @return 容器ID
     */
    public String createContainer(String userId, String sessionId, ContainerConfig config) {
        if (useMock) {
            String mockContainerId = "mock-container-" + userId + "-" + sessionId;
            log.info("模拟模式：创建 OpenHands 容器: {}", mockContainerId);
            return mockContainerId;
        }

        String containerName = "openhands-" + userId + "-" + sessionId;
        String workspacePath = workspaceBase + "/" + userId + "/" + sessionId;

        log.info("创建 OpenHands 容器: name={}, workspace={}", containerName, workspacePath);

        try {
            // 构建环境变量
            List<String> env = buildEnvironmentVariables(config, workspacePath);

            // 创建容器
            CreateContainerResponse container = dockerClient.createContainerCmd(openHandsImage)
                    .withName(containerName)
                    .withEnv(env)
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withPrivileged(true)
                                    .withBinds(
                                            new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock")),
                                            new Bind(workspaceBase, new Volume("/opt/workspace"))
                                    )
                    )
                    .exec();

            // 启动容器
            dockerClient.startContainerCmd(container.getId()).exec();

            log.info("OpenHands 容器已创建并启动: containerId={}", container.getId());
            return container.getId();

        } catch (Exception e) {
            log.error("创建 OpenHands 容器失败: name={}", containerName, e);
            throw new RuntimeException("创建容器失败", e);
        }
    }

    /**
     * 销毁容器
     *
     * @param containerId 容器ID
     */
    public void destroyContainer(String containerId) {
        if (containerId == null || containerId.isEmpty()) {
            log.warn("容器ID为空，跳过销毁");
            return;
        }

        if (useMock) {
            log.info("模拟模式：销毁 OpenHands 容器: containerId={}", containerId);
            return;
        }

        try {
            log.info("销毁 OpenHands 容器: containerId={}", containerId);

            // 停止容器
            dockerClient.stopContainerCmd(containerId)
                    .withTimeout(30)
                    .exec();

            // 删除容器
            dockerClient.removeContainerCmd(containerId)
                    .withForce(true)
                    .exec();

            log.info("OpenHands 容器已销毁: containerId={}", containerId);

        } catch (Exception e) {
            log.error("销毁容器失败: containerId={}", containerId, e);
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 获取容器状态
     *
     * @param containerId 容器ID
     * @return 容器状态
     */
    public ContainerStatus getContainerStatus(String containerId) {
        try {
            InspectContainerResponse response = dockerClient
                    .inspectContainerCmd(containerId)
                    .exec();

            return ContainerStatus.builder()
                    .id(containerId)
                    .name(response.getName())
                    .running(response.getState().getRunning())
                    .status(response.getState().getStatus())
                    .build();

        } catch (Exception e) {
            log.error("获取容器状态失败: containerId={}", containerId, e);
            return ContainerStatus.builder()
                    .id(containerId)
                    .running(false)
                    .status("UNKNOWN")
                    .build();
        }
    }

    /**
     * 检查容器是否就绪
     *
     * @param containerId 容器ID
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否就绪
     */
    public boolean waitForContainerReady(String containerId, int timeoutSeconds) {
        log.info("等待容器就绪: containerId={}, timeout={}s", containerId, timeoutSeconds);

        if (useMock) {
            log.info("模拟模式：容器已就绪: {}", containerId);
            return true;
        }

        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeout) {
            ContainerStatus status = getContainerStatus(containerId);
            if (status.isRunning()) {
                log.info("容器已就绪: containerId={}", containerId);
                return true;
            }

            try {
                Thread.sleep(1000); // 每秒检查一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.error("容器启动超时: containerId={}", containerId);
        return false;
    }

    /**
     * 构建环境变量
     */
    private List<String> buildEnvironmentVariables(ContainerConfig config, String workspacePath) {
        List<String> env = new ArrayList<>();

        // LLM 配置
        if (config.getApiKey() != null) {
            env.add("OPENAI_API_KEY=" + config.getApiKey());
        }
        if (config.getModelName() != null) {
            env.add("LLM_MODEL=" + config.getModelName());
        }
        if (config.getApiBaseUrl() != null) {
            env.add("LLM_BASE_URL=" + config.getApiBaseUrl());
        }

        // 工作空间配置
        env.add("WORKSPACE_BASE=" + workspacePath);
        env.add("FILE_STORE_PATH=/.openhands");

        // Git 配置
        env.add("GIT_CREDENTIALS_HELPER=store");

        return env;
    }
}
