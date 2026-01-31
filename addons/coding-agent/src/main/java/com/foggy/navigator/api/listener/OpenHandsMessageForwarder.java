package com.foggy.navigator.api.listener;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.Conversation.ConversationStatus;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.api.service.ConversationService;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
@Slf4j
public class OpenHandsMessageForwarder {

    @Autowired
    private OpenHandsClientFactory clientFactory;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Async("eventPublisherExecutor")
    @EventListener(condition = "#event.kind != null && #event.kind.name() == 'MESSAGE_SENT'")
    public void onMessageSent(Event event) {
        String conversationId = event.getConversationId();
        log.info("收到 MESSAGE_SENT 事件，准备转发到 OpenHands: conversationId={}", conversationId);

        try {
            Conversation conv = conversationService.getConversation(conversationId);
            if (conv == null) {
                log.warn("会话不存在，跳过转发: conversationId={}", conversationId);
                return;
            }

            if (conv.getOhConversationId() == null) {
                log.warn("会话缺少 ohConversationId，跳过转发: conversationId={}", conversationId);
                return;
            }

            if (conv.getStatus() != ConversationStatus.READY
                    && conv.getStatus() != ConversationStatus.RUNNING
                    && conv.getStatus() != ConversationStatus.IDLE) {
                log.warn("会话状态不允许转发: conversationId={}, status={}", conversationId, conv.getStatus());
                return;
            }

            String content = extractContent(event);
            if (content == null || content.isBlank()) {
                log.warn("消息内容为空，跳过转发: conversationId={}", conversationId);
                return;
            }

            OpenHandsClient client = clientFactory.getClientForUser(conv.getUserId());
            log.info("转发消息到 OpenHands: conversationId={}, ohConversationId={}", conversationId, conv.getOhConversationId());
            client.sendMessage(conv.getOhConversationId(), content);

            // Update status to RUNNING
            conv.setStatus(ConversationStatus.RUNNING);
            conv.setUpdatedAt(LocalDateTime.now());

            // Publish status change event
            eventPublisher.publishEvent(Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.CONVERSATION_STATUS)
                    .data(Map.of("status", "RUNNING"))
                    .build());

            // Publish internal event to start polling for OH responses
            eventPublisher.publishEvent(new OpenHandsPollingStartEvent(
                    conversationId, conv.getUserId(), conv.getOhConversationId()));

            log.info("消息已转发到 OpenHands，开始轮询事件: conversationId={}", conversationId);

        } catch (Exception e) {
            log.error("转发消息到 OpenHands 失败: conversationId={}, error={}", conversationId, e.getMessage(), e);

            eventPublisher.publishEvent(Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.ERROR)
                    .data(Map.of("error", "Failed to forward message to OpenHands: " + e.getMessage()))
                    .build());
        }
    }

    private String extractContent(Event event) {
        if (event.getData() == null) {
            return null;
        }
        Object content = event.getData().get("content");
        return content != null ? content.toString() : null;
    }
}
