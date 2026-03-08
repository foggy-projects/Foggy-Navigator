package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.CreateConversationRequest;
import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.entity.ConversationEntity;
import com.foggy.navigator.coding.agent.api.repository.ConversationRepository;
import com.foggy.navigator.coding.agent.api.repository.GitCredentialRepository;
import com.foggy.navigator.coding.agent.git.GitProviderFactory;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartRequest;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock
    private OpenHandsClientFactory clientFactory;

    @Mock
    private OpenHandsClient openHandsClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ValidationService validationService;

    @Mock
    private SessionManager sessionManager;

    @Mock
    private GitCredentialRepository gitCredentialRepository;

    @Mock
    private GitProviderFactory gitProviderFactory;

    @InjectMocks
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(conversationService, "autoTriggerValidationOnCreate", true);
        ReflectionTestUtils.setField(conversationService, "configuredModel", "gpt-4");
        lenient().when(sessionManager.createSession(any())).thenReturn("session-001");
    }

    @Test
    void testCreateConversation_Success() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        // V1 flow: startConversation returns a task with id
        AppConversationStartTask startTask = AppConversationStartTask.builder()
                .id("task-001")
                .status("WORKING")
                .build();

        // V1 flow: getStartTask returns task with appConversationId when READY
        AppConversationStartTask readyTask = AppConversationStartTask.builder()
                .id("task-001")
                .appConversationId("oh-conv-123")
                .sandboxId("sandbox-xyz")
                .status("READY")
                .build();

        when(clientFactory.getClientForUser("user-123")).thenReturn(openHandsClient);
        when(openHandsClient.startConversation(any(AppConversationStartRequest.class))).thenReturn(startTask);
        when(openHandsClient.getStartTask("task-001")).thenReturn(readyTask);
        when(conversationRepository.findByConversationId(anyString()))
                .thenReturn(Optional.of(ConversationEntity.builder()
                        .conversationId("test-id")
                        .userId("user-123")
                        .projectId("project-A")
                        .sandboxId("sandbox-xyz")
                        .ohConversationId("oh-conv-123")
                        .status(ConversationEntity.ConversationStatus.STARTING)
                        .build()));

        Conversation conversation = conversationService.createConversation(request);

        assertNotNull(conversation);
        assertEquals("user-123", conversation.getUserId());
        assertEquals("project-A", conversation.getProjectId());
        assertEquals("main", conversation.getBaseBranch());
        assertEquals("sandbox-xyz", conversation.getSandboxId());
        assertEquals("oh-conv-123", conversation.getOhConversationId());
        assertEquals(Conversation.ConversationStatus.READY, conversation.getStatus());

        verify(clientFactory).getClientForUser("user-123");
        verify(openHandsClient).startConversation(any(AppConversationStartRequest.class));
        verify(openHandsClient).getStartTask("task-001");
        verify(eventPublisher, times(2)).publishEvent(any(Event.class));
        verify(conversationRepository, times(2)).save(any(ConversationEntity.class));
    }

    @Test
    void testCreateConversation_Error() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        // V1 flow: startConversation returns a task
        AppConversationStartTask startTask = AppConversationStartTask.builder()
                .id("task-001")
                .status("WORKING")
                .build();

        // V1 flow: getStartTask returns ERROR status
        AppConversationStartTask errorTask = AppConversationStartTask.builder()
                .id("task-001")
                .status("ERROR")
                .detail("Failed to start sandbox")
                .build();

        when(clientFactory.getClientForUser("user-123")).thenReturn(openHandsClient);
        when(openHandsClient.startConversation(any(AppConversationStartRequest.class))).thenReturn(startTask);
        when(openHandsClient.getStartTask("task-001")).thenReturn(errorTask);
        when(conversationRepository.findByConversationId(anyString()))
                .thenReturn(Optional.of(ConversationEntity.builder()
                        .conversationId("test-id")
                        .userId("user-123")
                        .projectId("project-A")
                        .status(ConversationEntity.ConversationStatus.STARTING)
                        .build()));

        Conversation conversation = conversationService.createConversation(request);

        // Conversation should end up in ERROR state
        assertEquals(Conversation.ConversationStatus.ERROR, conversation.getStatus());
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
                .sandboxId("sandbox-xyz")
                .ohConversationId("oh-conv-123")
                .userId("user-123")
                .projectId("project-A")
                .status(ConversationEntity.ConversationStatus.READY)
                .namespace("user-user-123-session-123")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .baseBranch("main")
                .workingBranch("coding-agent/task-20260203")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(conversationRepository.findByConversationId(conversationId)).thenReturn(Optional.of(entity));

        Conversation conversation = conversationService.getConversation(conversationId);

        assertNotNull(conversation);
        assertEquals(conversationId, conversation.getConversationId());
        assertEquals("user-123", conversation.getUserId());
        assertEquals("oh-conv-123", conversation.getOhConversationId());
        assertEquals(Conversation.ConversationStatus.READY, conversation.getStatus());
    }

    @Test
    void testExists_FromDatabase() {
        String conversationId = "conv-123";
        when(conversationRepository.existsByConversationId(conversationId)).thenReturn(true);

        assertTrue(conversationService.exists(conversationId));
    }
}
