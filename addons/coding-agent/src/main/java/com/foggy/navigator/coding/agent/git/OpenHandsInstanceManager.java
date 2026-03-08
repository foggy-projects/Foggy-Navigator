package com.foggy.navigator.coding.agent.git;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.api.model.Container;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class OpenHandsInstanceManager {

    private final Map<String, UserInstance> userInstances = new ConcurrentHashMap<>();
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    private volatile DockerClient dockerClient;
    private volatile boolean dockerInitialized = false;
    private boolean useMock = false;

    @Value("${foggy.coding-agent.openhands.image:ghcr.io/all-hands-ai/openhands:main}")
    private String openHandsImage;

    @Value("${foggy.coding-agent.openhands.port-range-start:30100}")
    private int portRangeStart;

    @Value("${foggy.coding-agent.openhands.port-range-end:36000}")
    private int portRangeEnd;

    @Value("${foggy.coding-agent.openhands.workspace-base:/workspace}")
    private String workspaceBase;

    @Value("${foggy.coding-agent.openhands.api-key:}")
    private String apiKey;

    @Value("${foggy.coding-agent.openhands.model-name:gpt-4}")
    private String modelName;

    @Value("${foggy.coding-agent.openhands.api-base-url:}")
    private String apiBaseUrl;

    @Value("${foggy.coding-agent.openhands.oh-secret-key:openhands-dev-secret-key-1234567890}")
    private String ohSecretKey;

    @Value("${foggy.coding-agent.openhands.agent-server-image:ghcr.io/openhands/agent-server}")
    private String agentServerImage;

    @Value("${foggy.coding-agent.openhands.agent-server-tag:31536c8-python}")
    private String agentServerTag;

    @Value("${foggy.coding-agent.test-mode:false}")
    private boolean testMode;

    /**
     * 懒加载 DockerClient：首次调用时才连接 Docker daemon 并恢复已有容器端口
     */
    private synchronized DockerClient ensureDockerClient() {
        if (dockerInitialized) {
            return dockerClient;
        }
        dockerInitialized = true;

        try {
            String dockerHost = System.getenv("DOCKER_HOST");
            if (dockerHost == null || dockerHost.isEmpty()) {
                dockerHost = System.getProperty("docker.host", "tcp://localhost:2375");
            }
            log.info("InstanceManager 连接到 Docker: {}", dockerHost);

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

            // 首次连接成功后，恢复已有容器端口
            recoverExistingContainerPorts();
        } catch (Exception e) {
            log.warn("无法连接到 Docker，使用模拟模式: {}", e.getMessage());
            this.useMock = true;
        }

        return dockerClient;
    }

    /**
     * 扫描已存在的 openhands-user-* 容器，将其端口注册到 usedPorts，避免端口冲突
     */
    private void recoverExistingContainerPorts() {
        if (useMock || dockerClient == null) return;
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withNameFilter(List.of("openhands-user-"))
                    .exec();
            for (Container container : containers) {
                String state = container.getState(); // "running", "created", "exited"
                String name = container.getNames() != null && container.getNames().length > 0
                        ? container.getNames()[0].replaceFirst("^/", "") : "";
                if ("running".equalsIgnoreCase(state)) {
                    // 正在运行的容器：注册端口
                    var ports = container.getPorts();
                    if (ports != null) {
                        for (var port : ports) {
                            if (port.getPublicPort() != null && port.getPublicPort() > 0) {
                                usedPorts.add(port.getPublicPort());
                                log.info("启动恢复：注册已占用端口 {} (容器: {})", port.getPublicPort(), name);
                            }
                        }
                    }
                } else {
                    // 非运行状态（created/exited）：清除残留
                    log.info("启动清理：移除残留容器 {} (状态: {})", name, state);
                    try {
                        dockerClient.removeContainerCmd(container.getId()).withForce(true).exec();
                    } catch (Exception e) {
                        log.warn("移除残留容器失败: {}", name, e);
                    }
                }
            }
            log.info("启动端口恢复完成，已注册端口: {}", usedPorts);
        } catch (Exception e) {
            log.warn("扫描已存在容器失败: {}", e.getMessage());
        }
    }

    public synchronized UserInstance ensureUserInstance(String userId) {
        UserInstance existing = userInstances.get(userId);
        if (existing != null && existing.isRunning()) {
            if (isContainerAlive(existing.getContainerId())) {
                log.debug("用户实例已存在且运行中: userId={}, port={}", userId, existing.getPort());
                return existing;
            }
            log.warn("用户实例容器不可达，重新创建: userId={}", userId);
            cleanupInstance(userId);
        }

        log.info("为用户创建 OpenHands 实例: userId={}", userId);
        return createUserInstance(userId);
    }

    public String getBaseUrl(String userId) {
        UserInstance instance = userInstances.get(userId);
        if (instance == null) {
            return null;
        }
        return instance.getBaseUrl();
    }

    public boolean isReady(String userId) {
        UserInstance instance = userInstances.get(userId);
        if (instance == null) {
            return false;
        }
        return isHttpReady(instance.getBaseUrl());
    }

    public void shutdownUserInstance(String userId) {
        log.info("关闭用户实例: userId={}", userId);
        cleanupInstance(userId);
    }

    @PreDestroy
    public void shutdownAll() {
        log.info("关闭所有用户实例, count={}", userInstances.size());
        for (String userId : new ArrayList<>(userInstances.keySet())) {
            try {
                cleanupInstance(userId);
            } catch (Exception e) {
                log.error("关闭用户实例失败: userId={}", userId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${foggy.coding-agent.openhands.instance-health-check-interval:30000}", initialDelay = 60000)
    public void healthCheckAll() {
        if (userInstances.isEmpty()) return;
        log.debug("开始用户实例健康检查, count={}", userInstances.size());

        for (Map.Entry<String, UserInstance> entry : userInstances.entrySet()) {
            String userId = entry.getKey();
            UserInstance instance = entry.getValue();
            try {
                if (!isContainerAlive(instance.getContainerId())) {
                    log.warn("用户实例不健康: userId={}, containerId={}", userId, instance.getContainerId());
                    instance.setRunning(false);
                }
            } catch (Exception e) {
                log.error("健康检查失败: userId={}", userId, e);
            }
        }
    }

    public Map<String, UserInstance> getAllInstances() {
        return Map.copyOf(userInstances);
    }

    private UserInstance createUserInstance(String userId) {
        if (useMock || testMode) {
            int port = allocatePort();
            UserInstance mockInstance = UserInstance.builder()
                    .userId(userId)
                    .containerId("mock-instance-" + userId)
                    .port(port != -1 ? port : 30100)
                    .baseUrl("http://localhost:" + (port != -1 ? port : 30100))
                    .running(true)
                    .createdAt(LocalDateTime.now())
                    .build();
            userInstances.put(userId, mockInstance);
            log.info("模拟模式：创建用户实例: userId={}, port={}", userId, mockInstance.getPort());
            return mockInstance;
        }

        DockerClient client = ensureDockerClient();
        if (client == null) {
            throw new RuntimeException("Docker 不可用，无法创建用户实例");
        }

        int hostPort = allocatePort();
        if (hostPort == -1) {
            throw new RuntimeException("无法分配端口，端口范围已满: " + portRangeStart + "-" + portRangeEnd);
        }

        String containerName = "openhands-user-" + userId;
        String workspacePath = workspaceBase + "/" + userId;

        try {
            // Check if container already exists
            try {
                InspectContainerResponse inspect = client.inspectContainerCmd(containerName).exec();
                String existingId = inspect.getId();
                Boolean running = inspect.getState().getRunning();

                if (running != null && running) {
                    // Container exists and is running, discover its port
                    int existingPort = discoverContainerPort(inspect);
                    if (existingPort > 0) {
                        usedPorts.add(existingPort);
                        usedPorts.remove(hostPort); // release the pre-allocated port
                        UserInstance instance = UserInstance.builder()
                                .userId(userId)
                                .containerId(existingId)
                                .port(existingPort)
                                .baseUrl("http://localhost:" + existingPort)
                                .running(true)
                                .createdAt(LocalDateTime.now())
                                .build();
                        userInstances.put(userId, instance);
                        log.info("复用已存在的用户实例: userId={}, containerId={}, port={}", userId, existingId, existingPort);
                        return instance;
                    }
                }

                // Container exists but not running, remove it
                log.info("移除已停止的用户容器: userId={}, containerId={}", userId, existingId);
                try { client.stopContainerCmd(existingId).withTimeout(5).exec(); } catch (Exception ignored) {}
                client.removeContainerCmd(existingId).withForce(true).exec();
            } catch (com.github.dockerjava.api.exception.NotFoundException e) {
                // Container doesn't exist, will create new
            }

            List<String> env = buildEnvironmentVariables(workspacePath);

            ExposedPort containerPort = ExposedPort.tcp(3000);
            Ports portBindings = new Ports();
            portBindings.bind(containerPort, Ports.Binding.bindPort(hostPort));

            CreateContainerResponse container = client.createContainerCmd(openHandsImage)
                    .withName(containerName)
                    .withEnv(env)
                    .withExposedPorts(containerPort)
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withPrivileged(true)
                                    .withPortBindings(portBindings)
                                    .withBinds(
                                            new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock")),
                                            new Bind(workspaceBase, new Volume("/opt/workspace"))
                                    )
                    )
                    .exec();

            client.startContainerCmd(container.getId()).exec();

            // Wait for OH server to become HTTP-ready
            String baseUrl = "http://localhost:" + hostPort;
            log.info("等待 OpenHands 实例就绪: userId={}, port={}", userId, hostPort);
            boolean ready = waitForHttpReady(baseUrl, 60);
            if (!ready) {
                log.warn("OpenHands 实例未在超时内就绪，继续创建实例: userId={}", userId);
            } else {
                log.info("OpenHands 实例已就绪: userId={}, port={}", userId, hostPort);
            }

            UserInstance instance = UserInstance.builder()
                    .userId(userId)
                    .containerId(container.getId())
                    .port(hostPort)
                    .baseUrl("http://localhost:" + hostPort)
                    .running(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            userInstances.put(userId, instance);
            log.info("用户 OpenHands 实例已创建: userId={}, containerId={}, port={}", userId, container.getId(), hostPort);
            return instance;

        } catch (Exception e) {
            usedPorts.remove(hostPort);
            log.error("创建用户实例失败: userId={}", userId, e);
            throw new RuntimeException("创建用户 OpenHands 实例失败", e);
        }
    }

    private List<String> buildEnvironmentVariables(String workspacePath) {
        List<String> env = new ArrayList<>();

        if (apiKey != null && !apiKey.isEmpty()) {
            env.add("LLM_API_KEY=" + apiKey);
        }
        if (modelName != null && !modelName.isEmpty()) {
            env.add("LLM_MODEL=" + modelName);
        }
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) {
            env.add("LLM_BASE_URL=" + apiBaseUrl);
        }

        env.add("WORKSPACE_BASE=" + workspacePath);
        env.add("FILE_STORE_PATH=/.openhands");
        env.add("GIT_CREDENTIALS_HELPER=store");
        env.add("SANDBOX_LOCAL_RUNTIME_URL=http://host.docker.internal");
        env.add("SANDBOX_USER_ID=0");
        env.add("USE_HOST_NETWORK=false");
        env.add("OH_SANDBOX={\"kind\": \"DockerSandboxServiceInjector\", \"container_url_pattern\": \"http://host.docker.internal:{port}\"}");

        // V1-required environment variables
        if (ohSecretKey != null && !ohSecretKey.isEmpty()) {
            env.add("OH_SECRET_KEY=" + ohSecretKey);
        }
        env.add("DISABLE_MCP=true");
        if (agentServerImage != null && !agentServerImage.isEmpty()) {
            env.add("AGENT_SERVER_IMAGE_REPOSITORY=" + agentServerImage);
        }
        if (agentServerTag != null && !agentServerTag.isEmpty()) {
            env.add("AGENT_SERVER_IMAGE_TAG=" + agentServerTag);
        }

        // Forward LLM credentials to agent server containers via OH_AGENT_SERVER_ENV
        // The agent server is a separate container and doesn't inherit env vars from the main OH container.
        // litellm's OpenAI provider requires OPENAI_API_KEY in the environment.
        StringBuilder agentServerEnv = new StringBuilder("{");
        if (apiKey != null && !apiKey.isEmpty()) {
            agentServerEnv.append("\"OPENAI_API_KEY\":\"").append(apiKey).append("\"");
        }
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) {
            if (agentServerEnv.length() > 1) agentServerEnv.append(",");
            agentServerEnv.append("\"OPENAI_API_BASE\":\"").append(apiBaseUrl).append("\"");
        }
        agentServerEnv.append("}");
        env.add("OH_AGENT_SERVER_ENV=" + agentServerEnv);

        return env;
    }

    private synchronized int allocatePort() {
        for (int port = portRangeStart; port <= portRangeEnd; port++) {
            if (!usedPorts.contains(port)) {
                usedPorts.add(port);
                return port;
            }
        }
        return -1;
    }

    private void cleanupInstance(String userId) {
        UserInstance instance = userInstances.remove(userId);
        if (instance == null) return;

        usedPorts.remove(instance.getPort());

        if (!useMock && !testMode && instance.getContainerId() != null && dockerClient != null) {
            try {
                dockerClient.stopContainerCmd(instance.getContainerId()).withTimeout(10).exec();
                dockerClient.removeContainerCmd(instance.getContainerId()).withForce(true).exec();
                log.info("用户实例已销毁: userId={}, containerId={}", userId, instance.getContainerId());
            } catch (Exception e) {
                log.error("销毁用户实例容器失败: userId={}, containerId={}", userId, instance.getContainerId(), e);
            }
        }
    }

    private boolean isContainerAlive(String containerId) {
        if (useMock || testMode) return true;
        if (dockerClient == null) return false;
        try {
            InspectContainerResponse response = dockerClient.inspectContainerCmd(containerId).exec();
            Boolean running = response.getState().getRunning();
            return running != null && running;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitForHttpReady(String baseUrl, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isHttpReady(baseUrl)) {
                return true;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isHttpReady(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/api/v1/health").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private int discoverContainerPort(InspectContainerResponse inspect) {
        try {
            Map<ExposedPort, Ports.Binding[]> bindings = inspect.getNetworkSettings().getPorts().getBindings();
            if (bindings != null) {
                Ports.Binding[] portBindings = bindings.get(ExposedPort.tcp(3000));
                if (portBindings != null && portBindings.length > 0) {
                    return Integer.parseInt(portBindings[0].getHostPortSpec());
                }
            }
        } catch (Exception e) {
            log.warn("无法获取容器端口映射", e);
        }
        return -1;
    }

    public boolean isUseMock() {
        return useMock || testMode;
    }

    public DockerClient getDockerClient() {
        return dockerClient;
    }

    @Data
    @Builder
    public static class UserInstance {
        private String userId;
        private String containerId;
        private int port;
        private String baseUrl;
        private boolean running;
        private LocalDateTime createdAt;
    }
}
