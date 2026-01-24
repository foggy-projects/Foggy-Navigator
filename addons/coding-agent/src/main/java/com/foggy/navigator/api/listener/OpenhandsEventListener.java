package com.foggy.navigator.api.listener;

import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.service.EventService;
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

    @Autowired
    private EventService eventService;

    @Async
    @EventListener
    public void onEvent(Event event) {
        log.debug("收到事件: conversationId={}, kind={}", event.getConversationId(), event.getKind());

        try {
            // 保存事件到数据库
            eventService.saveEvent(event);

            // 推送事件到 SSE 流
            sseEventEmitter.sendEvent(event.getConversationId(), event);
        } catch (Exception e) {
            log.error("处理事件失败: conversationId={}, kind={}", event.getConversationId(), event.getKind(), e);
        }
    }
}
