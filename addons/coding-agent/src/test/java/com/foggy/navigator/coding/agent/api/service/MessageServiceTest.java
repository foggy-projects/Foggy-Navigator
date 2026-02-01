package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.Message;
import com.foggy.navigator.coding.agent.api.model.SendMessageRequest;
import com.foggy.navigator.coding.agent.api.model.entity.MessageEntity;
import com.foggy.navigator.coding.agent.api.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ValidationService validationService;

    @InjectMocks
    private MessageService messageService;

    @Test
    void testSendMessage_Success() {
        String conversationId = "conv-123";
        SendMessageRequest request = SendMessageRequest.builder()
                .content("Hello, how are you?")
                .build();

        Message message = messageService.sendMessage(conversationId, request);

        assertNotNull(message);
        assertEquals(conversationId, message.getConversationId());
        assertEquals("Hello, how are you?", message.getContent());
        assertNotNull(message.getMessageId());
        assertNotNull(message.getTimestamp());

        verify(messageRepository).save(any(MessageEntity.class));
        verify(eventPublisher).publishEvent(any(Event.class));
    }

    @Test
    void testSendMessage_MultipleMessages() {
        String conversationId = "conv-123";

        SendMessageRequest request1 = SendMessageRequest.builder()
                .content("First message")
                .build();

        SendMessageRequest request2 = SendMessageRequest.builder()
                .content("Second message")
                .build();

        Message message1 = messageService.sendMessage(conversationId, request1);
        Message message2 = messageService.sendMessage(conversationId, request2);

        assertNotNull(message1);
        assertNotNull(message2);
        assertNotEquals(message1.getMessageId(), message2.getMessageId());
        assertEquals("First message", message1.getContent());
        assertEquals("Second message", message2.getContent());

        verify(messageRepository, times(2)).save(any(MessageEntity.class));
        verify(eventPublisher, times(2)).publishEvent(any(Event.class));
    }

    @Test
    void testGetMessages_Empty() {
        String conversationId = "conv-123";

        List<Message> messages = messageService.getMessages(conversationId, 10);

        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    void testGetMessages_WithMessages() throws InterruptedException {
        String conversationId = "conv-123";

        SendMessageRequest request1 = SendMessageRequest.builder()
                .content("First message")
                .build();

        SendMessageRequest request2 = SendMessageRequest.builder()
                .content("Second message")
                .build();

        messageService.sendMessage(conversationId, request1);
        // 添加短暂延迟确保时间戳不同
        Thread.sleep(5);
        messageService.sendMessage(conversationId, request2);

        List<Message> messages = messageService.getMessages(conversationId, 10);

        assertNotNull(messages);
        assertEquals(2, messages.size());
        assertEquals("Second message", messages.get(0).getContent());
        assertEquals("First message", messages.get(1).getContent());
    }

    @Test
    void testGetMessages_WithLimit() throws InterruptedException {
        String conversationId = "conv-limit-test";

        for (int i = 1; i <= 5; i++) {
            SendMessageRequest request = SendMessageRequest.builder()
                    .content("Message " + i)
                    .build();
            messageService.sendMessage(conversationId, request);
            // 添加短暂延迟确保时间戳不同
            Thread.sleep(5);
        }

        List<Message> messages = messageService.getMessages(conversationId, 3);

        assertNotNull(messages);
        assertEquals(3, messages.size());
        assertEquals("Message 5", messages.get(0).getContent());
        assertEquals("Message 4", messages.get(1).getContent());
        assertEquals("Message 3", messages.get(2).getContent());
    }

    @Test
    void testGetMessages_FromDatabase() {
        String conversationId = "conv-123";

        MessageEntity entity1 = MessageEntity.builder()
                .id(1L)
                .messageId("msg-1")
                .conversationId(conversationId)
                .content("Database message 1")
                .timestamp(LocalDateTime.now().minusMinutes(2))
                .build();

        MessageEntity entity2 = MessageEntity.builder()
                .id(2L)
                .messageId("msg-2")
                .conversationId(conversationId)
                .content("Database message 2")
                .timestamp(LocalDateTime.now().minusMinutes(1))
                .build();

        when(messageRepository.findTopNByConversationIdOrderByTimestampDesc(conversationId))
                .thenReturn(List.of(entity2, entity1));

        List<Message> messages = messageService.getMessages(conversationId, 10);

        assertNotNull(messages);
        assertEquals(2, messages.size());
        assertEquals("Database message 2", messages.get(0).getContent());
        assertEquals("Database message 1", messages.get(1).getContent());
    }

    @Test
    void testSendMessage_PublishesEvent() {
        String conversationId = "conv-123";
        SendMessageRequest request = SendMessageRequest.builder()
                .content("Test message")
                .build();

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        messageService.sendMessage(conversationId, request);

        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        assertEquals(conversationId, publishedEvent.getConversationId());
        assertEquals(Event.EventKind.MESSAGE_SENT, publishedEvent.getKind());
        assertNotNull(publishedEvent.getData());
        assertTrue(publishedEvent.getData().containsKey("messageId"));
        assertTrue(publishedEvent.getData().containsKey("content"));
        assertEquals("Test message", publishedEvent.getData().get("content"));
    }

    @Test
    void testSaveMessageToDatabase() {
        String conversationId = "conv-123";
        SendMessageRequest request = SendMessageRequest.builder()
                .content("Test message")
                .build();

        Message message = messageService.sendMessage(conversationId, request);

        ArgumentCaptor<MessageEntity> entityCaptor = ArgumentCaptor.forClass(MessageEntity.class);

        verify(messageRepository).save(entityCaptor.capture());

        MessageEntity savedEntity = entityCaptor.getValue();
        assertEquals(message.getMessageId(), savedEntity.getMessageId());
        assertEquals(conversationId, savedEntity.getConversationId());
        assertEquals("Test message", savedEntity.getContent());
        assertEquals(message.getTimestamp(), savedEntity.getTimestamp());
    }

    @Test
    void testSendMessage_WithValidationTrigger() {
        String conversationId = "conv-123";
        SendMessageRequest request = SendMessageRequest.builder()
                .content("Test message with validation")
                .build();

        Message message = messageService.sendMessage(conversationId, request);

        assertNotNull(message);
        assertEquals(conversationId, message.getConversationId());
        assertEquals("Test message with validation", message.getContent());

        // 验证事件发布了
        verify(eventPublisher).publishEvent(any(Event.class));

        // 验证没有自动触发验证（因为默认禁用）
        verify(validationService, never()).triggerValidation(anyString());
    }

    @Test
    void testSendMessage_EventPublishContainsContent() {
        String conversationId = "conv-123";
        String messageContent = "Test message content";
        SendMessageRequest request = SendMessageRequest.builder()
                .content(messageContent)
                .build();

        ArgumentCaptor<Event> eventCaptor = ArgumentCaptor.forClass(Event.class);

        messageService.sendMessage(conversationId, request);

        verify(eventPublisher).publishEvent(eventCaptor.capture());

        Event publishedEvent = eventCaptor.getValue();
        assertEquals(conversationId, publishedEvent.getConversationId());
        assertEquals(Event.EventKind.MESSAGE_SENT, publishedEvent.getKind());
        assertEquals(messageContent, publishedEvent.getData().get("content"));
    }
}
