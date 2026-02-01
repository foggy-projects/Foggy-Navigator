package com.foggy.navigator.coding.agent.api.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.coding.agent.api.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Slf4j
public class SseEventEmitter {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> conversationEmitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SseEmitter createEmitter(String conversationId) {
        log.info("创建 SSE emitter: conversationId={}", conversationId);

        SseEmitter emitter = new SseEmitter(300000L);

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

        emitters.removeIf(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("event")
                        .data(objectMapper.writeValueAsString(event)));
                return false;
            } catch (IOException e) {
                log.error("发送 SSE 事件失败: conversationId={}", conversationId, e);
                return true;
            }
        });
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
