package com.foggy.navigator.session.event;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.session.service.SessionMessageDurablePersistenceCoordinator;
import com.foggy.navigator.session.service.SessionMessagePublicPayloadSanitizer;
import com.foggy.navigator.session.sse.UnifiedSseEmitter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监听AgentMessage事件，持久化 + SSE推送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionEventListener {

    /** session_messages.id and the descriptor messageId are VARCHAR(64). */
    private static final int MAX_DURABLE_MESSAGE_ID_LENGTH = 64;

    private final SessionManager sessionManager;
    private final UnifiedSseEmitter sseEmitter;
    private final SessionMessageDurablePersistenceCoordinator messagePersistenceCoordinator;
    private final Set<String> synchronouslyHandledMessageIds = ConcurrentHashMap.newKeySet();

    @Async("sessionEventExecutor")
    @EventListener
    public void onAgentMessage(AgentMessage message) {
        normalizeDurableMessageId(message);
        sanitizePublicPayload(message);
        if (message != null && message.getMessageId() != null
                && synchronouslyHandledMessageIds.remove(message.getMessageId())) {
            return;
        }
        handleMessage(message);
    }

    /**
     * Synchronous entry point for relays that need message persistence to be
     * visible before they advance task status.
     */
    public void handleMessage(AgentMessage message) {
        normalizeDurableMessageId(message);
        sanitizePublicPayload(message);
        handleMessage(message, false);
    }

    /**
     * Persists and emits a message synchronously. Persistence failures are
     * propagated so durable stream consumers do not advance their cursor.
     */
    public void handleMessageDurably(AgentMessage message) {
        normalizeDurableMessageId(message);
        sanitizePublicPayload(message);
        handleMessage(message, true);
        if (message != null && message.getMessageId() != null) {
            synchronouslyHandledMessageIds.add(message.getMessageId());
        }
    }

    private void handleMessage(AgentMessage message, boolean propagatePersistenceFailure) {
        String sessionId = message.getSessionId();
        log.debug("Received AgentMessage: sessionId={}, type={}, agentId={}",
                sessionId, message.getType(), message.getAgentId());

        // 1. Persist user-visible messages. A terminal result may be the only
        // final assistant text produced by newer Codex SDKs, so keep it unless
        // the same answer was already persisted immediately before it.
        if (shouldPersist(message)) {
            try {
                if (shouldPersistResultEvent(message)) {
                    messagePersistenceCoordinator.persist(message);
                }
            } catch (Exception e) {
                log.error("Failed to persist message: sessionId={}, type={}", sessionId, message.getType(), e);
                if (propagatePersistenceFailure) {
                    throw new MessagePersistenceException(sessionId, message.getMessageId(), e);
                }
            }
        }

        // 2. SSE推送（通过 UnifiedSseEmitter 路由到订阅了该 session 的用户）
        log.debug("Sending SSE event: sessionId={}, type={}", sessionId, message.getType());
        sseEmitter.sendSessionEvent(sessionId, message);
    }

    private void sanitizePublicPayload(AgentMessage message) {
        if (message != null) {
            message.setPayload(SessionMessagePublicPayloadSanitizer.redactInternalStorageKeys(message.getPayload()));
        }
    }

    /**
     * Provider replay identifiers may contain a UUID-sized task id plus ESN
     * and event part. Keep short IDs untouched; map longer values to a full
     * SHA-256 hex key so the same replay event has one DB-safe identity.
     */
    private void normalizeDurableMessageId(AgentMessage message) {
        if (message == null || message.getMessageId() == null
                || message.getMessageId().length() <= MAX_DURABLE_MESSAGE_ID_LENGTH) {
            return;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(message.getMessageId().getBytes(StandardCharsets.UTF_8));
            StringBuilder compact = new StringBuilder(MAX_DURABLE_MESSAGE_ID_LENGTH);
            for (byte value : digest) {
                compact.append(Character.forDigit((value >>> 4) & 0x0F, 16));
                compact.append(Character.forDigit(value & 0x0F, 16));
            }
            message.setMessageId(compact.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for durable message IDs", e);
        }
    }

    public static final class MessagePersistenceException extends RuntimeException {
        private MessagePersistenceException(String sessionId, String messageId, Throwable cause) {
            super("Failed to durably persist message " + messageId + " for session " + sessionId, cause);
        }
    }

    private boolean shouldPersist(AgentMessage message) {
        if (message == null || message.getType() == null) {
            return false;
        }
        MessageType type = message.getType();
        return type != MessageType.TEXT_CHUNK
                && type != MessageType.HEARTBEAT
                && type != MessageType.SESSION_START
                && type != MessageType.NATIVE_SUBTASK_UPDATE
                && !isInternalSystemState(message);
    }

    private boolean isInternalSystemState(AgentMessage message) {
        if (message.getType() != MessageType.STATE_SYNC || !(message.getPayload() instanceof Map<?, ?> payload)) {
            return false;
        }
        return "system".equals(payload.get("subtype"));
    }

    /**
     * result 事件（isResult=true）只携带终态和指标（cost/tokens/duration），
     * 其文本内容与前面的 assistant_text 完全一致。Codex 使用 SESSION_END，
     * 其他 relay 可能使用 TEXT_COMPLETE，两者都不得重复持久化。
     * 如果也持久化，就会在 DB 中产生重复消息。
     */
    @SuppressWarnings("unchecked")
    private boolean isResultEvent(AgentMessage message) {
        if (message.getType() != MessageType.TEXT_COMPLETE
                && message.getType() != MessageType.SESSION_END) return false;
        if (message.getPayload() instanceof Map) {
            Map<String, Object> payload = (Map<String, Object>) message.getPayload();
            return Boolean.TRUE.equals(payload.get("isResult"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean shouldPersistResultEvent(AgentMessage message) {
        if (!isResultEvent(message)) return true;
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        String content = payload.get("content") instanceof String value ? value : null;
        if (content == null || content.isBlank()) return false;

        List<Message> recent = sessionManager.getRecentMessages(message.getSessionId(), 50);
        if (recent == null) return true;
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message prior = recent.get(i);
            if (prior.getRole() == MessageRole.USER) break;
            if (prior.getRole() == MessageRole.ASSISTANT
                    && Objects.equals(content, prior.getContent())) {
                return false;
            }
        }
        return true;
    }

}
