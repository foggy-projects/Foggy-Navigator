package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.dto.UnifiedSessionDTO;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话管理 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/sessions")
@RequireAuth
public class SessionController {

    private final SessionManager sessionManager;
    private final AgentInvoker agentInvoker;
    private final SessionRepository sessionRepository;
    private final SessionMetadataService sessionMetadataService;
    private final SessionTaskResourceAccessService resourceAccessService;

    public SessionController(SessionManager sessionManager, AgentInvoker agentInvoker,
                             SessionRepository sessionRepository,
                             SessionMetadataService sessionMetadataService,
                             SessionTaskResourceAccessService resourceAccessService) {
        this.sessionManager = sessionManager;
        this.agentInvoker = agentInvoker;
        this.sessionRepository = sessionRepository;
        this.sessionMetadataService = sessionMetadataService;
        this.resourceAccessService = resourceAccessService;
    }

    /**
     * 创建会话
     */
    @PostMapping
    public RX<Session> createSession(@RequestBody CreateSessionForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Create session: agentId={}, user={}", form.getAgentId(), user.getUsername());

        if (form.getParentSessionId() != null && !form.getParentSessionId().isBlank()) {
            resourceAccessService.requireOwnedSession(
                    form.getParentSessionId(), user.getUserId(), user.getTenantId());
        }

        String sessionId = sessionManager.createSession(SessionCreateRequest.builder()
                .userId(user.getUserId())
                .tenantId(user.getTenantId())
                .agentId(form.getAgentId())
                .parentSessionId(form.getParentSessionId())
                .taskName(form.getTitle())
                .build());

        Session session = sessionManager.getSession(sessionId);
        return RX.ok(session);
    }

    /**
     * 查询会话列表
     * 返回统一会话 DTO。
     */
    @GetMapping
    public RX<List<UnifiedSessionDTO>> listSessions(
            @RequestParam(required = false) String agentId) {
        CurrentUser user = UserContext.getCurrentUser();
        List<Session> sessions;
        if (agentId != null && !agentId.isBlank()) {
            sessions = sessionRepository.findByUserIdAndTenantIdAndAgentIdOrderByUpdatedAtDesc(
                            user.getUserId(), user.getTenantId(), agentId)
                    .stream()
                    .map(SessionController::toSession)
                    .collect(Collectors.toList());
        } else {
            sessions = sessionRepository.findByUserIdAndTenantIdOrderByUpdatedAtDesc(
                            user.getUserId(), user.getTenantId())
                    .stream()
                    .map(SessionController::toSession)
                    .collect(Collectors.toList());
        }

        // claude-worker 会话由 Workers 工作台独立管理，不在通用聊天列表中显示
        sessions = sessions.stream()
                .filter(s -> !"claude-worker".equals(s.getAgentId()))
                .collect(Collectors.toList());

        List<UnifiedSessionDTO> result = sessions.stream()
                .map(UnifiedSessionDTO::fromSession)
                .collect(Collectors.toList());

        return RX.ok(result);
    }

    /**
     * 获取单个会话
     */
    @GetMapping("/{id}")
    public RX<Session> getSession(@PathVariable String id) {
        CurrentUser user = UserContext.getCurrentUser();
        resourceAccessService.requireOwnedSession(id, user.getUserId(), user.getTenantId());
        Session session = sessionManager.getSession(id);
        if (session == null) {
            throw RX.throwB("Session not found: " + id);
        }
        return RX.ok(session);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{id}")
    public RX<Void> deleteSession(@PathVariable String id) {
        CurrentUser user = UserContext.getCurrentUser();
        resourceAccessService.requireOwnedSession(id, user.getUserId(), user.getTenantId());
        log.info("Delete session: id={}", id);
        try {
            sessionMetadataService.deleteConversation(id, user.getUserId());
        } catch (IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
        return RX.ok();
    }

    /**
     * 获取会话消息列表（全量）
     */
    @GetMapping("/{id}/messages")
    public RX<List<Message>> getMessages(@PathVariable String id) {
        CurrentUser user = UserContext.getCurrentUser();
        resourceAccessService.requireOwnedSession(id, user.getUserId(), user.getTenantId());
        List<Message> messages = sessionManager.getAllMessages(id);
        return RX.ok(messages);
    }

    /**
     * 获取会话最新消息（分页，从尾部开始）。
     * 用于聊天面板按需加载：首次加载最新 N 条，向上滚动时加载更早的消息。
     *
     * @param id     会话ID
     * @param limit  每页条数（默认50）
     * @param offset 从尾部的偏移量（0=最新的 limit 条，50=跳过最新50条后的 limit 条）
     */
    @GetMapping("/{id}/messages/latest")
    public RX<Map<String, Object>> getLatestMessages(
            @PathVariable String id,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        CurrentUser user = UserContext.getCurrentUser();
        resourceAccessService.requireOwnedSession(id, user.getUserId(), user.getTenantId());
        long total = sessionManager.countMessages(id);
        List<Message> messages = sessionManager.getLatestMessages(id, limit, offset);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messages", messages);
        result.put("total", total);
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("hasMore", (long) (offset + limit) < total);
        return RX.ok(result);
    }

    /**
     * 发送消息（用户消息 + 触发Agent异步处理）
     */
    @PostMapping("/{id}/messages")
    public RX<Message> sendMessage(
            @PathVariable String id,
            @RequestBody SendMessageForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Send message: sessionId={}, user={}", id, user.getUsername());

        SessionEntity session = resourceAccessService.requireOwnedSession(
                id, user.getUserId(), user.getTenantId());

        // 1. 持久化用户消息
        Message userMessage = Message.user(id, form.getContent());
        String messageId = sessionManager.addMessage(id, userMessage);
        userMessage.setId(messageId);

        // 2. 异步调用Agent
        agentInvoker.invokeAsync(id, session.getAgentId(), userMessage);

        return RX.ok(userMessage);
    }

    private static Session toSession(SessionEntity entity) {
        return Session.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .tenantId(entity.getTenantId())
                .agentId(entity.getAgentId())
                .parentSessionId(entity.getParentSessionId())
                .status(com.foggy.navigator.agent.framework.session.SessionStatus.valueOf(entity.getStatus()))
                .taskName(entity.getTitle())
                .summary(entity.getSummary())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    /**
     * 获取引导卡片
     */
    @GetMapping("/guide-cards")
    public RX<List<Map<String, Object>>> getGuideCards(
            @RequestParam(required = false) String agentId) {
        List<Map<String, Object>> cards = List.of(
                Map.of("title", "开始对话", "description", "与AI助手开始新对话", "icon", "chat"),
                Map.of("title", "查看帮助", "description", "了解系统功能和使用方法", "icon", "help")
        );
        return RX.ok(cards);
    }

    // ===== Form DTOs =====

    @Data
    public static class CreateSessionForm {
        private String title;
        private String agentId;
        private String parentSessionId;
    }

    @Data
    public static class SendMessageForm {
        private String content;
    }
}
