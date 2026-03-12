package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.ConversationConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.ConversationConfigEntity;
import com.foggy.navigator.claude.worker.model.entity.ClaudeTaskEntity;
import com.foggy.navigator.claude.worker.repository.ConversationConfigRepository;
import com.foggy.navigator.claude.worker.repository.ClaudeTaskRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ConversationConfigService 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class ConversationConfigServiceTest {

    @Mock private ConversationConfigRepository configRepository;
    @Mock private ClaudeTaskRepository taskRepository;
    @Mock private CredentialEncryptor credentialEncryptor;
    @Mock private LlmModelManager llmModelManager;

    @InjectMocks private ConversationConfigService service;

    // ---- getOrCreate ----

    @Test
    void getOrCreate_existingConfig_returnsIt() {
        ConversationConfigEntity existing = buildEntity("s1", "w1", "u1");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(existing));

        ConversationConfigEntity result = service.getOrCreate("s1", "w1", "u1");
        assertEquals("s1", result.getSessionId());
        verify(configRepository, never()).save(any());
    }

    @Test
    void getOrCreate_notExisting_creates() {
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationConfigEntity result = service.getOrCreate("s1", "w1", "u1");
        assertEquals("s1", result.getSessionId());
        assertEquals("w1", result.getWorkerId());
        verify(configRepository).save(any());
    }

    // ---- updatePin ----

    @Test
    void updatePin_setsTrue() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        ConversationConfigDTO dto = service.updatePin("s1", "u1", true);
        assertTrue(dto.isPinned());
        assertNotNull(entity.getPinnedAt());
    }

    @Test
    void updatePin_setsFalse_clearsPinnedAt() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setPinned(true);
        entity.setPinnedAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.updatePin("s1", "u1", false);
        assertFalse(entity.getPinned());
        assertNull(entity.getPinnedAt());
    }

    @Test
    void updatePin_wrongUser_throws() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "other-user");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.updatePin("s1", "u1", true));
    }

    // ---- bindAuth ----

    @Test
    void bindAuth_firstTime_binds() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(null);
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("my-api-key")).thenReturn("enc-key");
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt("enc-key")).thenReturn("my-api-key");

        service.bindAuth("s1", "u1", "API_KEY", "my-api-key", "https://api.anthropic.com");

        assertEquals("API_KEY", entity.getAuthMode());
        assertEquals("enc-key", entity.getAuthToken());
        assertEquals("https://api.anthropic.com", entity.getBaseUrl());
        assertNotNull(entity.getAuthBoundAt());
    }

    @Test
    void bindAuth_alreadyBound_throws() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class,
                () -> service.bindAuth("s1", "u1", "API_KEY", "tk", null));
    }

    @Test
    void bindAuth_wrongUser_throws() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "other");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.bindAuth("s1", "u1", "API_KEY", "tk", null));
    }

    // ---- bindAuthFromWorker ----

    @Test
    void bindAuthFromWorker_setsFromMap() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(null);
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("worker-key")).thenReturn("enc-worker-key");
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> workerAuth = Map.of(
                "auth_mode", "CUSTOM_ENDPOINT",
                "api_key", "worker-key",
                "base_url", "http://custom"
        );

        service.bindAuthFromWorker("s1", "w1", "u1", workerAuth);

        assertEquals("CUSTOM_ENDPOINT", entity.getAuthMode());
        assertEquals("enc-worker-key", entity.getAuthToken());
        assertEquals("http://custom", entity.getBaseUrl());
        assertNotNull(entity.getAuthBoundAt());
    }

    @Test
    void bindAuthFromWorker_alreadyBound_skips() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        service.bindAuthFromWorker("s1", "w1", "u1", Map.of("api_key", "new-key"));

        // save should NOT be called since already bound
        verify(configRepository, never()).save(any());
    }

    // ---- bindAuthFromDirectory ----

    @Test
    void bindAuthFromDirectory_encryptsToken() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(null);
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("dir-token")).thenReturn("enc-dir-token");
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.bindAuthFromDirectory("s1", "w1", "u1", "SUBSCRIPTION", "dir-token", null);

        assertEquals("SUBSCRIPTION", entity.getAuthMode());
        assertEquals("enc-dir-token", entity.getAuthToken());
        assertNotNull(entity.getAuthBoundAt());
    }

    @Test
    void bindAuthFromDirectory_alreadyBound_skips() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        service.bindAuthFromDirectory("s1", "w1", "u1", "SUB", "tk", null);

        verify(configRepository, never()).save(any());
    }

    // ---- updateAuth (允许覆盖) ----

    @Test
    void updateAuth_overwritesExisting() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(LocalDateTime.now());
        entity.setAuthMode("OLD_MODE");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("new-token")).thenReturn("enc-new");
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt("enc-new")).thenReturn("new-token");

        service.updateAuth("s1", "u1", "NEW_MODE", "new-token", "http://new-url");

        assertEquals("NEW_MODE", entity.getAuthMode());
        assertEquals("enc-new", entity.getAuthToken());
        assertEquals("http://new-url", entity.getBaseUrl());
    }

    @Test
    void updateAuth_setsAuthBoundAt_ifNull() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthBoundAt(null);
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(credentialEncryptor.encrypt("token")).thenReturn("enc");
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt("enc")).thenReturn("token");

        service.updateAuth("s1", "u1", "MODE", "token", null);

        assertNotNull(entity.getAuthBoundAt());
    }

    // ---- 状态转换 ----

    @Test
    void archiveConversation_setsARCHIVED() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.archiveConversation("s1", "u1");

        assertEquals("ARCHIVED", entity.getInteractionState());
    }

    @Test
    void unarchiveConversation_setsAWAITING_REPLY() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setInteractionState("ARCHIVED");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.unarchiveConversation("s1", "u1");

        assertEquals("AWAITING_REPLY", entity.getInteractionState());
    }

    @Test
    void holdConversation_setsON_HOLD() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.holdConversation("s1", "u1");

        assertEquals("ON_HOLD", entity.getInteractionState());
    }

    @Test
    void unholdConversation_setsAWAITING_REPLY() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setInteractionState("ON_HOLD");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.unholdConversation("s1", "u1");

        assertEquals("AWAITING_REPLY", entity.getInteractionState());
    }

    @Test
    void archiveConversation_wrongUser_throws() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "other");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.archiveConversation("s1", "u1"));
    }

    // ---- 标签 ----

    @Test
    void updateTags_serializesJsonArray() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.updateTags("s1", "u1", List.of("bug", "feature"));

        assertNotNull(entity.getTags());
        assertTrue(entity.getTags().contains("bug"));
        assertTrue(entity.getTags().contains("feature"));
    }

    @Test
    void updateTags_nullOrEmpty_clears() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setTags("[\"old\"]");
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialEncryptor.decrypt(anyString())).thenReturn("token");

        service.updateTags("s1", "u1", null);

        assertNull(entity.getTags());
    }

    // ---- Token 脱敏 ----

    @Test
    void toDTO_masksLongToken() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthToken("enc-token");
        entity.setAuthBoundAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentialEncryptor.decrypt("enc-token")).thenReturn("sk-ant-api03-1234567890abcdef");

        ConversationConfigDTO dto = service.updateTitle("s1", "u1", "My Title");

        assertNotNull(dto.getMaskedAuthToken());
        // Long token: first 6 + **** + last 4
        assertEquals("sk-ant****cdef", dto.getMaskedAuthToken());
    }

    @Test
    void toDTO_masksShortToken() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthToken("enc-token");
        entity.setAuthBoundAt(LocalDateTime.now());
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(credentialEncryptor.decrypt("enc-token")).thenReturn("short12");

        ConversationConfigDTO dto = service.updateTitle("s1", "u1", "Title");

        // Short token (<=10): first 2 + **** + last 2
        assertEquals("sh****12", dto.getMaskedAuthToken());
    }

    @Test
    void toDTO_noAuthToken_nullMasked() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthToken(null);
        entity.setAuthBoundAt(null);
        when(configRepository.findBySessionId("s1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConversationConfigDTO dto = service.updateTitle("s1", "u1", "Title");

        assertNull(dto.getMaskedAuthToken());
    }

    // ---- 解密 token ----

    @Test
    void getDecryptedToken_null_returnsNull() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthToken(null);

        assertNull(service.getDecryptedToken(entity));
        verify(credentialEncryptor, never()).decrypt(anyString());
    }

    @Test
    void getDecryptedToken_decrypts() {
        ConversationConfigEntity entity = buildEntity("s1", "w1", "u1");
        entity.setAuthToken("enc-value");
        when(credentialEncryptor.decrypt("enc-value")).thenReturn("plain-value");

        assertEquals("plain-value", service.getDecryptedToken(entity));
    }

    // ---- helper ----

    private ConversationConfigEntity buildEntity(String sessionId, String workerId, String userId) {
        ConversationConfigEntity entity = new ConversationConfigEntity();
        entity.setSessionId(sessionId);
        entity.setWorkerId(workerId);
        entity.setUserId(userId);
        entity.setPinned(false);
        return entity;
    }
}
