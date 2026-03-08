package com.foggy.navigator.coding.agent.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.CreateConversationRequest;
import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.GitProject;
import com.foggy.navigator.coding.agent.api.model.entity.ConversationEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.repository.ConversationRepository;
import com.foggy.navigator.coding.agent.api.repository.GitCredentialRepository;
import com.foggy.navigator.coding.agent.git.GitProviderFactory;
import com.foggy.navigator.coding.agent.git.GitProviderService;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartRequest;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private GitCredentialRepository gitCredentialRepository;

    @Autowired
    private GitProviderFactory gitProviderFactory;

    @Autowired
    @Lazy
    private ValidationService validationService;

    @Autowired
    private SessionManager sessionManager;

    @Value("${foggy.coding-agent.openhands.model-name:gpt-4}")
    private String configuredModel;

    @Value("${foggy.coding-agent.validation.auto-trigger-on-create:true}")
    private boolean autoTriggerValidationOnCreate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    private static final DateTimeFormatter BRANCH_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // No @Transactional: this method does multiple independent DB ops + long-running
    // OpenHands polling. A single transaction would cause UnexpectedRollbackException
    // when the catch block tries to save ERROR state after an inner exception.
    public Conversation createConversation(CreateConversationRequest request) {
        String conversationId = UUID.randomUUID().toString();
        Conversation.ConversationBuilder conversationBuilder = Conversation.builder()
                .conversationId(conversationId)
                .userId(request.getUserId())
                .projectId(request.getProjectId())
                .status(Conversation.ConversationStatus.STARTING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());

        // 新的 Git 项目流程：通过 gitCredentialId + gitProjectId + baseBranch 创建
        if (request.getGitCredentialId() != null && request.getGitProjectId() != null) {
            log.info("创建对话(新流程): userId={}, gitCredentialId={}, gitProjectId={}, baseBranch={}",
                    request.getUserId(), request.getGitCredentialId(), request.getGitProjectId(), request.getBaseBranch());

            GitCredentialEntity credential = gitCredentialRepository.findByCredentialId(request.getGitCredentialId())
                    .orElseThrow(() -> new RuntimeException("凭证不存在: " + request.getGitCredentialId()));

            if (!credential.getUserId().equals(request.getUserId())) {
                throw new RuntimeException("无权使用该凭证");
            }

            GitProviderService gitService = gitProviderFactory.getService(credential.getProvider());
            GitProject project = gitService.getProject(credential, request.getGitProjectId());

            String baseBranch = request.getBaseBranch() != null ? request.getBaseBranch() : project.getDefaultBranch();
            String workingBranch = generateWorkingBranchName(request.getTaskDescription());

            conversationBuilder
                    .gitCredentialId(request.getGitCredentialId())
                    .gitProvider(credential.getProvider())
                    .gitProjectId(request.getGitProjectId())
                    .gitProjectPath(project.getPathWithNamespace())
                    .gitRepoUrl(project.getHttpUrlToRepo())
                    .baseBranch(baseBranch)
                    .workingBranch(workingBranch)
                    .branchName(workingBranch);  // 兼容旧字段
        } else {
            // 旧流程：直接传入 gitRepoUrl 和 branchName
            log.info("创建对话(旧流程): userId={}, projectId={}, branch={}",
                    request.getUserId(), request.getProjectId(), request.getBranchName());

            conversationBuilder
                    .gitRepoUrl(request.getGitRepoUrl())
                    .branchName(request.getBranchName())
                    .baseBranch(request.getBranchName())
                    .workingBranch(request.getBranchName());
        }

        Conversation conversation = conversationBuilder.build();

        // 创建关联的 Session（统一会话管理）
        String taskTitle = request.getTaskDescription() != null ? request.getTaskDescription() : "Coding Session";
        SessionCreateRequest sessionReq = SessionCreateRequest.builder()
                .userId(request.getUserId())
                .agentId("coding-agent")
                .taskName(taskTitle)
                .parentSessionId(request.getParentSessionId())
                .build();
        String sessionId = sessionManager.createSession(sessionReq);
        conversation.setSessionId(sessionId);
        log.info("Created associated session: conversationId={}, sessionId={}", conversationId, sessionId);

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

            // OH V1: Don't pass selectedRepository/selectedBranch to avoid git auth issues.
            // The agent will clone the repo as part of its task message.
            AppConversationStartRequest ohReq = AppConversationStartRequest.builder()
                    .llmModel(configuredModel)
                    .agentType("default")
                    .build();

            AppConversationStartTask task = client.startConversation(ohReq);
            log.info("OH V1 启动任务已创建: taskId={}, status={}", task.getId(), task.getStatus());

            // Poll start task until READY, then extract appConversationId
            AppConversationStartTask readyTask = pollStartTaskForReady(client, task.getId(), 120);
            if (readyTask == null) {
                throw new RuntimeException("OH 会话启动超时");
            }

            conversation.setOhConversationId(readyTask.getAppConversationId());
            conversation.setSandboxId(readyTask.getSandboxId());
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

            log.info("对话创建成功: conversationId={}, ohConversationId={}", conversationId, readyTask.getAppConversationId());
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

    private AppConversationStartTask pollStartTaskForReady(OpenHandsClient client, String taskId, int timeoutSeconds) {
        long startTime = System.currentTimeMillis();
        long timeout = timeoutSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeout) {
            try {
                AppConversationStartTask task = client.getStartTask(taskId);
                if (task != null) {
                    String status = task.getStatus();
                    log.debug("OH V1 启动任务状态: taskId={}, status={}, appConversationId={}",
                            taskId, status, task.getAppConversationId());

                    if ("READY".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {
                        return task;
                    }
                    if ("ERROR".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                        log.error("OH V1 启动任务失败: taskId={}, detail={}", taskId, task.getDetail());
                        return null;
                    }
                }
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("轮询 OH V1 启动任务异常 (可能尚未就绪): {}", e.getMessage());
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        log.error("OH V1 启动任务超时: taskId={}", taskId);
        return null;
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

        // 删除关联的 Session
        if (conversation.getSessionId() != null) {
            try {
                sessionManager.deleteSession(conversation.getSessionId());
                log.info("Deleted associated session: sessionId={}", conversation.getSessionId());
            } catch (Exception e) {
                log.error("删除关联 Session 失败: sessionId={}", conversation.getSessionId(), e);
            }
        }

        conversations.remove(conversationId);
        deleteConversationFromDatabase(conversationId);
        log.info("对话已删除: conversationId={}", conversationId);
    }

    /**
     * 仅删除 Conversation（不删除关联的 Session）
     * 用于从 session-module 删除会话时调用
     */
    public void deleteConversationOnly(String conversationId) {
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

        log.info("删除对话(不含Session): conversationId={}", conversationId);

        // 停止 sandbox
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
        log.info("对话已删除(不含Session): conversationId={}", conversationId);
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
                    .sessionId(conversation.getSessionId())
                    .ohConversationId(conversation.getOhConversationId())
                    .userId(conversation.getUserId())
                    .projectId(conversation.getProjectId())
                    .status(mapStatus(conversation.getStatus()))
                    .namespace(conversation.getNamespace())
                    .gitCredentialId(conversation.getGitCredentialId())
                    .gitProvider(conversation.getGitProvider())
                    .gitProjectId(conversation.getGitProjectId())
                    .gitProjectPath(conversation.getGitProjectPath())
                    .gitRepoUrl(conversation.getGitRepoUrl())
                    .baseBranch(conversation.getBaseBranch())
                    .workingBranch(conversation.getWorkingBranch())
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
                        entity.setGitCredentialId(conversation.getGitCredentialId());
                        entity.setGitProvider(conversation.getGitProvider());
                        entity.setGitProjectId(conversation.getGitProjectId());
                        entity.setGitProjectPath(conversation.getGitProjectPath());
                        entity.setGitRepoUrl(conversation.getGitRepoUrl());
                        entity.setBaseBranch(conversation.getBaseBranch());
                        entity.setWorkingBranch(conversation.getWorkingBranch());
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
                            .sessionId(entity.getSessionId())
                            .ohConversationId(entity.getOhConversationId())
                            .userId(entity.getUserId())
                            .projectId(entity.getProjectId())
                            .status(mapStatus(entity.getStatus()))
                            .namespace(entity.getNamespace())
                            .gitCredentialId(entity.getGitCredentialId())
                            .gitProvider(entity.getGitProvider())
                            .gitProjectId(entity.getGitProjectId())
                            .gitProjectPath(entity.getGitProjectPath())
                            .gitRepoUrl(entity.getGitRepoUrl())
                            .baseBranch(entity.getBaseBranch())
                            .workingBranch(entity.getWorkingBranch())
                            .branchName(entity.getWorkingBranch())  // 兼容旧字段
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

    private String generateWorkingBranchName(String taskDescription) {
        String timestamp = LocalDateTime.now().format(BRANCH_DATE_FORMAT);
        String safeName = "task";
        if (taskDescription != null && !taskDescription.isBlank()) {
            // 将任务描述转换为安全的分支名：只保留字母数字和连字符，限制长度
            safeName = taskDescription.toLowerCase()
                    .replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-")  // 非字母数字中文替换为连字符
                    .replaceAll("^-+|-+$", "")  // 移除首尾连字符
                    .replaceAll("-+", "-");  // 合并连续连字符
            if (safeName.length() > 30) {
                safeName = safeName.substring(0, 30);
            }
            if (safeName.isBlank()) {
                safeName = "task";
            }
        }
        return String.format("coding-agent/%s-%s", safeName, timestamp);
    }
}
