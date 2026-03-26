package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.LlmModelConfigDTO;
import com.foggy.navigator.common.entity.SessionEntity;
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

    private SessionMetadataService service;

    @BeforeEach
    void setUp() {
        service = new SessionMetadataService(sessionRepository, credentialEncryptor, llmModelManager);
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
        assertEquals("sk-tes****7890", dto.getMaskedAuthToken());
        assertEquals(List.of("tag-a", "tag-b"), dto.getTags());
        assertEquals("AWAITING_REPLY", dto.getInteractionState());
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
                () -> service.bindAuth("session-1", "user-1", "API_KEY", "token", null));

        assertEquals("Auth already bound for this conversation", error.getMessage());
        verify(sessionRepository, never()).save(any());
        verify(credentialEncryptor, never()).encrypt(anyString());
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
