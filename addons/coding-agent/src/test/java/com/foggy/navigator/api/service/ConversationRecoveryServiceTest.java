package com.foggy.navigator.api.service;

import com.foggy.navigator.api.model.Conversation;
import com.foggy.navigator.api.model.entity.ConversationEntity;
import com.foggy.navigator.api.repository.ConversationRepository;
import com.foggy.navigator.foundation.git.OpenHandsContainerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationRecoveryServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationService conversationService;

    @Mock
    private OpenHandsContainerManager containerManager;

    @InjectMocks
    private ConversationRecoveryService conversationRecoveryService;

    private ConversationEntity stoppedConversation;
    private ConversationEntity errorConversation;
    private ConversationEntity readyConversation;

    @BeforeEach
    void setUp() {
        stoppedConversation = ConversationEntity.builder()
                .id(1L)
                .conversationId("conv-stopped")
                .sandboxId("container-stopped")
                .userId("user-123")
                .projectId("project-A")
                .status(ConversationEntity.ConversationStatus.STOPPED)
                .namespace("user-user-123-session-123")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .createdAt(LocalDateTime.now().minusHours(1))
                .updatedAt(LocalDateTime.now().minusHours(1))
                .build();

        errorConversation = ConversationEntity.builder()
                .id(2L)
                .conversationId("conv-error")
                .sandboxId("container-error")
                .userId("user-456")
                .projectId("project-B")
                .status(ConversationEntity.ConversationStatus.ERROR)
                .namespace("user-user-456-session-456")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .createdAt(LocalDateTime.now().minusHours(2))
                .updatedAt(LocalDateTime.now().minusHours(2))
                .build();

        readyConversation = ConversationEntity.builder()
                .id(3L)
                .conversationId("conv-ready")
                .sandboxId("container-ready")
                .userId("user-789")
                .projectId("project-C")
                .status(ConversationEntity.ConversationStatus.READY)
                .namespace("user-user-789-session-789")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .updatedAt(LocalDateTime.now().minusMinutes(30))
                .build();
    }

    @Test
    void testRecoverConversation_Stopped_Success() {
        String conversationId = "conv-stopped";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(stoppedConversation));
        when(containerManager.startContainer("container-stopped")).thenReturn(true);
        when(containerManager.waitForContainerReady("container-stopped", 60)).thenReturn(true);

        conversationRecoveryService.recoverConversation(conversationId);

        verify(containerManager).startContainer("container-stopped");
        verify(containerManager).waitForContainerReady("container-stopped", 60);
        verify(conversationRepository).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.READY
        ));
    }

    @Test
    void testRecoverConversation_Stopped_Timeout() {
        String conversationId = "conv-stopped";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(stoppedConversation));
        when(containerManager.startContainer("container-stopped")).thenReturn(true);
        when(containerManager.waitForContainerReady("container-stopped", 60)).thenReturn(false);

        assertThrows(RuntimeException.class, () -> {
            conversationRecoveryService.recoverConversation(conversationId);
        });

        verify(containerManager).startContainer("container-stopped");
        verify(containerManager).waitForContainerReady("container-stopped", 60);
        verify(conversationRepository).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.ERROR
        ));
    }

    @Test
    void testRecoverConversation_Error_ContainerRunning() {
        String conversationId = "conv-error";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(errorConversation));
        when(containerManager.containerExists("container-error")).thenReturn(true);
        when(containerManager.isContainerRunning("container-error")).thenReturn(true);

        conversationRecoveryService.recoverConversation(conversationId);

        verify(containerManager, never()).startContainer(anyString());
        verify(conversationRepository).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.READY
        ));
    }

    @Test
    void testRecoverConversation_Error_ContainerExistsButNotRunning() {
        String conversationId = "conv-error";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(errorConversation));
        when(containerManager.containerExists("container-error")).thenReturn(true);
        when(containerManager.isContainerRunning("container-error")).thenReturn(false);
        when(containerManager.startContainer("container-error")).thenReturn(true);
        when(containerManager.waitForContainerReady("container-error", 60)).thenReturn(true);

        conversationRecoveryService.recoverConversation(conversationId);

        verify(containerManager).startContainer("container-error");
        verify(containerManager).waitForContainerReady("container-error", 60);
        verify(conversationRepository).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.READY
        ));
    }

    @Test
    void testRecoverConversation_Error_ContainerNotExists() {
        String conversationId = "conv-error";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(errorConversation));
        when(containerManager.containerExists("container-error")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> {
            conversationRecoveryService.recoverConversation(conversationId);
        });

        verify(containerManager, never()).startContainer(anyString());
        verify(conversationRepository, times(2)).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.ERROR
        ));
    }

    @Test
    void testRecoverConversation_Ready_NoAction() {
        String conversationId = "conv-ready";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(readyConversation));

        conversationRecoveryService.recoverConversation(conversationId);

        verify(containerManager, never()).startContainer(anyString());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void testRecoverConversation_NotFound() {
        String conversationId = "conv-not-found";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            conversationRecoveryService.recoverConversation(conversationId);
        });
    }

    @Test
    void testRecoverAllConversations() {
        when(conversationRepository.findAll())
                .thenReturn(List.of(stoppedConversation, errorConversation, readyConversation));

        // Mock findByConversationId for recoverConversation calls
        when(conversationRepository.findByConversationId("conv-stopped"))
                .thenReturn(Optional.of(stoppedConversation));
        when(conversationRepository.findByConversationId("conv-error"))
                .thenReturn(Optional.of(errorConversation));

        // Mock container operations for stopped conversation
        when(containerManager.startContainer("container-stopped")).thenReturn(true);
        when(containerManager.waitForContainerReady("container-stopped", 60)).thenReturn(true);

        // Mock container operations for error conversation
        when(containerManager.containerExists("container-error")).thenReturn(true);
        when(containerManager.isContainerRunning("container-error")).thenReturn(true);

        Conversation recoveredConversation = Conversation.builder()
                .conversationId("conv-stopped")
                .status(Conversation.ConversationStatus.READY)
                .build();

        when(conversationService.getConversation("conv-stopped")).thenReturn(recoveredConversation);
        when(conversationService.getConversation("conv-error")).thenReturn(recoveredConversation);

        List<Conversation> recovered = conversationRecoveryService.recoverAllConversations();

        assertEquals(2, recovered.size());
        verify(conversationRepository, times(2)).save(any());
    }

    @Test
    void testCleanupStoppedConversations() {
        when(conversationRepository.findByStatus(ConversationEntity.ConversationStatus.STOPPED))
                .thenReturn(List.of(stoppedConversation));
        when(containerManager.containerExists("container-stopped")).thenReturn(false);

        conversationRecoveryService.cleanupStoppedConversations();

        verify(conversationRepository).delete(stoppedConversation);
    }

    @Test
    void testCleanupStoppedConversations_ContainerExists() {
        when(conversationRepository.findByStatus(ConversationEntity.ConversationStatus.STOPPED))
                .thenReturn(List.of(stoppedConversation));
        when(containerManager.containerExists("container-stopped")).thenReturn(true);

        conversationRecoveryService.cleanupStoppedConversations();

        verify(conversationRepository, never()).delete(any());
    }

    @Test
    void testCleanupStoppedConversations_NoConversations() {
        when(conversationRepository.findByStatus(ConversationEntity.ConversationStatus.STOPPED))
                .thenReturn(List.of());

        conversationRecoveryService.cleanupStoppedConversations();

        verify(conversationRepository, never()).delete(any());
    }

    @Test
    void testRecoverConversation_StartContainerFailure() {
        String conversationId = "conv-stopped";
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(stoppedConversation));
        when(containerManager.startContainer("container-stopped")).thenReturn(false);

        assertThrows(RuntimeException.class, () -> {
            conversationRecoveryService.recoverConversation(conversationId);
        });

        verify(conversationRepository).save(argThat(entity ->
                entity.getConversationId().equals(conversationId) &&
                entity.getStatus() == ConversationEntity.ConversationStatus.ERROR
        ));
    }

    @Test
    void testRecoverConversation_NullContainerId() {
        String conversationId = "conv-stopped";
        stoppedConversation.setSandboxId(null);
        when(conversationRepository.findByConversationId(conversationId))
                .thenReturn(Optional.of(stoppedConversation));

        assertThrows(RuntimeException.class, () -> {
            conversationRecoveryService.recoverConversation(conversationId);
        });

        verify(containerManager, never()).startContainer(anyString());
    }
}
