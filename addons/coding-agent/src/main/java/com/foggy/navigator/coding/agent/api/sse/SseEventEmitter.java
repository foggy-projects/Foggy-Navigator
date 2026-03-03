package com.foggy.navigator.coding.agent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.coding.agent.api.model.Event;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

@Component
@Slf4j
public class SseEventEmitter {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> conversationEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SseEventEmitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Send heartbeat comment every 15 seconds to keep SSE connections alive
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeats, 15, 15, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }

    public SseEmitter createEmitter(String conversationId) {
        log.info("创建 SSE emitter: conversationId={}", conversationId);

        // 24-hour timeout instead of infinite — prevents zombie connections from accumulating
        SseEmitter emitter = new SseEmitter(24 * 60 * 60 * 1000L);

        emitter.onCompletion(() -> {
            log.info("SSE emitter 完成: conversationId={}", conversationId);
            removeEmitter(conversationId, emitter);
        });

        emitter.onTimeout(() -> {
            log.warn("SSE emitter 超时: conversationId={}", conversationId);
            removeEmitter(conversationId, emitter);
        });

        emitter.onError(throwable -> {
            log.error("SSE emitter 错误: conversationId={}", conversationId, throwable);
            removeEmitter(conversationId, emitter);
        });

        conversationEmitters.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        try {
            emitter.send(SseEmitter.event().data(Map.of("type", "connected", "conversationId", conversationId)));
        } catch (IOException e) {
            log.error("发送连接事件失败: conversationId={}", conversationId, e);
        }

        return emitter;
    }

    public void sendEvent(String conversationId, Event event) {
        CopyOnWriteArrayList<SseEmitter> emitters = conversationEmitters.get(conversationId);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("没有活跃的 SSE emitter: conversationId={}", conversationId);
            return;
        }

        log.debug("发送 SSE 事件: conversationId={}, kind={}", conversationId, event.getKind());

        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.error("SSE 事件序列化失败: conversationId={}, kind={}", conversationId, event.getKind(), e);
            return;
        }

        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("event")
                        .data(json));
                return false;
            } catch (IOException e) {
                log.error("发送 SSE 事件失败（连接问题）: conversationId={}", conversationId);
                return true;
            }
        });
    }

    private void sendHeartbeats() {
        try {
            conversationEmitters.forEach((conversationId, emitters) -> {
                int before = emitters.size();
                emitters.removeIf(emitter -> {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                        return false;
                    } catch (Exception e) {
                        log.debug("心跳发送失败，移除 emitter: conversationId={}", conversationId);
                        return true;
                    }
                });
                int after = emitters.size();
                if (after > 0) {
                    log.debug("心跳发送成功: conversationId={}, emitters={}", conversationId, after);
                }
                if (before > 0 && after == 0) {
                    log.info("所有 emitter 已失效，移除: conversationId={}", conversationId);
                }
                if (emitters.isEmpty()) {
                    conversationEmitters.remove(conversationId);
                }
            });
        } catch (Exception e) {
            log.error("心跳调度异常（不影响后续心跳）", e);
        }
    }

    private void removeEmitter(String conversationId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = conversationEmitters.get(conversationId);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                conversationEmitters.remove(conversationId);
            }
        }
    }

    public int getEmitterCount(String conversationId) {
        CopyOnWriteArrayList<SseEmitter> emitters = conversationEmitters.get(conversationId);
        return emitters != null ? emitters.size() : 0;
    }
}
