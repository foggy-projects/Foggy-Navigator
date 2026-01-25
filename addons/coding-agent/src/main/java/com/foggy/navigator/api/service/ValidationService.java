package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.Message;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ValidationService {

    @Autowired
    private OpenHandsClientFactory openHandsClientFactory;

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private EventService eventService;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${foggy.coding-agent.validation.enabled:true}")
    private boolean validationEnabled;

    @Value("${foggy.coding-agent.validation.timeout:30000}")
    private long validationTimeout;

    @Async("validationExecutor")
    @Transactional
    public void triggerValidation(String conversationId) {
        if (!validationEnabled) {
            log.debug("验证服务已禁用，跳过验证: conversationId={}", conversationId);
            return;
        }

        log.info("触发验证: conversationId={}", conversationId);

        try {
            // 检查 Conversation 是否存在
            Conversation conversation = conversationService.getConversation(conversationId);
            if (conversation == null) {
                log.warn("对话不存在，跳过验证: conversationId={}", conversationId);
                publishErrorEvent(conversationId, "对话不存在");
                return;
            }

            // 检查容器是否就绪
            if (conversation.getStatus() != Conversation.ConversationStatus.READY) {
                log.warn("对话状态不是 READY，跳过验证: conversationId={}, status={}",
                        conversationId, conversation.getStatus());
                return;
            }

            // 获取最新消息作为验证上下文
            List<Message> messages = messageService.getMessages(conversationId, 10);
            Map<String, Object> context = Map.of(
                    "messageCount", messages.size(),
                    "timestamp", System.currentTimeMillis()
            );

            OpenHandsClient client = getClientForConversation(conversationId);
            client.post("/app-conversations/" + conversationId + "/validate", context, Void.class);

            // 使用 ApplicationEventPublisher 发布事件
            Event event = Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.VALIDATION_TRIGGERED)
                    .data(context)
                    .build();
            eventPublisher.publishEvent(event);

            log.info("验证已触发: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("触发验证失败: conversationId={}, error={}", conversationId, e.getMessage(), e);
            publishErrorEvent(conversationId, "触发验证失败: " + e.getMessage());
        }
    }

    public Event getValidationStatus(String conversationId) {
        log.debug("获取验证状态: conversationId={}", conversationId);

        try {
            // 查询最新的 VALIDATION_RESULT 事件
            List<Event> events = eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_RESULT);
            if (events != null && !events.isEmpty()) {
                return events.get(0); // 返回最新的验证结果
            }

            // 如果没有 VALIDATION_RESULT，检查是否有 VALIDATION_TRIGGERED
            events = eventService.getEventsByKind(conversationId, Event.EventKind.VALIDATION_TRIGGERED);
            if (events != null && !events.isEmpty()) {
                return Event.builder()
                        .conversationId(conversationId)
                        .kind(Event.EventKind.VALIDATION_TRIGGERED)
                        .data(Map.of("status", "in_progress"))
                        .build();
            }

            // 没有任何验证事件
            return Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.VALIDATION_RESULT)
                    .data(Map.of("status", "not_started"))
                    .build();
        } catch (Exception e) {
            log.error("获取验证状态失败: conversationId={}", conversationId, e);
            return Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.ERROR)
                    .data(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    public List<com.foggy.navigator.foundation.git.model.OpenHandsEvent> getValidationResults(String conversationId) {
        log.info("获取验证结果: conversationId={}", conversationId);

        try {
            OpenHandsClient client = getClientForConversation(conversationId);
            List<com.foggy.navigator.foundation.git.model.OpenHandsEvent> events = client.searchEvents(
                    conversationId,
                    "VALIDATION_RESULT",
                    null,
                    null,
                    null,
                    100
            );

            log.info("获取到 {} 条验证结果: conversationId={}", events.size(), conversationId);
            return events;
        } catch (Exception e) {
            log.error("获取验证结果失败: conversationId={}", conversationId, e);
            throw new RuntimeException("获取验证结果失败", e);
        }
    }

    private void publishErrorEvent(String conversationId, String errorMessage) {
        Event errorEvent = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.ERROR)
                .data(Map.of("error", errorMessage, "source", "ValidationService"))
                .build();
        eventPublisher.publishEvent(errorEvent);
    }

    private OpenHandsClient getClientForConversation(String conversationId) {
        Conversation conversation = conversationService.getConversation(conversationId);
        if (conversation == null) {
            throw new RuntimeException("对话不存在: " + conversationId);
        }
        String sandboxId = conversation.getSandboxId();
        if (sandboxId == null) {
            throw new RuntimeException("对话沙箱未就绪: " + conversationId);
        }
        return openHandsClientFactory.getClient(sandboxId);
    }
}
