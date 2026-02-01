package com.foggy.navigator.coding.agent.api.listener;

import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.service.EventService;
import com.foggy.navigator.coding.agent.api.sse.SseEventEmitter;
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

    @Async("eventPublisherExecutor")
    @EventListener
    public void onEvent(Event event) {
        long startTime = System.currentTimeMillis();
        log.debug("收到事件: conversationId={}, kind={}", event.getConversationId(), event.getKind());

        try {
            // 保存事件到数据库
            eventService.saveEvent(event);

            // 推送事件到 SSE 流
            sseEventEmitter.sendEvent(event.getConversationId(), event);

            long duration = System.currentTimeMillis() - startTime;
            if (duration > 100) {
                log.warn("事件处理耗时较长: conversationId={}, kind={}, duration={}ms",
                        event.getConversationId(), event.getKind(), duration);
            }
        } catch (Exception e) {
            log.error("处理事件失败: conversationId={}, kind={}, error={}",
                    event.getConversationId(), event.getKind(), e.getMessage(), e);
        }
    }
}
