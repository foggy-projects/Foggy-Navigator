package com.foggy.navigator.session.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

/**
 * SSE会话事件推送器
 * 参考 coding-agent 的 SseEventEmitter 模式
 */
@Component
@Slf4j
public class SseSessionEmitter {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> sessionEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseSessionEmitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    public SseEmitter createEmitter(String sessionId) {
        log.info("Creating SSE emitter: sessionId={}", sessionId);

        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> {
            log.info("SSE emitter completed: sessionId={}", sessionId);
            removeEmitter(sessionId, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE emitter timeout: sessionId={}", sessionId);
            removeEmitter(sessionId, emitter);
        });

        emitter.onError(throwable -> {
            log.error("SSE emitter error: sessionId={}", sessionId, throwable);
            removeEmitter(sessionId, emitter);
        });

        sessionEmitters.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "connected", "sessionId", sessionId)));
        } catch (IOException e) {
            log.error("Failed to send connection event: sessionId={}", sessionId, e);
        }

        return emitter;
    }

    public void sendEvent(String sessionId, AgentMessage message) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE emitters: sessionId={}", sessionId);
            return;
        }

        log.debug("Sending SSE event: sessionId={}, type={}", sessionId, message.getType());

        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            log.error("SSE event serialization failed: sessionId={}, type={}", sessionId, message.getType(), e);
            return;
        }

        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("event")
                        .data(json));
                return false;
            } catch (IOException e) {
                log.error("Failed to send SSE event (connection issue): sessionId={}", sessionId);
                return true;
            }
        });
    }

    private void sendHeartbeats() {
        try {
            sessionEmitters.forEach((sessionId, emitters) -> {
                int before = emitters.size();

                AgentMessage heartbeat = AgentMessage.of(sessionId, "", MessageType.HEARTBEAT, null);
                String json;
                try {
                    json = objectMapper.writeValueAsString(heartbeat);
                } catch (Exception e) {
                    log.error("Heartbeat serialization failed", e);
                    return;
                }

                emitters.removeIf(emitter -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name("event")
                                .data(json));
                        return false;
                    } catch (Exception e) {
                        log.debug("Heartbeat send failed, removing emitter: sessionId={}", sessionId);
                        return true;
                    }
                });
                int after = emitters.size();
                if (before > 0 && after == 0) {
                    log.info("All emitters invalid, removing: sessionId={}", sessionId);
                }
                if (emitters.isEmpty()) {
                    sessionEmitters.remove(sessionId);
                }
            });
        } catch (Exception e) {
            log.error("Heartbeat scheduling exception", e);
        }
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                sessionEmitters.remove(sessionId);
            }
        }
    }

    public int getEmitterCount(String sessionId) {
        CopyOnWriteArrayList<SseEmitter> emitters = sessionEmitters.get(sessionId);
        return emitters != null ? emitters.size() : 0;
    }
}
