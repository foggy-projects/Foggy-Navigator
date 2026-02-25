package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.core.AgentInvoker;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionCreateRequest;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.service.CodingConversationLookup;
import com.foggy.navigator.session.dto.UnifiedSessionDTO;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.sse.SseSessionEmitter;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final SseSessionEmitter sseSessionEmitter;
    private final SessionRepository sessionRepository;

    // 可选依赖：coding-agent 模块的会话信息查询服务
    @Autowired(required = false)
    private CodingConversationLookup codingConversationLookup;

    public SessionController(SessionManager sessionManager, AgentInvoker agentInvoker,
                             SseSessionEmitter sseSessionEmitter, SessionRepository sessionRepository) {
        this.sessionManager = sessionManager;
        this.agentInvoker = agentInvoker;
        this.sseSessionEmitter = sseSessionEmitter;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 创建会话
     */
    @PostMapping
    public RX<Session> createSession(@RequestBody CreateSessionForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        log.info("Create session: agentId={}, user={}", form.getAgentId(), user.getUsername());

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
     * 返回统一会话 DTO，包含 type 字段和 coding-agent 扩展信息
     */
    @GetMapping
    public RX<List<UnifiedSessionDTO>> listSessions(
            @RequestParam(required = false) String agentId) {
        CurrentUser user = UserContext.getCurrentUser();
        List<Session> sessions;
        if (agentId != null && !agentId.isBlank()) {
            sessions = sessionRepository.findByUserIdAndAgentIdOrderByUpdatedAtDesc(
                            user.getUserId(), agentId)
                    .stream()
                    .map(entity -> Session.builder()
                            .id(entity.getId())
                            .userId(entity.getUserId())
                            .tenantId(entity.getTenantId())
                            .agentId(entity.getAgentId())
                            .parentSessionId(entity.getParentSessionId())
                            .status(com.foggy.navigator.agent.framework.session.SessionStatus.valueOf(entity.getStatus()))
                            .taskName(entity.getTitle())
                            .createdAt(entity.getCreatedAt())
                            .updatedAt(entity.getUpdatedAt())
                            .build())
                    .collect(Collectors.toList());
        } else {
            sessions = sessionManager.findByUser(user.getUserId());
        }

        // claude-worker 会话由 /claude-tasks 页面独立管理，不在聊天列表中显示
        sessions = sessions.stream()
                .filter(s -> !"claude-worker".equals(s.getAgentId()))
                .collect(Collectors.toList());

        // 转换为 UnifiedSessionDTO 并补充 coding-agent 扩展信息
        List<UnifiedSessionDTO> result = sessions.stream()
                .map(UnifiedSessionDTO::fromSession)
                .collect(Collectors.toList());

        // 如果有 coding-agent 会话，批量查询扩展信息
        if (codingConversationLookup != null) {
            List<String> codingSessionIds = result.stream()
                    .filter(s -> "coding".equals(s.getType()))
                    .map(UnifiedSessionDTO::getId)
                    .collect(Collectors.toList());

            if (!codingSessionIds.isEmpty()) {
                Map<String, Map<String, Object>> conversationInfoMap =
                        codingConversationLookup.getConversationInfoBatch(codingSessionIds);

                result.forEach(session -> {
                    if ("coding".equals(session.getType())) {
                        Map<String, Object> info = conversationInfoMap.get(session.getId());
                        if (info != null) {
                            session.setConversationId((String) info.get("conversationId"));
                            session.setSandboxStatus((String) info.get("sandboxStatus"));
                            session.setGitRepoUrl((String) info.get("gitRepoUrl"));
                        }
                    }
                });
            }
        }

        return RX.ok(result);
    }

    /**
     * 获取单个会话
     */
    @GetMapping("/{id}")
    public RX<Session> getSession(@PathVariable String id) {
        Session session = sessionManager.getSession(id);
        if (session == null) {
            throw RX.throwB("Session not found: " + id);
        }
        return RX.ok(session);
    }

    /**
     * 删除会话
     * 如果是 coding-agent 会话，同时删除关联的 Conversation 和 sandbox
     */
    @DeleteMapping("/{id}")
    public RX<Void> deleteSession(@PathVariable String id) {
        log.info("Delete session: id={}", id);

        // 如果是 coding-agent 会话，先删除关联的 Conversation
        if (codingConversationLookup != null) {
            codingConversationLookup.deleteConversationBySessionId(id);
        }

        // 删除 Session 及其消息
        sessionManager.deleteSession(id);
        return RX.ok();
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/{id}/messages")
    public RX<List<Message>> getMessages(@PathVariable String id) {
        List<Message> messages = sessionManager.getAllMessages(id);
        return RX.ok(messages);
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

        Session session = sessionManager.getSession(id);
        if (session == null) {
            throw RX.throwB("Session not found: " + id);
        }

        // 1. 持久化用户消息
        Message userMessage = Message.user(id, form.getContent());
        String messageId = sessionManager.addMessage(id, userMessage);
        userMessage.setId(messageId);

        // 2. 异步调用Agent
        agentInvoker.invokeAsync(id, session.getAgentId(), userMessage);

        return RX.ok(userMessage);
    }

    /**
     * SSE事件流
     */
    @GetMapping("/{id}/stream")
    public SseEmitter streamEvents(@PathVariable String id) {
        log.info("SSE stream requested: sessionId={}", id);
        return sseSessionEmitter.createEmitter(id);
    }

    /**
     * 获取引导卡片
     */
    @GetMapping("/guide-cards")
    public RX<List<Map<String, Object>>> getGuideCards(
            @RequestParam(required = false) String agentId) {
        // 初期硬编码规则返回
        List<Map<String, Object>> cards;
        if ("tutor-agent".equals(agentId)) {
            cards = List.of(
                    Map.of("title", "数据查询", "description", "帮你查询和分析数据", "icon", "search"),
                    Map.of("title", "报表生成", "description", "快速生成各类报表", "icon", "chart"),
                    Map.of("title", "数据建模", "description", "辅助数据模型设计", "icon", "model")
            );
        } else {
            cards = List.of(
                    Map.of("title", "开始对话", "description", "与AI助手开始新对话", "icon", "chat"),
                    Map.of("title", "查看帮助", "description", "了解系统功能和使用方法", "icon", "help")
            );
        }
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
