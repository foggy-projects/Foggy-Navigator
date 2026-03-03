package com.foggy.navigator.session.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.metrics.NavigatorMetrics;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Unified SSE Emitter — 每个用户仅维护一条 SSE 连接
 * 替代 SseSessionEmitter (per-session) + UserSseEmitter (per-user) 的双连接模式
 *
 * 事件类型:
 * - session_event: 会话级 AgentMessage（前端按 sessionId 路由）
 * - assistant_notification: 助手通知（A2aMessage）
 * - task_update: 任务状态更新
 * - heartbeat: 连接保活
 */
@Component
@Slf4j
public class UnifiedSseEmitter {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionToUsers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "unified-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public UnifiedSseEmitter(ObjectMapper objectMapper, @Nullable MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
        if (meterRegistry != null) {
            meterRegistry.gauge(NavigatorMetrics.SSE_ACTIVE_CONNECTIONS, this,
                    UnifiedSseEmitter::getTotalEmitterCount);
        }
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    /**
     * 创建 SSE emitter（每个浏览器 Tab 调用一次）
     */
    public SseEmitter createEmitter(String userId) {
        log.info("Creating unified SSE emitter: userId={}", userId);

        SseEmitter emitter = new SseEmitter(24 * 60 * 60 * 1000L);

        emitter.onCompletion(() -> {
            log.info("Unified SSE emitter completed: userId={}", userId);
            removeEmitter(userId, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("Unified SSE emitter timeout: userId={}", userId);
            removeEmitter(userId, emitter);
        });

        emitter.onError(throwable -> {
            log.error("Unified SSE emitter error: userId={}", userId, throwable);
            removeEmitter(userId, emitter);
        });

        userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "connected", "userId", userId)));
        } catch (IOException e) {
            log.error("Failed to send connection event: userId={}", userId, e);
        }

        return emitter;
    }

    /**
     * 订阅 session 事件
     */
    public void subscribe(String userId, String sessionId) {
        userSubscriptions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionToUsers.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(userId);
        log.debug("Subscribed: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 取消订阅 session 事件
     */
    public void unsubscribe(String userId, String sessionId) {
        Set<String> subs = userSubscriptions.get(userId);
        if (subs != null) {
            subs.remove(sessionId);
            if (subs.isEmpty()) userSubscriptions.remove(userId);
        }

        Set<String> users = sessionToUsers.get(sessionId);
        if (users != null) {
            users.remove(userId);
            if (users.isEmpty()) sessionToUsers.remove(sessionId);
        }
        log.debug("Unsubscribed: userId={}, sessionId={}", userId, sessionId);
    }

    /**
     * 获取用户当前订阅的 sessionId 列表
     */
    public Set<String> getSubscriptions(String userId) {
        Set<String> subs = userSubscriptions.get(userId);
        return subs != null ? Set.copyOf(subs) : Set.of();
    }

    /**
     * 推送会话事件 — 通过 sessionToUsers 反向索引找到订阅用户
     */
    public void sendSessionEvent(String sessionId, AgentMessage message) {
        Set<String> users = sessionToUsers.get(sessionId);
        if (users == null || users.isEmpty()) {
            log.debug("No subscribers for session: sessionId={}", sessionId);
            return;
        }

        // Wrap the message with sessionId for frontend routing
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Session event serialization failed: sessionId={}, type={}", sessionId, message.getType(), e);
            return;
        }

        for (String userId : users) {
            sendEventToUser(userId, "session_event", json);
        }
    }

    /**
     * 推送助手通知
     */
    public void sendNotification(String userId, A2aMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("Notification serialization failed: userId={}", userId, e);
            return;
        }
        sendEventToUser(userId, "assistant_notification", json);
    }

    /**
     * 推送任务状态更新
     */
    public void sendTaskUpdate(String userId, Map<String, Object> update) {
        String json;
        try {
            json = objectMapper.writeValueAsString(update);
        } catch (Exception e) {
            log.error("Task update serialization failed: userId={}", userId, e);
            return;
        }
        sendEventToUser(userId, "task_update", json);
    }

    /**
     * 是否有活跃连接
     */
    public boolean hasActiveEmitters(String userId) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        return emitters != null && !emitters.isEmpty();
    }

    public int getTotalEmitterCount() {
        return userEmitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }

    // ---- internal ----

    private void sendEventToUser(String userId, String eventName, String json) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active emitters for user: userId={}", userId);
            return;
        }

        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(json));
                return false;
            } catch (IOException e) {
                log.error("Failed to send SSE event: userId={}, event={}", userId, eventName);
                return true;
            }
        });
    }

    private void sendHeartbeats() {
        try {
            userEmitters.forEach((userId, emitters) -> {
                emitters.removeIf(emitter -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("heartbeat")
                                .data("{}"));
                        return false;
                    } catch (Exception e) {
                        log.debug("Heartbeat failed, removing emitter: userId={}", userId);
                        return true;
                    }
                });
                if (emitters.isEmpty()) {
                    userEmitters.remove(userId);
                    cleanupSubscriptions(userId);
                }
            });
        } catch (Exception e) {
            log.error("Heartbeat scheduling exception", e);
        }
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
                cleanupSubscriptions(userId);
            }
        }
    }

    /**
     * 用户所有 emitter 断开后，清理其订阅关系
     */
    private void cleanupSubscriptions(String userId) {
        Set<String> subs = userSubscriptions.remove(userId);
        if (subs != null) {
            for (String sessionId : subs) {
                Set<String> users = sessionToUsers.get(sessionId);
                if (users != null) {
                    users.remove(userId);
                    if (users.isEmpty()) {
                        sessionToUsers.remove(sessionId);
                    }
                }
            }
        }
        log.debug("Cleaned up subscriptions for disconnected user: userId={}", userId);
    }
}
