package com.foggy.navigator.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.CreateConversationRequest;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import com.foggy.navigator.foundation.git.model.ContainerConfig;
import com.foggy.navigator.foundation.git.util.NamespaceGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversationService {

    @Autowired
    private OpenHandsContainerManager containerManager;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ConversationRepository conversationRepository;

    @Value("${foggy.coding-agent.openhands.workspace-base:/workspace}")
    private String workspaceBase;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Transactional
    public Conversation createConversation(CreateConversationRequest request) {
        log.info("创建对话: userId={}, projectId={}, branch={}",
                request.getUserId(), request.getProjectId(), request.getBranchName());

        String conversationId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();

        String namespace = NamespaceGenerator.generateForSession(
                request.getUserId(),
                sessionId
        );

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .userId(request.getUserId())
                .projectId(request.getProjectId())
                .gitRepoUrl(request.getGitRepoUrl())
                .branchName(request.getBranchName())
                .namespace(namespace)
                .status(Conversation.ConversationStatus.STARTING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        conversations.put(conversationId, conversation);

        saveConversationToDatabase(conversation);

        Event startEvent = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.CONVERSATION_STATUS)
                .data(Map.of("status", "STARTING"))
                .build();
        eventPublisher.publishEvent(startEvent);

        try {
            ContainerConfig containerConfig = ContainerConfig.builder()
                    .apiKey(null)
                    .modelName("gpt-4")
                    .build();

            String containerId = containerManager.createContainer(
                    request.getUserId(),
                    sessionId,
                    containerConfig
            );

            conversation.setSandboxId(containerId);

            boolean ready = containerManager.waitForContainerReady(containerId, 60);
            if (!ready) {
                throw new RuntimeException("容器启动超时");
            }

            conversation.setStatus(Conversation.ConversationStatus.READY);
            conversation.setUpdatedAt(LocalDateTime.now());

            updateConversationInDatabase(conversation);

            Event readyEvent = Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.CONVERSATION_STATUS)
                    .data(Map.of("status", "READY"))
                    .build();
            eventPublisher.publishEvent(readyEvent);

            log.info("对话创建成功: conversationId={}, containerId={}", conversationId, containerId);
            return conversation;

        } catch (Exception e) {
            log.error("创建对话失败: conversationId={}", conversationId, e);
            conversation.setStatus(Conversation.ConversationStatus.ERROR);
            conversation.setUpdatedAt(LocalDateTime.now());
            updateConversationInDatabase(conversation);
            throw new RuntimeException("创建对话失败", e);
        }
    }

    public Conversation getConversation(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            conversation = loadConversationFromDatabase(conversationId);
            if (conversation != null) {
                conversations.put(conversationId, conversation);
            }
        }
        return conversation;
    }

    public List<Conversation> listConversations(String userId, String projectId, Conversation.ConversationStatus status, int page, int size) {
        return conversations.values().stream()
                .filter(conv -> userId == null || conv.getUserId().equals(userId))
                .filter(conv -> projectId == null || conv.getProjectId().equals(projectId))
                .filter(conv -> status == null || conv.getStatus().equals(status))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .skip(page * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    public void deleteConversation(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            conversation = loadConversationFromDatabase(conversationId);
            if (conversation != null) {
                conversations.put(conversationId, conversation);
            }
        }

        if (conversation == null) {
            log.warn("对话不存在: {}", conversationId);
            return;
        }

        log.info("删除对话: conversationId={}", conversationId);

        if (conversation.getSandboxId() != null) {
            containerManager.destroyContainer(conversation.getSandboxId());
        }

        conversations.remove(conversationId);
        deleteConversationFromDatabase(conversationId);
        log.info("对话已删除: conversationId={}", conversationId);
    }

    public void stopConversation(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation == null) {
            conversation = loadConversationFromDatabase(conversationId);
            if (conversation != null) {
                conversations.put(conversationId, conversation);
            }
        }

        if (conversation == null) {
            log.warn("对话不存在: {}", conversationId);
            return;
        }

        log.info("停止对话: conversationId={}", conversationId);

        if (conversation.getSandboxId() != null) {
            containerManager.destroyContainer(conversation.getSandboxId());
        }

        conversation.setStatus(Conversation.ConversationStatus.STOPPED);
        conversation.setUpdatedAt(LocalDateTime.now());

        updateConversationInDatabase(conversation);

        log.info("对话已停止: conversationId={}", conversationId);
    }

    public boolean exists(String conversationId) {
        if (conversations.containsKey(conversationId)) {
            return true;
        }
        return conversationRepository.existsByConversationId(conversationId);
    }

    @Transactional
    protected void saveConversationToDatabase(Conversation conversation) {
        try {
            ConversationEntity entity = ConversationEntity.builder()
                    .conversationId(conversation.getConversationId())
                    .sandboxId(conversation.getSandboxId())
                    .userId(conversation.getUserId())
                    .projectId(conversation.getProjectId())
                    .status(mapStatus(conversation.getStatus()))
                    .namespace(conversation.getNamespace())
                    .gitRepoUrl(conversation.getGitRepoUrl())
                    .branchName(conversation.getBranchName())
                    .createdAt(conversation.getCreatedAt())
                    .updatedAt(conversation.getUpdatedAt())
                    .build();
            conversationRepository.save(entity);
        } catch (Exception e) {
            log.error("保存对话到数据库失败: conversationId={}", conversation.getConversationId(), e);
        }
    }

    @Transactional
    protected void updateConversationInDatabase(Conversation conversation) {
        try {
            conversationRepository.findByConversationId(conversation.getConversationId())
                    .ifPresent(entity -> {
                        entity.setSandboxId(conversation.getSandboxId());
                        entity.setStatus(mapStatus(conversation.getStatus()));
                        entity.setNamespace(conversation.getNamespace());
                        entity.setGitRepoUrl(conversation.getGitRepoUrl());
                        entity.setBranchName(conversation.getBranchName());
                        entity.setUpdatedAt(conversation.getUpdatedAt());
                        conversationRepository.save(entity);
                    });
        } catch (Exception e) {
            log.error("更新对话到数据库失败: conversationId={}", conversation.getConversationId(), e);
        }
    }

    @Transactional
    protected void deleteConversationFromDatabase(String conversationId) {
        try {
            conversationRepository.deleteByConversationId(conversationId);
        } catch (Exception e) {
            log.error("从数据库删除对话失败: conversationId={}", conversationId, e);
        }
    }

    protected Conversation loadConversationFromDatabase(String conversationId) {
        try {
            return conversationRepository.findByConversationId(conversationId)
                    .map(entity -> Conversation.builder()
                            .conversationId(entity.getConversationId())
                            .sandboxId(entity.getSandboxId())
                            .userId(entity.getUserId())
                            .projectId(entity.getProjectId())
                            .status(mapStatus(entity.getStatus()))
                            .namespace(entity.getNamespace())
                            .gitRepoUrl(entity.getGitRepoUrl())
                            .branchName(entity.getBranchName())
                            .createdAt(entity.getCreatedAt())
                            .updatedAt(entity.getUpdatedAt())
                            .build())
                    .orElse(null);
        } catch (Exception e) {
            log.error("从数据库加载对话失败: conversationId={}", conversationId, e);
            return null;
        }
    }

    private ConversationEntity.ConversationStatus mapStatus(Conversation.ConversationStatus status) {
        return ConversationEntity.ConversationStatus.valueOf(status.name());
    }

    private Conversation.ConversationStatus mapStatus(ConversationEntity.ConversationStatus status) {
        return Conversation.ConversationStatus.valueOf(status.name());
    }
}
