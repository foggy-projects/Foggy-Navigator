package com.foggy.navigator.session.service.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.AgentMessageBuilder;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.common.entity.SessionMessagePayloadEntity;
import com.foggy.navigator.common.entity.SessionMessagePayloadStatus;
import com.foggy.navigator.common.repository.SessionMessagePayloadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionMessagePayloadRoutingServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private SessionMessagePayloadRepository payloadRepository;
    @Mock
    private SessionMessagePayloadStore payloadStore;

    private SessionMessagePayloadProperties properties;
    private SessionMessagePayloadRoutingService service;

    @BeforeEach
    void setUp() {
        properties = new SessionMessagePayloadProperties();
        properties.setEnabled(true);
        properties.setInlinePreviewBytes(48L * 1024L);
        properties.setMaxPayloadBytes(8L * 1024L * 1024L);
        service = new SessionMessagePayloadRoutingService(
                payloadRepository, payloadStore, properties, objectMapper);
    }

    @Test
    void storesFullUtf8ToolOutputAndPersistsOnlyBoundedPublicPreview() throws Exception {
        stubPayloadBackend();
        String output = "前缀\"\\emoji🔧\n".repeat(8_000) + "尾部✅";
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-ready", output);
        when(payloadRepository.findByMessageIdForUpdate("message-ready")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenReturn(new StoredSessionMessagePayload(
                "filesystem", "a".repeat(64) + ".gz", "gzip",
                output.getBytes(StandardCharsets.UTF_8).length, 12_345L, sha256(output)));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        ArgumentCaptor<SessionMessagePayload> payloadCaptor = ArgumentCaptor.forClass(SessionMessagePayload.class);
        verify(payloadStore).write(payloadCaptor.capture());
        verify(payloadRepository).findByMessageIdForUpdate("message-ready");
        assertEquals(output, new String(payloadCaptor.getValue().content(), StandardCharsets.UTF_8),
                "store must receive the unescaped, untruncated UTF-8 bytes");

        Map<String, Object> payload = payload(message);
        assertTrue((Boolean) payload.get("dataTruncated"));
        assertEquals(payload.get("data"), payload.get("content"),
                "duplicate tool content must be bounded with data");
        assertTrue(((String) payload.get("data")).startsWith("前缀"));
        assertTrue(((String) payload.get("data")).endsWith("尾部✅"));
        assertEquals("session_message_payload_store", payload.get("truncationReason"));
        assertFalse(payload.containsKey("storageKey"));
        Map<?, ?> descriptor = (Map<?, ?>) payload.get("payloadDescriptor");
        assertEquals("READY", descriptor.get("status"));
        assertFalse(descriptor.containsKey("storageKey"));
        assertFalse(descriptor.containsKey("backend"));
        assertTrue(serializedMetadataBytes(message, payload) <= 48L * 1024L,
                "BUG-021's 48 KiB durable metadata guard must still hold after JSON escaping");
    }

    @Test
    void routesLargeLangGraphJsonDataWhenPythonWhitespaceDiffersFromJackson() throws Exception {
        stubPayloadBackend();
        String toolResult = "valid-json-🔧\"\\\n".repeat(10_000);
        // Python json.dumps(..., ensure_ascii=False) uses spaces after ':' and
        // ',', while Jackson's default serializer does not. The values are the
        // same JSON tree and must therefore be treated as duplicate copies.
        String output = "{\"toolResult\": " + objectMapper.writeValueAsString(toolResult)
                + ", \"metadata\": {\"emoji\": \"🔧\", \"quote\": \"\\\"\"}}";
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedJson = objectMapper.readValue(output, Map.class);
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("tool", "query_model");
        messagePayload.put("data", parsedJson);
        messagePayload.put("content", output);
        AgentMessage message = AgentMessage.builder()
                .messageId("langgraph-json-message")
                .sessionId("session-1")
                .taskId("task-1")
                .agentId("langgraph-biz-worker")
                .type(MessageType.TOOL_CALL_RESULT)
                .payload(messagePayload)
                .build();
        when(payloadRepository.findByMessageIdForUpdate("langgraph-json-message")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenReturn(new StoredSessionMessagePayload(
                "filesystem", "d".repeat(64) + ".gz", "gzip",
                output.getBytes(StandardCharsets.UTF_8).length, 12_345L, sha256(output)));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        ArgumentCaptor<SessionMessagePayload> payloadCaptor = ArgumentCaptor.forClass(SessionMessagePayload.class);
        verify(payloadStore).write(payloadCaptor.capture());
        assertEquals(output, new String(payloadCaptor.getValue().content(), StandardCharsets.UTF_8));
        Map<String, Object> payload = payload(message);
        assertTrue(payload.get("data") instanceof String,
                "the parsed JSON duplicate must not remain in durable metadata");
        assertEquals(payload.get("data"), payload.get("content"));
        assertTrue((Boolean) payload.get("dataTruncated"));
        assertTrue(serializedMetadataBytes(message, payload) <= 48L * 1024L);
    }

    @Test
    void routesOversizedStructuredToolDataAsCanonicalJsonWhenContentIsOnlyPresentation() throws Exception {
        stubPayloadBackend();
        Map<String, Object> structuredData = new LinkedHashMap<>();
        structuredData.put("escaped", "UTF-8 🔧 quote=\" slash=\\ newline=\n".repeat(8_000));
        structuredData.put("count", 8_000);
        String canonicalJson = objectMapper.writeValueAsString(structuredData);
        AgentMessage message = AgentMessageBuilder.create("session-1", "generic-provider")
                .taskId("task-1")
                .toolCallResult("tool-structured", "query", structuredData, true)
                .build();
        payload(message).put("content", "Query completed; 8,000 rows returned");
        message.setMessageId("structured-tool-message");
        when(payloadRepository.findByMessageIdForUpdate("structured-tool-message")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenReturn(new StoredSessionMessagePayload(
                "filesystem", "s".repeat(64) + ".gz", "gzip",
                canonicalJson.getBytes(StandardCharsets.UTF_8).length, 12_345L, sha256(canonicalJson)));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        ArgumentCaptor<SessionMessagePayload> payloadCaptor = ArgumentCaptor.forClass(SessionMessagePayload.class);
        verify(payloadStore).write(payloadCaptor.capture());
        assertEquals(canonicalJson, new String(payloadCaptor.getValue().content(), StandardCharsets.UTF_8));
        assertEquals("application/json; charset=utf-8", payloadCaptor.getValue().contentType());
        Map<String, Object> payload = payload(message);
        assertTrue(payload.get("data") instanceof String,
                "the original Map/List must not remain in session_messages.metadata");
        assertEquals("Query completed; 8,000 rows returned", payload.get("content"),
                "a presentation-only content field must not replace the authoritative data payload");
        assertTrue((Boolean) payload.get("dataTruncated"));
        assertEquals("application/json; charset=utf-8",
                ((Map<?, ?>) payload.get("payloadDescriptor")).get("contentType"));
        assertTrue(serializedMetadataBytes(message, payload) <= 48L * 1024L,
                "structured output must retain the BUG-021 metadata bound after JSON escaping");
    }

    @Test
    void storeFailureCreatesUnavailablePreviewAndDoesNotPropagate() throws Exception {
        stubPayloadBackend();
        String output = "store-failure-🔧\"\\".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-unavailable", output);
        when(payloadRepository.findByMessageIdForUpdate("message-unavailable")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenThrow(new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE", "disk is read-only"));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        ArgumentCaptor<SessionMessagePayloadEntity> descriptorCaptor =
                ArgumentCaptor.forClass(SessionMessagePayloadEntity.class);
        verify(payloadRepository).save(descriptorCaptor.capture());
        assertEquals(SessionMessagePayloadStatus.UNAVAILABLE, descriptorCaptor.getValue().getStatus());
        assertNull(descriptorCaptor.getValue().getStorageKey());
        Map<String, Object> payload = payload(message);
        assertEquals("session_message_payload_unavailable", payload.get("truncationReason"));
        assertEquals("UNAVAILABLE", ((Map<?, ?>) payload.get("payloadDescriptor")).get("status"));
        assertTrue(serializedMetadataBytes(message, payload) <= 48L * 1024L);
    }

    @Test
    void uncheckedPayloadStoreFailureAlsoCreatesUnavailablePreviewAndDoesNotPropagate() {
        stubPayloadBackend();
        String output = "unchecked-store-failure-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-unavailable-unchecked", output);
        when(payloadRepository.findByMessageIdForUpdate("message-unavailable-unchecked")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenThrow(new IllegalStateException("unexpected filesystem backend fault"));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        Map<String, Object> payload = payload(message);
        assertEquals("UNAVAILABLE", ((Map<?, ?>) payload.get("payloadDescriptor")).get("status"));
        assertEquals("session_message_payload_unavailable", payload.get("truncationReason"));
    }

    @Test
    void invalidStoreIntegrityReceiptAlsoCreatesUnavailablePreview() {
        stubPayloadBackend();
        String output = "integrity-failure-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-integrity-failure", output);
        when(payloadRepository.findByMessageIdForUpdate("message-integrity-failure")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenReturn(new StoredSessionMessagePayload(
                "filesystem", "z".repeat(64) + ".gz", "gzip", 1L, 1L, "0".repeat(64)));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.prepareForDurablePersistence(message);

        assertEquals("UNAVAILABLE", ((Map<?, ?>) payload(message).get("payloadDescriptor")).get("status"));
        assertEquals("session_message_payload_unavailable", payload(message).get("truncationReason"));
    }

    @Test
    void descriptorMysqlFailurePropagatesInsteadOfPretendingTheEventWasDurable() {
        stubPayloadBackend();
        String output = "mysql-failure-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-mysql-failure", output);
        when(payloadRepository.findByMessageIdForUpdate("message-mysql-failure")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenReturn(new StoredSessionMessagePayload(
                "filesystem", "c".repeat(64) + ".gz", "gzip",
                output.getBytes(StandardCharsets.UTF_8).length, 1L, sha256(output)));
        when(payloadRepository.save(any(SessionMessagePayloadEntity.class)))
                .thenThrow(new DataIntegrityViolationException("mysql unavailable"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.prepareForDurablePersistence(message));
    }

    @Test
    void replayReusesExistingDescriptorWithoutWritingAnotherFile() {
        String output = "replay-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-replay", output);
        SessionMessagePayloadEntity existing = readyDescriptor("message-replay", "session-1", output);
        when(payloadRepository.findByMessageIdForUpdate("message-replay")).thenReturn(Optional.of(existing));

        service.prepareForDurablePersistence(message);

        verify(payloadStore, never()).write(any());
        verify(payloadRepository, never()).save(any());
        Map<String, Object> payload = payload(message);
        assertEquals("READY", ((Map<?, ?>) payload.get("payloadDescriptor")).get("status"));
        assertTrue((Boolean) payload.get("dataTruncated"));
    }

    @Test
    void replayWithDifferentBytesForTheSameStableMessageIdIsRejected() {
        String original = "first-replay-🔧".repeat(8_000);
        String conflicting = "different-replay-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-replay-conflict", conflicting);
        when(payloadRepository.findByMessageIdForUpdate("message-replay-conflict")).thenReturn(Optional.of(
                readyDescriptor("message-replay-conflict", "session-1", original)));

        SessionMessagePayloadReplayConflictException conflict = assertThrows(
                SessionMessagePayloadReplayConflictException.class,
                () -> service.prepareForDurablePersistence(message));

        assertEquals(SessionMessagePayloadReplayConflictException.CODE, conflict.code());
        verify(payloadStore, never()).write(any());
        verify(payloadRepository, never()).save(any());
    }

    @Test
    void orphanedFileWithDifferentBytesForTheSameMessageIdIsRejectedInsteadOfAckableUnavailable() {
        stubPayloadBackend();
        String output = "orphan-replay-🔧".repeat(8_000);
        AgentMessage message = toolMessage(MessageType.TOOL_CALL_RESULT, "message-orphan-conflict", output);
        when(payloadRepository.findByMessageIdForUpdate("message-orphan-conflict")).thenReturn(Optional.empty());
        when(payloadStore.write(any())).thenThrow(new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH", "orphan payload has a different sha"));

        SessionMessagePayloadReplayConflictException conflict = assertThrows(
                SessionMessagePayloadReplayConflictException.class,
                () -> service.prepareForDurablePersistence(message));

        assertEquals(SessionMessagePayloadReplayConflictException.CODE, conflict.code());
        verify(payloadRepository, never()).save(any());
    }

    @Test
    void alsoRoutesLargeToolCallErrorsButLeavesFinalAssistantReplyUntouched() {
        String output = "tool-error-🔧".repeat(8_000);
        AgentMessage error = toolMessage(MessageType.TOOL_CALL_ERROR, "message-error", output);
        when(payloadRepository.findByMessageIdForUpdate("message-error")).thenReturn(Optional.of(
                readyDescriptor("message-error", "session-1", output)));

        service.prepareForDurablePersistence(error);

        assertTrue(payload(error).containsKey("payloadDescriptor"));
        String finalReply = "final assistant response \"🔧".repeat(8_000);
        AgentMessage result = AgentMessage.builder()
                .messageId("message-final")
                .sessionId("session-1")
                .agentId("codex-worker")
                .type(MessageType.SESSION_END)
                .payload(Map.of("content", finalReply, "isResult", true))
                .build();

        service.prepareForDurablePersistence(result);

        assertEquals(finalReply, payload(result).get("content"));
        assertFalse(payload(result).containsKey("payloadDescriptor"));
        verify(payloadStore, never()).write(any());
    }

    @Test
    void redactsBackendStorageKeysEvenWhenSmallToolOutputStaysInline() {
        AgentMessage message = AgentMessage.builder()
                .messageId("small-storage-key-message")
                .sessionId("session-1")
                .agentId("worker")
                .type(MessageType.TOOL_CALL_RESULT)
                .payload(Map.of(
                        "storageKey", "secret.gz",
                        "nested", Map.of("storage_key", "nested-secret.gz", "visible", "yes"),
                        "data", "small tool output"))
                .build();

        service.prepareForDurablePersistence(message);

        Map<?, ?> payload = payload(message);
        assertFalse(payload.containsKey("storageKey"));
        assertFalse(((Map<?, ?>) payload.get("nested")).containsKey("storage_key"));
        verifyNoInteractions(payloadRepository, payloadStore);
    }

    private AgentMessage toolMessage(MessageType type, String messageId, String output) {
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("tool", "shell");
        messagePayload.put("data", output);
        messagePayload.put("content", output);
        return AgentMessage.builder()
                .messageId(messageId)
                .sessionId("session-1")
                .taskId("task-1")
                .agentId("codex-worker")
                .type(type)
                .payload(messagePayload)
                .build();
    }

    private void stubPayloadBackend() {
        when(payloadStore.backend()).thenReturn("filesystem");
    }

    private SessionMessagePayloadEntity readyDescriptor(String messageId, String sessionId, String output) {
        SessionMessagePayloadEntity descriptor = new SessionMessagePayloadEntity();
        descriptor.setId("payload-" + messageId);
        descriptor.setMessageId(messageId);
        descriptor.setSessionId(sessionId);
        descriptor.setBackend("filesystem");
        descriptor.setStorageKey("e".repeat(64) + ".gz");
        descriptor.setContentType("text/plain; charset=utf-8");
        descriptor.setContentEncoding("gzip");
        descriptor.setOriginalBytes((long) output.getBytes(StandardCharsets.UTF_8).length);
        descriptor.setStoredBytes(12_345L);
        descriptor.setSha256(sha256(output));
        descriptor.setStatus(SessionMessagePayloadStatus.READY);
        return descriptor;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(AgentMessage message) {
        return (Map<String, Object>) message.getPayload();
    }

    private long serializedMetadataBytes(AgentMessage message, Map<String, Object> payload) throws Exception {
        Map<String, Object> metadata = new LinkedHashMap<>(payload);
        metadata.put("type", message.getType().name());
        metadata.put("agentId", message.getAgentId());
        return objectMapper.writeValueAsBytes(metadata).length;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
