package com.foggy.navigator.coding.agent.spi;

import com.foggy.navigator.coding.agent.api.model.*;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.service.ConversationService;
import com.foggy.navigator.coding.agent.api.service.GitCredentialService;
import com.foggy.navigator.coding.agent.api.service.MessageService;
import com.foggy.navigator.coding.agent.git.GitProviderFactory;
import com.foggy.navigator.coding.agent.git.GitProviderService;
import com.foggy.navigator.common.dto.GitProviderConfigDTO;
import com.foggy.navigator.spi.coding.CodingAgentFacade;
import com.foggy.navigator.spi.config.GitProviderManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CodingAgentFacade 的实现
 * 直接调用同 JVM 内的 Service Bean，取代 HTTP 自调用
 */
@Slf4j
@Service
public class CodingAgentFacadeImpl implements CodingAgentFacade {

    private final GitCredentialService gitCredentialService;
    private final GitProviderFactory gitProviderFactory;
    private final ConversationService conversationService;
    private final MessageService messageService;
    @Nullable
    private final GitProviderManager gitProviderManager;

    public CodingAgentFacadeImpl(GitCredentialService gitCredentialService,
                                  GitProviderFactory gitProviderFactory,
                                  ConversationService conversationService,
                                  MessageService messageService,
                                  @Nullable GitProviderManager gitProviderManager) {
        this.gitCredentialService = gitCredentialService;
        this.gitProviderFactory = gitProviderFactory;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.gitProviderManager = gitProviderManager;
    }

    @Override
    public List<Map<String, Object>> listGitCredentials(String userId) {
        log.debug("SPI: listGitCredentials for userId={}", userId);
        List<GitCredentialResponse> credentials = gitCredentialService.listCredentials(userId);
        if (!credentials.isEmpty()) {
            return credentials.stream()
                    .map(this::credentialToMap)
                    .collect(Collectors.toList());
        }
        // 回退到平台 Git 配置（Setup 向导中配置的）
        if (gitProviderManager != null && gitProviderManager.hasAnyProvider(userId)) {
            log.info("No local git credentials, falling back to platform git providers for userId={}", userId);
            List<GitProviderConfigDTO> providers = gitProviderManager.listGitProviders(userId);
            return providers.stream()
                    .map(this::platformProviderToMap)
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    @Override
    public List<Map<String, Object>> listGitProjects(String userId, String credentialId) {
        log.debug("SPI: listGitProjects for userId={}, credentialId={}", userId, credentialId);
        GitCredentialEntity credential = gitCredentialService.getCredentialEntity(userId, credentialId);
        GitProviderService provider = gitProviderFactory.getService(credential.getProvider());
        List<GitProject> projects = provider.listProjects(credential, null, 1, 100);
        return projects.stream()
                .map(this::projectToMap)
                .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> listGitBranches(String userId, String credentialId, String projectId) {
        log.debug("SPI: listGitBranches for userId={}, credentialId={}, projectId={}", userId, credentialId, projectId);
        GitCredentialEntity credential = gitCredentialService.getCredentialEntity(userId, credentialId);
        GitProviderService provider = gitProviderFactory.getService(credential.getProvider());
        List<GitBranch> branches = provider.listBranches(credential, projectId, null);
        return branches.stream()
                .map(this::branchToMap)
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> createConversation(String userId, Map<String, Object> params) {
        log.debug("SPI: createConversation for userId={}", userId);
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId(userId)
                .gitCredentialId((String) params.get("credentialId"))
                .gitProjectId((String) params.get("projectId"))
                .baseBranch((String) params.get("branch"))
                .taskDescription((String) params.get("title"))
                .build();
        Conversation conversation = conversationService.createConversation(request);
        return conversationToMap(conversation);
    }

    @Override
    public Map<String, Object> sendMessage(String userId, String conversationId, Map<String, Object> params) {
        log.debug("SPI: sendMessage for conversationId={}", conversationId);
        SendMessageRequest request = SendMessageRequest.builder()
                .content((String) params.get("message"))
                .build();
        Message message = messageService.sendMessage(conversationId, request);
        Map<String, Object> result = new HashMap<>();
        result.put("messageId", message.getMessageId());
        result.put("content", message.getContent());
        return result;
    }

    @Override
    public Map<String, Object> getConversationStatus(String userId, String conversationId) {
        log.debug("SPI: getConversationStatus for conversationId={}", conversationId);
        Conversation conversation = conversationService.getConversation(conversationId);
        return conversationToMap(conversation);
    }

    private Map<String, Object> platformProviderToMap(GitProviderConfigDTO dto) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", "platform:" + dto.getId());
        map.put("name", dto.getProviderType().name() + (dto.getUsername() != null ? " (" + dto.getUsername() + ")" : ""));
        map.put("type", dto.getProviderType().name());
        map.put("serverUrl", dto.getBaseUrl());
        map.put("source", "platform");
        return map;
    }

    private Map<String, Object> credentialToMap(GitCredentialResponse cred) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", cred.getCredentialId());
        map.put("name", cred.getDisplayName());
        map.put("type", cred.getProvider() != null ? cred.getProvider().name() : null);
        map.put("serverUrl", cred.getServerUrl());
        return map;
    }

    private Map<String, Object> projectToMap(GitProject project) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("path_with_namespace", project.getPathWithNamespace());
        map.put("description", project.getDescription());
        map.put("defaultBranch", project.getDefaultBranch());
        map.put("httpUrlToRepo", project.getHttpUrlToRepo());
        return map;
    }

    private Map<String, Object> branchToMap(GitBranch branch) {
        Map<String, Object> map = new HashMap<>();
        map.put("name", branch.getName());
        map.put("default", branch.isDefault());
        map.put("protected", branch.isProtected());
        return map;
    }

    private Map<String, Object> conversationToMap(Conversation conv) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", conv.getConversationId());
        map.put("status", conv.getStatus() != null ? conv.getStatus().name() : null);
        map.put("sessionId", conv.getSessionId());
        map.put("gitRepoUrl", conv.getGitRepoUrl());
        map.put("baseBranch", conv.getBaseBranch());
        map.put("workingBranch", conv.getWorkingBranch());
        return map;
    }
}
