package com.foggy.navigator.api.listener;

import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.sse.SseEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OpenhandsEventListener {

    @Autowired
    private SseEventEmitter sseEventEmitter;

    @Async
    @EventListener
    public void onEvent(Event event) {
        log.debug("收到事件: conversationId={}, kind={}", event.getConversationId(), event.getKind());

        try {
            sseEventEmitter.sendEvent(event.getConversationId(), event);
        } catch (Exception e) {
            log.error("推送事件失败: conversationId={}", event.getConversationId(), e);
        }
    }
}
