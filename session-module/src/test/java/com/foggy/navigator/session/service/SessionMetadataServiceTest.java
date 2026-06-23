package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.entity.WorkingDirectoryEntity;
import com.foggy.navigator.common.repository.SessionTaskRepository;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionMetadataServiceTest {

    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private CredentialEncryptor credentialEncryptor;
    @Mock
    private LlmModelManager llmModelManager;
    @Mock
    private WorkingDirectoryRepository workingDirectoryRepository;
    @Mock
    private SessionTaskRepository sessionTaskRepository;

    private SessionMetadataService service;

    @BeforeEach
    void setUp() {
        service = new SessionMetadataService(sessionRepository, credentialEncryptor, llmModelManager,
                workingDirectoryRepository, sessionTaskRepository);
    }

    @Test
    void listBySessionIds_masksTokenAndParsesTags() {
        SessionEntity session = session("session-1");
        session.setTitle("My Session");
        session.setPinned(true);
        session.setInteractionState("AWAITING_REPLY");
        session.setTagsJson("[\"tag-a\",\"tag-b\"]");
        session.setAuthMode("API_KEY");
        session.setAuthBoundAt(LocalDateTime.of(2026, 3, 24, 10, 0));
        session.setAuthModelConfigId("cfg-1");
        session.setAuthTokenCiphertext("cipher");

        when(sessionRepository.findAllById(List.of("session-1"))).thenReturn(List.of(session));
        when(credentialEncryptor.decrypt("cipher")).thenReturn("sk-test-1234567890");

        List<SessionConfigDTO> result = service.listBySessionIds("user-1", List.of("session-1"));

        assertEquals(1, result.size());
        SessionConfigDTO dto = result.get(0);
        assertEquals("session-1", dto.getSessionId());
        assertEquals("My Session", dto.getCustomTitle());
        assertTrue(dto.isPinned());
        assertTrue(dto.isAuthBound());
        assertEquals("cfg-1", dto.getAuthModelConfigId());
        assertEquals("sk-tes****7890", dto.getMaskedAuthToken());
        assertEquals(List.of("tag-a", "tag-b"), dto.getTags());
        assertEquals("AWAITING_REPLY", dto.getInteractionState());
    }

    @Test
    void updateMilestone_acceptsDirectoryOwnedMilestone() {
        SessionEntity session = session("session-1");
        session.setCurrentDirectoryId("dir-1");
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("dir-1");
        directory.setUserId("user-1");
        directory.setMilestonesJson("[{\"id\":\"ms-1\",\"name\":\"v3.0.0\",\"status\":\"ACTIVE\",\"docPath\":\"docs/v3.0.0\"}]");

        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(workingDirectoryRepository.findByDirectoryIdAndUserId("dir-1", "user-1")).thenReturn(Optional.of(directory));
        when(sessionRepository.save(session)).thenReturn(session);

        SessionConfigDTO result = service.updateMilestone("session-1", "user-1", "ms-1");

        assertEquals("ms-1", session.getMilestoneId());
        assertEquals("ms-1", result.getMilestoneId());
    }

    @Test
    void updateMilestone_rejectsUnknownMilestone() {
        SessionEntity session = session("session-1");
        session.setCurrentDirectoryId("dir-1");
        WorkingDirectoryEntity directory = new WorkingDirectoryEntity();
        directory.setDirectoryId("dir-1");
        directory.setUserId("user-1");
        directory.setMilestonesJson("[{\"id\":\"ms-1\",\"name\":\"v3.0.0\",\"status\":\"ACTIVE\"}]");

        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(workingDirectoryRepository.findByDirectoryIdAndUserId("dir-1", "user-1")).thenReturn(Optional.of(directory));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateMilestone("session-1", "user-1", "ms-2"));

        assertEquals("Milestone not found in directory: ms-2", error.getMessage());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void updatePin_updatesPinnedFields() {
        SessionEntity session = session("session-1");
        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(session)).thenReturn(session);

        SessionConfigDTO result = service.updatePin("session-1", "user-1", true);

        assertTrue(session.getPinned());
        assertNotNull(session.getPinnedAt());
        assertTrue(result.isPinned());
    }

    @Test
    void archiveConversation_createsMetadataFromOwnedTaskProjectionWhenSessionMissing() {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setSessionId("legacy-session-1");
        task.setTaskId("task-1");
        task.setUserId("user-1");
        task.setTenantId("tenant-1");
        task.setProviderType("codex-worker");
        task.setAgentId("agent-1");
        task.setWorkerId("worker-1");
        task.setDirectoryId("dir-1");
        task.setModel("gpt-5");
        task.setCreatedAt(LocalDateTime.of(2026, 3, 24, 9, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 3, 24, 10, 0));

        when(sessionRepository.findByIdAndUserId("legacy-session-1", "user-1")).thenReturn(Optional.empty());
        when(sessionTaskRepository.findFirstBySessionIdAndUserIdOrderByCreatedAtDesc("legacy-session-1", "user-1"))
                .thenReturn(Optional.of(task));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        SessionConfigDTO result = service.archiveConversation("legacy-session-1", "user-1");

        assertEquals("legacy-session-1", result.getSessionId());
        assertEquals("ARCHIVED", result.getInteractionState());
        verify(sessionRepository).save(argThat(session ->
                "legacy-session-1".equals(session.getId())
                        && "user-1".equals(session.getUserId())
                        && "codex-worker".equals(session.getProviderType())
                        && "worker-1".equals(session.getCurrentWorkerId())
                        && "task-1".equals(session.getLatestTaskId())
                        && "ARCHIVED".equals(session.getInteractionState())));
    }

    @Test
    void archiveConversation_stillRejectsUnknownSessionWithoutOwnedTaskProjection() {
        when(sessionRepository.findByIdAndUserId("missing-session", "user-1")).thenReturn(Optional.empty());
        when(sessionTaskRepository.findFirstBySessionIdAndUserIdOrderByCreatedAtDesc("missing-session", "user-1"))
                .thenReturn(Optional.empty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.archiveConversation("missing-session", "user-1"));

        assertEquals("Session not found: missing-session", error.getMessage());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void batchBindAuth_resolvesModelConfigUsingSessionWorker() {
        SessionEntity session = session("session-1");
        session.setCurrentWorkerId("worker-1");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setId("cfg-1");
        modelConfig.setBaseUrl("");

        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey("cfg-1")).thenReturn("sk-live-123456");
        when(credentialEncryptor.encrypt("sk-live-123456")).thenReturn("encrypted");
        when(sessionRepository.save(session)).thenReturn(session);

        int bound = service.batchBindAuth(List.of("session-1"), "user-1",
                null, null, null, false, "cfg-1");

        assertEquals(1, bound);
        assertEquals("API_KEY", session.getAuthMode());
        assertEquals("encrypted", session.getAuthTokenCiphertext());
        assertEquals("cfg-1", session.getAuthModelConfigId());
        assertNotNull(session.getAuthBoundAt());
        verify(llmModelManager).validateModelAccessForWorker("cfg-1", "worker-1");
    }

    @Test
    void bindAuth_rejectsAlreadyBoundSession() {
        SessionEntity session = session("session-1");
        session.setAuthBoundAt(LocalDateTime.now());
        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.bindAuth("session-1", "user-1", "API_KEY", "token", null, null));

        assertEquals("Auth already bound for this conversation", error.getMessage());
        verify(sessionRepository, never()).save(any());
        verify(credentialEncryptor, never()).encrypt(anyString());
    }

    @Test
    void bindAuth_resolvesModelConfigUsingSessionWorker() {
        SessionEntity session = session("session-1");
        session.setCurrentWorkerId("worker-1");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setId("cfg-1");

        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-1")).thenReturn(Optional.of(modelConfig));
        when(llmModelManager.getDecryptedApiKey("cfg-1")).thenReturn("sk-live-123456");
        when(credentialEncryptor.encrypt("sk-live-123456")).thenReturn("encrypted");
        when(sessionRepository.save(session)).thenReturn(session);

        SessionConfigDTO result = service.bindAuth("session-1", "user-1",
                null, null, null, "cfg-1");

        assertEquals("API_KEY", session.getAuthMode());
        assertEquals("encrypted", session.getAuthTokenCiphertext());
        assertEquals("cfg-1", session.getAuthModelConfigId());
        assertEquals("cfg-1", result.getAuthModelConfigId());
        assertNotNull(session.getAuthBoundAt());
        verify(llmModelManager).validateModelAccessForWorker("cfg-1", "worker-1");
    }

    @Test
    void bindAuth_preservesSubscriptionModelConfigWithoutToken() {
        SessionEntity session = session("session-1");
        session.setCurrentWorkerId("worker-1");

        LlmModelConfigDTO modelConfig = new LlmModelConfigDTO();
        modelConfig.setId("cfg-subscription");
        modelConfig.setWorkerBackend("CLAUDE_CODE");
        modelConfig.setHasApiKey(false);

        when(sessionRepository.findByIdAndUserId("session-1", "user-1")).thenReturn(Optional.of(session));
        when(llmModelManager.getModelConfig("cfg-subscription")).thenReturn(Optional.of(modelConfig));
        when(sessionRepository.save(session)).thenReturn(session);

        SessionConfigDTO result = service.bindAuth("session-1", "user-1",
                null, null, null, "cfg-subscription");

        assertEquals("SUBSCRIPTION", session.getAuthMode());
        assertNull(session.getAuthTokenCiphertext());
        assertEquals("cfg-subscription", session.getAuthModelConfigId());
        assertEquals("cfg-subscription", result.getAuthModelConfigId());
        assertNotNull(session.getAuthBoundAt());
        verify(llmModelManager).validateModelAccessForWorker("cfg-subscription", "worker-1");
        verify(llmModelManager, never()).getDecryptedApiKey("cfg-subscription");
    }

    private SessionEntity session(String sessionId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId("user-1");
        session.setStatus("ACTIVE");
        session.setCreatedAt(LocalDateTime.of(2026, 3, 24, 9, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 3, 24, 9, 0));
        return session;
    }
}
