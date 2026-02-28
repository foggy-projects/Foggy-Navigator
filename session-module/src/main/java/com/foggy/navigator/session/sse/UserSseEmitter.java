package com.foggy.navigator.session.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 用户级 SSE 推送器（与会话级 SseSessionEmitter 解耦）
 * 用于推送全局通知（任务助手通知、任务状态更新等）
 */
@Component
@Slf4j
public class UserSseEmitter {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "user-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public UserSseEmitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    public SseEmitter createEmitter(String userId) {
        log.info("Creating user SSE emitter: userId={}", userId);

        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> {
            log.info("User SSE emitter completed: userId={}", userId);
            removeEmitter(userId, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("User SSE emitter timeout: userId={}", userId);
            removeEmitter(userId, emitter);
        });

        emitter.onError(throwable -> {
            log.error("User SSE emitter error: userId={}", userId, throwable);
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
     * 推送助手通知
     */
    public void sendNotification(String userId, A2aMessage message) {
        sendEventToUser(userId, "assistant_notification", message);
    }

    /**
     * 推送任务状态更新（驱动进行中面板实时刷新）
     */
    public void sendTaskUpdate(String userId, Map<String, Object> update) {
        sendEventToUser(userId, "task_update", update);
    }

    private void sendEventToUser(String userId, String eventName, Object data) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active user SSE emitters: userId={}", userId);
            return;
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.error("User SSE event serialization failed: userId={}, event={}", userId, eventName, e);
            return;
        }

        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(json));
                return false;
            } catch (IOException e) {
                log.error("Failed to send user SSE event: userId={}, event={}", userId, eventName);
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
                        log.debug("User SSE heartbeat failed, removing emitter: userId={}", userId);
                        return true;
                    }
                });
                if (emitters.isEmpty()) {
                    userEmitters.remove(userId);
                }
            });
        } catch (Exception e) {
            log.error("User SSE heartbeat scheduling exception", e);
        }
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                userEmitters.remove(userId);
            }
        }
    }

    public boolean hasActiveEmitters(String userId) {
        CopyOnWriteArrayList<SseEmitter> emitters = userEmitters.get(userId);
        return emitters != null && !emitters.isEmpty();
    }
}
