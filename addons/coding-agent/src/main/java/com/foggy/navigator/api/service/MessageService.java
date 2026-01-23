package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.Message;
import com.foggy.navigator.api.model.SendMessageRequest;
import com.foggy.navigator.api.model.entity.MessageEntity;
import com.foggy.navigator.api.repository.MessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class MessageService {

    private final Map<String, List<Message>> conversationMessages = new ConcurrentHashMap<>();

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private MessageRepository messageRepository;

    @Transactional
    public Message sendMessage(String conversationId, SendMessageRequest request) {
        log.info("发送消息: conversationId={}, content={}", conversationId, request.getContent());

        String messageId = UUID.randomUUID().toString();

        Message message = Message.builder()
                .messageId(messageId)
                .conversationId(conversationId)
                .content(request.getContent())
                .timestamp(LocalDateTime.now())
                .build();

        conversationMessages.computeIfAbsent(conversationId, k -> new ArrayList<>()).add(message);

        saveMessageToDatabase(message);

        Event messageEvent = Event.builder()
                .conversationId(conversationId)
                .kind(Event.EventKind.MESSAGE_SENT)
                .data(Map.of("messageId", messageId, "content", request.getContent()))
                .build();
        eventPublisher.publishEvent(messageEvent);

        log.info("消息已发送: messageId={}", messageId);
        return message;
    }

    public List<Message> getMessages(String conversationId, int limit) {
        List<Message> messages = conversationMessages.get(conversationId);
        if (messages == null) {
            messages = loadMessagesFromDatabase(conversationId, limit);
            if (messages != null) {
                conversationMessages.put(conversationId, messages);
            }
        }

        return messages.stream()
                .sorted((a, b) -> {
                    int timeCompare = b.getTimestamp().compareTo(a.getTimestamp());
                    if (timeCompare != 0) {
                        return timeCompare;
                    }
                    // 如果时间戳相同，使用 messageId 作为次要排序键，确保排序稳定
                    return b.getMessageId().compareTo(a.getMessageId());
                })
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    protected void saveMessageToDatabase(Message message) {
        try {
            MessageEntity entity = MessageEntity.builder()
                    .messageId(message.getMessageId())
                    .conversationId(message.getConversationId())
                    .content(message.getContent())
                    .timestamp(message.getTimestamp())
                    .build();
            messageRepository.save(entity);
        } catch (Exception e) {
            log.error("保存消息到数据库失败: messageId={}", message.getMessageId(), e);
        }
    }

    protected List<Message> loadMessagesFromDatabase(String conversationId, int limit) {
        try {
            return messageRepository.findTopNByConversationIdOrderByTimestampDesc(conversationId)
                    .stream()
                    .limit(limit)
                    .map(entity -> Message.builder()
                            .messageId(entity.getMessageId())
                            .conversationId(entity.getConversationId())
                            .content(entity.getContent())
                            .timestamp(entity.getTimestamp())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("从数据库加载消息失败: conversationId={}", conversationId, e);
            return null;
        }
    }
}
