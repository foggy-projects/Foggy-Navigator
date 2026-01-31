package com.foggy.navigator.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.CreateConversationRequest;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import com.foggy.navigator.foundation.git.model.v1.AppConversationInfo;
import com.foggy.navigator.foundation.git.model.v1.AppConversationStartRequest;
import com.foggy.navigator.foundation.git.model.v1.AppConversationStartTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
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
    private OpenHandsClientFactory clientFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    @Lazy
    private ValidationService validationService;

    @Value("${foggy.coding-agent.openhands.model-name:gpt-4}")
    private String configuredModel;

    @Value("${foggy.coding-agent.validation.auto-trigger-on-create:true}")
    private boolean autoTriggerValidationOnCreate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    @Transactional
    public Conversation createConversation(CreateConversationRequest request) {
        log.info("创建对话: userId={}, projectId={}, branch={}",
                request.getUserId(), request.getProjectId(), request.getBranchName());

        String conversationId = UUID.randomUUID().toString();

        Conversation conversation = Conversation.builder()
                .conversationId(conversationId)
                .userId(request.getUserId())
                .projectId(request.getProjectId())
                .gitRepoUrl(request.getGitRepoUrl())
                .branchName(request.getBranchName())
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
            OpenHandsClient client = clientFactory.getClientForUser(request.getUserId());

            AppConversationStartRequest ohReq = AppConversationStartRequest.builder()
                    .selectedRepository(request.getGitRepoUrl())
                    .selectedBranch(request.getBranchName())
                    .initialMessage(request.getInitialMessage())
                    .llmModel(configuredModel)
                    .agentType("DEFAULT")
                    .build();

            AppConversationStartTask task = client.startConversation(ohReq);
            conversation.setOhConversationId(task.getConversationId());
            conversation.setSandboxId(task.getSandboxId());

            boolean ready = pollForReady(client, task.getConversationId(), 120);
            if (!ready) {
                throw new RuntimeException("OH 会话启动超时");
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

            if (autoTriggerValidationOnCreate) {
                try {
                    validationService.triggerValidation(conversationId);
                } catch (Exception e) {
                    log.error("自动触发验证失败: conversationId={}, error={}", conversationId, e.getMessage());
                }
            }

            log.info("对话创建成功: conversationId={}, ohConversationId={}", conversationId, task.getConversationId());
            return conversation;

        } catch (Exception e) {
            log.error("创建对话失败: conversationId={}", conversationId, e);
            conversation.setStatus(Conversation.ConversationStatus.ERROR);
            conversation.setUpdatedAt(LocalDateTime.now());
            updateConversationInDatabase(conversation);

            Event errorEvent = Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.ERROR)
                    .data(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error"))
                    .build();
            eventPublisher.publishEvent(errorEvent);

            return conversation;
        }
    }

    private boolean pollForReady(OpenHandsClient client, String ohConversationId, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                AppConversationInfo info = client.getConversationInfo(ohConversationId);
                if (info != null) {
                    String status = info.getSandboxStatus();
                    if ("READY".equalsIgnoreCase(status) || "running".equalsIgnoreCase(status)) {
                        return true;
                    }
                    if ("ERROR".equalsIgnoreCase(status)) {
                        log.error("OH 会话进入 ERROR 状态: ohConversationId={}", ohConversationId);
                        return false;
                    }
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("轮询 OH 会话状态异常 (可能尚未就绪): {}", e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        log.error("OH 会话启动超时: ohConversationId={}", ohConversationId);
        return false;
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
                .skip((long) page * size)
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
            try {
                OpenHandsClient client = clientFactory.getClientForUser(conversation.getUserId());
                client.deleteSandbox(conversation.getSandboxId());
            } catch (Exception e) {
                log.error("删除 OH sandbox 失败: sandboxId={}", conversation.getSandboxId(), e);
            }
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
            try {
                OpenHandsClient client = clientFactory.getClientForUser(conversation.getUserId());
                client.pauseSandbox(conversation.getSandboxId());
            } catch (Exception e) {
                log.error("暂停 OH sandbox 失败: sandboxId={}", conversation.getSandboxId(), e);
            }
        }

        conversation.setStatus(Conversation.ConversationStatus.PAUSED);
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
                    .ohConversationId(conversation.getOhConversationId())
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
                        entity.setOhConversationId(conversation.getOhConversationId());
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
                            .ohConversationId(entity.getOhConversationId())
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
