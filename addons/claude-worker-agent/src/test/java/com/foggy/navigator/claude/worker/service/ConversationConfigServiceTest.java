package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.repository.SessionEntityRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationConfigServiceTest {

    @Mock private SessionEntityRepository sessionRepository;
    @Mock private ClaudeTaskRepository taskRepository;
    @Mock private CredentialEncryptor credentialEncryptor;
    @Mock private LlmModelManager llmModelManager;

    @InjectMocks private ConversationConfigService service;

    @Test
    void getOrCreate_existingSession_mapsToConversationConfig() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setPinned(true);
        session.setTitle("Custom Title");
        session.setInteractionState("AWAITING_REPLY");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        ConversationConfigEntity result = service.getOrCreate("s1", "w1", "u1");

        assertEquals("s1", result.getSessionId());
        assertEquals("w1", result.getWorkerId());
        assertEquals("Custom Title", result.getCustomTitle());
        assertEquals("AWAITING_REPLY", result.getInteractionState());
        assertTrue(result.getPinned());
        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void getOrCreate_readsSchemaVersionedAgentTeamsProviderState() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setProviderStateJson(ProviderStateCodec.mergeSessionValue(
                null,
                "claude-worker",
                ProviderStateCodec.FIELD_AGENT_TEAMS_CONFIG_ID,
                "teams-1"));
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        ConversationConfigEntity result = service.getOrCreate("s1", "w1", "u1");

        assertEquals("teams-1", result.getAgentTeamsConfigId());
    }

    @Test
    void getOrCreate_missingSession_createsBackedSessionRecord() {
        when(sessionRepository.findById("s1")).thenReturn(Optional.empty());
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationConfigEntity result = service.getOrCreate("s1", "w1", "u1");

        assertEquals("s1", result.getSessionId());
        assertEquals("w1", result.getWorkerId());
        verify(sessionRepository).save(argThat(session ->
                "s1".equals(session.getId())
                        && "u1".equals(session.getUserId())
                        && "w1".equals(session.getCurrentWorkerId())
                        && "claude-worker".equals(session.getProviderType())));
    }

    @Test
    void updatePin_updatesSessionFields() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationConfigDTO dto = service.updatePin("s1", "u1", true);

        assertTrue(dto.isPinned());
        assertTrue(session.getPinned());
        assertNotNull(session.getPinnedAt());
    }

    @Test
    void saveConfig_writesSchemaVersionedProviderStateAndPreservesUnknownFields() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setProviderStateJson("{\"claudeSessionId\":\"claude-1\",\"other\":\"keep\"}");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ConversationConfigEntity entity = new ConversationConfigEntity();
        entity.setSessionId("s1");
        entity.setWorkerId("w1");
        entity.setUserId("u1");
        entity.setInteractionState("AWAITING_REPLY");
        entity.setAgentTeamsConfigId("teams-1");

        service.saveConfig(entity);

        Map<String, Object> state = ProviderStateCodec.parseObject(session.getProviderStateJson());
        assertEquals(ProviderStateCodec.CURRENT_SCHEMA_VERSION, state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION));
        assertEquals("claude-worker", state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        assertEquals("claude-1", state.get(ProviderStateCodec.FIELD_CLAUDE_SESSION_ID));
        assertEquals("teams-1", state.get(ProviderStateCodec.FIELD_AGENT_TEAMS_CONFIG_ID));
        assertEquals("keep", state.get("other"));
    }

    @Test
    void bindAuth_firstTime_persistsEncryptedTokenOnSession() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialEncryptor.encrypt("my-api-key")).thenReturn("enc-key");
        when(credentialEncryptor.decrypt("enc-key")).thenReturn("my-api-key");

        ConversationConfigDTO dto = service.bindAuth("s1", "u1", "API_KEY", "my-api-key", "https://api.anthropic.com");

        assertEquals("API_KEY", dto.getAuthMode());
        assertEquals("API_KEY", session.getAuthMode());
        assertEquals("enc-key", session.getAuthTokenCiphertext());
        assertEquals("https://api.anthropic.com", session.getAuthBaseUrl());
        assertNotNull(session.getAuthBoundAt());
    }

    @Test
    void bindAuth_alreadyBound_throws() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setAuthBoundAt(LocalDateTime.now());
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        assertThrows(IllegalStateException.class,
                () -> service.bindAuth("s1", "u1", "API_KEY", "token", null));
    }

    @Test
    void bindAuthFromWorker_alreadyBound_skipsSave() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setAuthBoundAt(LocalDateTime.now());
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        service.bindAuthFromWorker("s1", "w1", "u1", java.util.Map.of("api_key", "new-key"));

        verify(sessionRepository, never()).save(any(SessionEntity.class));
    }

    @Test
    void updateTags_serializesIntoSessionTagsJson() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateTags("s1", "u1", List.of("bug", "feature"));

        assertNotNull(session.getTagsJson());
        assertTrue(session.getTagsJson().contains("bug"));
        assertTrue(session.getTagsJson().contains("feature"));
    }

    @Test
    void updateTitle_returnsMaskedTokenFromSessionStorage() {
        SessionEntity session = buildSession("s1", "w1", "u1");
        session.setAuthTokenCiphertext("enc-token");
        session.setAuthBoundAt(LocalDateTime.now());
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));
        when(sessionRepository.save(any(SessionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(credentialEncryptor.decrypt("enc-token")).thenReturn("sk-ant-api03-1234567890abcdef");

        ConversationConfigDTO dto = service.updateTitle("s1", "u1", "My Title");

        assertEquals("My Title", session.getTitle());
        assertEquals("sk-ant****cdef", dto.getMaskedAuthToken());
    }

    @Test
    void archiveConversation_wrongUser_throws() {
        SessionEntity session = buildSession("s1", "w1", "other");
        when(sessionRepository.findById("s1")).thenReturn(Optional.of(session));

        assertThrows(IllegalArgumentException.class,
                () -> service.archiveConversation("s1", "u1"));
    }

    @Test
    void findSessionIdsByInteractionStates_delegatesToSessionRepository() {
        when(sessionRepository.findSessionIdsByInteractionStateIn("u1", List.of("PROCESSING", "ON_HOLD")))
                .thenReturn(List.of("s1", "s2"));

        List<String> result = service.findSessionIdsByInteractionStates("u1", List.of("PROCESSING", "ON_HOLD"));

        assertEquals(List.of("s1", "s2"), result);
    }

    @Test
    void getDecryptedToken_usesCiphertextFromConversationConfig() {
        ConversationConfigEntity entity = new ConversationConfigEntity();
        entity.setAuthToken("enc-value");
        when(credentialEncryptor.decrypt("enc-value")).thenReturn("plain-value");

        assertEquals("plain-value", service.getDecryptedToken(entity));
        verify(credentialEncryptor).decrypt("enc-value");
    }

    private SessionEntity buildSession(String sessionId, String workerId, String userId) {
        SessionEntity entity = new SessionEntity();
        entity.setId(sessionId);
        entity.setUserId(userId);
        entity.setCurrentWorkerId(workerId);
        entity.setProviderType("claude-worker");
        entity.setStatus("ACTIVE");
        entity.setInteractionState("PROCESSING");
        entity.setPinned(false);
        return entity;
    }
}
