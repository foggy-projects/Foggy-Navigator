package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.foundation.git.OpenHandsClient;
import com.foggy.navigator.foundation.git.OpenHandsClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
    private EventService eventService;

    public void triggerValidation(String conversationId) {
        log.info("触发验证: conversationId={}", conversationId);

        try {
            OpenHandsClient client = getClientForConversation(conversationId);
            client.post("/app-conversations/" + conversationId + "/validate", Map.of(), Void.class);

            Event event = Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.VALIDATION_TRIGGERED)
                    .data(Map.of("timestamp", System.currentTimeMillis()))
                    .build();

            eventService.saveEvent(event);

            log.info("验证已触发: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("触发验证失败: conversationId={}", conversationId, e);

            Event errorEvent = Event.builder()
                    .conversationId(conversationId)
                    .kind(Event.EventKind.ERROR)
                    .data(Map.of("error", e.getMessage()))
                    .build();

            eventService.saveEvent(errorEvent);

            throw new RuntimeException("触发验证失败", e);
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
