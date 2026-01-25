package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.CreateConversationRequest;
import com.foggy.navigator.api.model.Event;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import com.foggy.navigator.foundation.git.model.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private OpenHandsContainerManager containerManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ValidationService validationService;

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        // 设置 autoTriggerValidationOnCreate 为 true
        ReflectionTestUtils.setField(conversationService, "autoTriggerValidationOnCreate", true);
    }

    @Test
    void testCreateConversation_Success() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(eq(mockContainerId), anyInt()))
                .thenReturn(true);
        when(conversationRepository.findByConversationId(anyString()))
                .thenReturn(Optional.of(ConversationEntity.builder()
                        .conversationId("test-id")
                        .userId("user-123")
                        .projectId("project-A")
                        .sandboxId(mockContainerId)
                        .status(ConversationEntity.ConversationStatus.STARTING)
                        .build()));

        Conversation conversation = conversationService.createConversation(request);

        assertNotNull(conversation);
        assertEquals("user-123", conversation.getUserId());
        assertEquals("project-A", conversation.getProjectId());
        assertEquals("main", conversation.getBranchName());
        assertEquals(mockContainerId, conversation.getSandboxId());
        assertEquals(Conversation.ConversationStatus.READY, conversation.getStatus());
        assertNotNull(conversation.getNamespace());
        assertTrue(conversation.getNamespace().startsWith("user-user-123-"));

        verify(containerManager).createContainer(anyString(), anyString(), any(ContainerConfig.class));
        verify(containerManager).waitForContainerReady(eq(mockContainerId), anyInt());
        verify(eventPublisher, times(2)).publishEvent(any(Event.class));
        verify(conversationRepository, times(2)).save(any(ConversationEntity.class));
    }

    @Test
    void testCreateConversation_ContainerTimeout() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(eq(mockContainerId), anyInt()))
                .thenReturn(false);
        when(conversationRepository.findByConversationId(anyString()))
                .thenReturn(Optional.of(ConversationEntity.builder()
                        .conversationId("test-id")
                        .userId("user-123")
                        .projectId("project-A")
                        .sandboxId(mockContainerId)
                        .status(ConversationEntity.ConversationStatus.STARTING)
                        .build()));

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            conversationService.createConversation(request);
        });

        assertTrue(exception.getMessage().contains("创建对话失败"));
        assertTrue(exception.getCause().getMessage().contains("容器启动超时"));
        verify(containerManager).createContainer(anyString(), anyString(), any(ContainerConfig.class));
        verify(containerManager).waitForContainerReady(eq(mockContainerId), anyInt());
        verify(conversationRepository, times(2)).save(any(ConversationEntity.class));
    }

    @Test
    void testGetConversation_Success() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Conversation created = conversationService.createConversation(request);

        Conversation retrieved = conversationService.getConversation(created.getConversationId());

        assertNotNull(retrieved);
        assertEquals(created.getConversationId(), retrieved.getConversationId());
        assertEquals(created.getUserId(), retrieved.getUserId());
    }

    @Test
    void testGetConversation_NotFound() {
        Conversation conversation = conversationService.getConversation("non-existent-id");
        assertNull(conversation);
    }

    @Test
    void testGetConversation_FromDatabase() {
        String conversationId = "conv-123";
        ConversationEntity entity = ConversationEntity.builder()
                .conversationId(conversationId)
                .sandboxId("container-xyz")
                .userId("user-123")
                .projectId("project-A")
                .status(ConversationEntity.ConversationStatus.READY)
                .namespace("user-user-123-session-123")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findByConversationId(conversationId)).thenReturn(Optional.of(entity));

        Conversation conversation = conversationService.getConversation(conversationId);

        assertNotNull(conversation);
        assertEquals(conversationId, conversation.getConversationId());
        assertEquals("user-123", conversation.getUserId());
        assertEquals(Conversation.ConversationStatus.READY, conversation.getStatus());
    }

    @Test
    void testListConversations() {
        CreateConversationRequest request1 = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        CreateConversationRequest request2 = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-B")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn("container-xyz");
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        conversationService.createConversation(request1);
        conversationService.createConversation(request2);

        List<Conversation> conversations = conversationService.listConversations("user-123", null, null, 0, 10);

        assertEquals(2, conversations.size());
        assertEquals("user-123", conversations.get(0).getUserId());
        assertEquals("user-123", conversations.get(1).getUserId());
    }

    @Test
    void testListConversations_WithFilters() {
        CreateConversationRequest request1 = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        CreateConversationRequest request2 = CreateConversationRequest.builder()
                .userId("user-456")
                .projectId("project-B")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn("container-xyz");
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        conversationService.createConversation(request1);
        conversationService.createConversation(request2);

        List<Conversation> conversations = conversationService.listConversations("user-123", "project-A", null, 0, 10);

        assertEquals(1, conversations.size());
        assertEquals("user-123", conversations.get(0).getUserId());
        assertEquals("project-A", conversations.get(0).getProjectId());
    }

    @Test
    void testDeleteConversation() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Conversation created = conversationService.createConversation(request);

        conversationService.deleteConversation(created.getConversationId());

        verify(containerManager).destroyContainer(eq(mockContainerId));
        verify(conversationRepository).deleteByConversationId(eq(created.getConversationId()));
        assertNull(conversationService.getConversation(created.getConversationId()));
    }

    @Test
    void testStopConversation() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Conversation created = conversationService.createConversation(request);

        conversationService.stopConversation(created.getConversationId());

        verify(containerManager).destroyContainer(eq(mockContainerId));

        Conversation stopped = conversationService.getConversation(created.getConversationId());
        assertEquals(Conversation.ConversationStatus.STOPPED, stopped.getStatus());
    }

    @Test
    void testExists() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn("container-xyz");
        when(containerManager.waitForContainerReady(anyString(), anyInt()))
                .thenReturn(true);

        Conversation created = conversationService.createConversation(request);

        assertTrue(conversationService.exists(created.getConversationId()));
        assertFalse(conversationService.exists("non-existent-id"));
    }

    @Test
    void testExists_FromDatabase() {
        String conversationId = "conv-123";
        when(conversationRepository.existsByConversationId(conversationId)).thenReturn(true);

        assertTrue(conversationService.exists(conversationId));
    }

    @Test
    void testCreateConversation_WithValidationTrigger() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        String mockContainerId = "container-xyz";
        when(containerManager.createContainer(anyString(), anyString(), any(ContainerConfig.class)))
                .thenReturn(mockContainerId);
        when(containerManager.waitForContainerReady(eq(mockContainerId), anyInt()))
                .thenReturn(true);
        when(conversationRepository.findByConversationId(anyString()))
                .thenReturn(Optional.of(ConversationEntity.builder()
                        .conversationId("test-id")
                        .userId("user-123")
                        .projectId("project-A")
                        .sandboxId(mockContainerId)
                        .status(ConversationEntity.ConversationStatus.READY)
                        .build()));

        Conversation conversation = conversationService.createConversation(request);

        assertNotNull(conversation);
        assertEquals(Conversation.ConversationStatus.READY, conversation.getStatus());

        // 验证触发了验证服务
        verify(validationService).triggerValidation(eq(conversation.getConversationId()));
    }
}
