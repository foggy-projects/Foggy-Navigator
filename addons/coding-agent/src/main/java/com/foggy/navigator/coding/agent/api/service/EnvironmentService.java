package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.CreateEnvironmentRequest;
import com.foggy.navigator.coding.agent.api.model.Environment;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import com.foggy.navigator.coding.agent.git.ValidationServiceClient;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartRequest;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class EnvironmentService {

    @Autowired
    private OpenHandsClientFactory clientFactory;

    @Autowired
    private ValidationServiceClient validationClient;

    @Value("${foggy.coding-agent.openhands.workspace-base:/workspace}")
    private String workspaceBase;

    @Value("${foggy.coding-agent.openhands.model-name:gpt-4}")
    private String configuredModel;

    private final Map<String, Environment> environments = new ConcurrentHashMap<>();

    public Environment createEnvironment(CreateEnvironmentRequest request) {
        log.info("创建编辑环境: userId={}, projectId={}, branch={}",
                request.getUserId(), request.getProjectId(), request.getBranchName());

        String environmentId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        String workspacePath = workspaceBase + "/" + request.getUserId() + "/" + sessionId;

        Environment environment = Environment.builder()
                .id(environmentId)
                .userId(request.getUserId())
                .sessionId(sessionId)
                .projectId(request.getProjectId())
                .gitRepoUrl(request.getGitRepoUrl())
                .branchName(request.getBranchName())
                .workspacePath(workspacePath)
                .validationServiceUrl(request.getValidationServiceUrl())
                .status(Environment.EnvironmentStatus.CREATING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        environments.put(environmentId, environment);

        try {
            OpenHandsClient client = clientFactory.getClientForUser(request.getUserId());

            AppConversationStartRequest ohReq = AppConversationStartRequest.builder()
                    .selectedRepository(request.getGitRepoUrl())
                    .selectedBranch(request.getBranchName())
                    .llmModel(request.getModelName() != null ? request.getModelName() : configuredModel)
                    .agentType("DEFAULT")
                    .build();

            AppConversationStartTask task = client.startConversation(ohReq);
            environment.setContainerId(task.getSandboxId());

            environment.setStatus(Environment.EnvironmentStatus.READY);
            environment.setUpdatedAt(LocalDateTime.now());

            log.info("环境创建成功: environmentId={}, sandboxId={}",
                    environmentId, task.getSandboxId());

            return environment;

        } catch (Exception e) {
            log.error("创建环境失败: environmentId={}", environmentId, e);
            environment.setStatus(Environment.EnvironmentStatus.ERROR);
            environment.setUpdatedAt(LocalDateTime.now());
            throw new RuntimeException("创建环境失败: " + e.getMessage(), e);
        }
    }

    public Environment getEnvironment(String environmentId) {
        Environment environment = environments.get(environmentId);
        if (environment == null) {
            throw new RuntimeException("环境不存在: " + environmentId);
        }
        return environment;
    }

    public void destroyEnvironment(String environmentId) {
        log.info("销毁环境: environmentId={}", environmentId);

        Environment environment = environments.get(environmentId);
        if (environment == null) {
            log.warn("环境不存在: environmentId={}", environmentId);
            return;
        }

        try {
            if (environment.getContainerId() != null) {
                OpenHandsClient client = clientFactory.getClientForUser(environment.getUserId());
                client.deleteSandbox(environment.getContainerId());
            }

            environment.setStatus(Environment.EnvironmentStatus.DESTROYED);
            environment.setUpdatedAt(LocalDateTime.now());

            environments.remove(environmentId);

            log.info("环境已销毁: environmentId={}", environmentId);

        } catch (Exception e) {
            log.error("销毁环境失败: environmentId={}", environmentId, e);
            throw new RuntimeException("销毁环境失败: " + e.getMessage(), e);
        }
    }

    public boolean exists(String environmentId) {
        return environments.containsKey(environmentId);
    }
}
