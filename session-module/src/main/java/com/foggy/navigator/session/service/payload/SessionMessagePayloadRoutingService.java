package com.foggy.navigator.session.service.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.common.entity.SessionMessagePayloadEntity;
import com.foggy.navigator.common.entity.SessionMessagePayloadStatus;
import com.foggy.navigator.common.repository.SessionMessagePayloadRepository;
import com.foggy.navigator.session.service.SessionMessagePublicPayloadSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Routes oversized tool output into the payload store before a session message
 * is mapped to MySQL metadata. It is intentionally provider-neutral: Codex,
 * Claude, Gemini, LangGraph, and core AgentInvoker events all pass through the
 * same SessionEventListener boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMessagePayloadRoutingService {

    /** BUG-021 compatibility limit for serialized metadata, including type and agentId. */
    public static final long DEFAULT_INLINE_PREVIEW_BYTES = 48L * 1024L;

    private static final String TEXT_CONTENT_TYPE = "text/plain; charset=utf-8";
    private static final String JSON_CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String LEGACY_TRUNCATION_REASON = "session_message_metadata_limit";
    private static final String PAYLOAD_STORE_TRUNCATION_REASON = "session_message_payload_store";
    private static final String PAYLOAD_UNAVAILABLE_TRUNCATION_REASON = "session_message_payload_unavailable";

    private final SessionMessagePayloadRepository payloadRepository;
    private final SessionMessagePayloadStore payloadStore;
    private final SessionMessagePayloadProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * Mutates the event payload only when a textual tool result would exceed
     * the bounded durable-metadata contract. Store faults become an
     * {@code UNAVAILABLE} descriptor; descriptor/MySQL faults deliberately
     * propagate to the durable relay and therefore prevent its ACK.
     */
    public void prepareForDurablePersistence(AgentMessage message) {
        if (!isToolOutput(message) || !(message.getPayload() instanceof Map<?, ?> rawPayload)) {
            return;
        }
        Map<String, Object> payload = SessionMessagePublicPayloadSanitizer.redactInternalStorageKeys(rawPayload);
        // Direct callers of the routing service must be just as safe as the
        // SessionEventListener SSE boundary, including small inline payloads.
        message.setPayload(payload);
        ToolOutput toolOutput = resolveToolOutput(payload);
        if (toolOutput == null) {
            return;
        }
        String output = toolOutput.value();

        if (serializedSessionMetadataBytes(message, payload) <= properties.getInlinePreviewBytes()) {
            return;
        }

        byte[] originalBytes = output.getBytes(StandardCharsets.UTF_8);
        SessionMessagePayloadEntity descriptor = null;
        if (properties.isEnabled() && hasStableIdentity(message)) {
            // Called inside SessionMessageDurablePersistenceCoordinator's
            // transaction. The pessimistic lookup serializes shared-store
            // replays before either node can create a payload object.
            Optional<SessionMessagePayloadEntity> existing = payloadRepository
                    .findByMessageIdForUpdate(message.getMessageId());
            if (existing.isPresent()) {
                descriptor = existing.get();
                verifyReplayIdentity(descriptor, message, originalBytes, toolOutput.contentType());
            } else {
                descriptor = createDescriptor(message, originalBytes, toolOutput.contentType());
            }
            applyPublicDescriptor(payload, descriptor);
        }

        boundPreview(message, payload, toolOutput, originalBytes.length, descriptor);
        message.setPayload(payload);
    }

    /**
     * The common tool-result contract makes {@code data} authoritative. A
     * textual {@code content} field is used only when data is absent, so a
     * short presentation label can never replace a large structured result.
     * LangGraph preserves original JSON text in content while parsing data into
     * a Map/List; when those two representations are equal, both copies are
     * replaced by the same bounded preview.
     */
    private ToolOutput resolveToolOutput(Map<?, ?> rawPayload) {
        Object rawData = rawPayload.get("data");
        Object rawContent = rawPayload.get("content");
        if (rawData instanceof String data) {
            return new ToolOutput(data, true, Objects.equals(rawContent, data), TEXT_CONTENT_TYPE);
        }
        if (rawData != null) {
            try {
                String canonicalJson = objectMapper.writeValueAsString(rawData);
                if (rawContent instanceof String content && isJsonDuplicate(content, rawData, canonicalJson)) {
                    // LangGraph keeps the original JSON text in content and a
                    // parsed equivalent in data. Preserve the source text as
                    // the complete Payload even when Python/Jackson differ in
                    // insignificant whitespace, then bound both metadata
                    // copies together.
                    return new ToolOutput(content, true, true, JSON_CONTENT_TYPE);
                }
                return new ToolOutput(canonicalJson, true, false, JSON_CONTENT_TYPE);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unable to serialize structured tool output", e);
            }
        }
        if (rawContent instanceof String content) {
            return new ToolOutput(content, false, true, TEXT_CONTENT_TYPE);
        }
        return null;
    }

    private boolean isJsonDuplicate(String rawContent, Object rawData, String canonicalJson) {
        if (Objects.equals(rawContent, canonicalJson)) {
            return true;
        }
        try {
            return objectMapper.readTree(rawContent).equals(objectMapper.valueToTree(rawData));
        } catch (JsonProcessingException | IllegalArgumentException ignored) {
            // content is allowed to be a separate presentation field. In that
            // case data remains authoritative and only data is externalized.
            return false;
        }
    }

    private SessionMessagePayloadEntity createDescriptor(AgentMessage message, byte[] originalBytes,
                                                         String contentType) {
        String sha256 = sha256(originalBytes);
        SessionMessagePayloadEntity descriptor = baseDescriptor(message, originalBytes.length, sha256, contentType);
        if (originalBytes.length > properties.getMaxPayloadBytes()) {
            descriptor.setStatus(SessionMessagePayloadStatus.UNAVAILABLE);
            log.warn("Session payload exceeds configured maximum; keeping bounded preview: "
                            + "sessionId={}, messageId={}, originalBytes={}, maxPayloadBytes={}",
                    message.getSessionId(), message.getMessageId(), originalBytes.length,
                    properties.getMaxPayloadBytes());
            return payloadRepository.save(descriptor);
        }

        try {
            StoredSessionMessagePayload stored = payloadStore.write(new SessionMessagePayload(
                    message.getMessageId(), message.getSessionId(), contentType, originalBytes));
            verifyStoredPayload(stored, originalBytes.length, sha256);
            descriptor.setBackend(stored.backend());
            descriptor.setStorageKey(stored.storageKey());
            descriptor.setContentEncoding(stored.contentEncoding());
            descriptor.setStoredBytes(stored.storedBytes());
            descriptor.setSha256(stored.sha256());
            descriptor.setStatus(SessionMessagePayloadStatus.READY);
        } catch (SessionMessagePayloadStoreException e) {
            if (isReplayIntegrityConflict(e)) {
                // The file can be a reusable orphan left by a failed MySQL
                // transaction. A same-id event with different bytes must not
                // turn that conflict into an ACKable UNAVAILABLE descriptor.
                throw new SessionMessagePayloadReplayConflictException(
                        "Stable session message id " + message.getMessageId()
                                + " conflicts with an existing payload object", e);
            }
            markPayloadUnavailable(descriptor, message, originalBytes.length, e);
        } catch (RuntimeException e) {
            // No durable retry source remains after the event is ACKed. Make
            // that loss explicit while allowing the worker stream to converge.
            // Repository work is outside this catch, so a MySQL descriptor
            // failure still propagates and prevents the upstream ACK.
            markPayloadUnavailable(descriptor, message, originalBytes.length, e);
        }
        // Descriptor persistence is deliberately outside the Store-failure
        // fallback. Any MySQL failure must propagate to the durable relay so
        // the provider event is not ACKed.
        return payloadRepository.save(descriptor);
    }

    private boolean isReplayIntegrityConflict(SessionMessagePayloadStoreException exception) {
        return "SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH".equals(exception.code());
    }

    private void markPayloadUnavailable(SessionMessagePayloadEntity descriptor, AgentMessage message,
                                        int originalBytes, RuntimeException failure) {
        // No durable retry source remains after the event is ACKed. Make that
        // loss explicit while allowing the worker stream to converge.
        // Repository work is outside this boundary, so a MySQL descriptor
        // failure still propagates and prevents the upstream ACK.
        descriptor.setStatus(SessionMessagePayloadStatus.UNAVAILABLE);
        log.warn("Session payload store unavailable; keeping bounded preview: "
                        + "sessionId={}, messageId={}, originalBytes={}, failureType={}",
                message.getSessionId(), message.getMessageId(), originalBytes,
                failure instanceof SessionMessagePayloadStoreException storeFailure
                        ? storeFailure.code() : failure.getClass().getSimpleName());
    }

    private void verifyStoredPayload(StoredSessionMessagePayload stored, int originalBytes, String expectedSha256) {
        if (stored == null
                || !Objects.equals(payloadStore.backend(), stored.backend())
                || !hasText(stored.storageKey())
                || !hasText(stored.contentEncoding())
                || stored.originalBytes() != originalBytes
                || stored.storedBytes() < 0
                || !Objects.equals(expectedSha256, stored.sha256())) {
            throw new SessionMessagePayloadStoreException(
                    "SESSION_MESSAGE_PAYLOAD_STORE_INTEGRITY_MISMATCH",
                    "Session message payload store returned an invalid durability descriptor"
            );
        }
    }

    private SessionMessagePayloadEntity baseDescriptor(AgentMessage message, int originalBytes, String sha256,
                                                       String contentType) {
        SessionMessagePayloadEntity descriptor = new SessionMessagePayloadEntity();
        descriptor.setId(payloadId(message.getMessageId()));
        descriptor.setMessageId(message.getMessageId());
        descriptor.setSessionId(message.getSessionId());
        descriptor.setBackend(payloadStore.backend());
        descriptor.setContentType(contentType);
        descriptor.setContentEncoding("gzip");
        descriptor.setOriginalBytes((long) originalBytes);
        descriptor.setSha256(sha256);
        descriptor.setExpiresAt(expiresAt());
        return descriptor;
    }

    /**
     * A message id is the immutable payload identity used by SSE replay. Never
     * reuse its descriptor for different bytes: doing so would keep the first
     * stored object but send a preview of unrelated content. Throwing reaches
     * the durable relay and deliberately withholds its upstream ACK.
     */
    private void verifyReplayIdentity(SessionMessagePayloadEntity descriptor, AgentMessage message,
                                      byte[] originalBytes, String contentType) {
        String sha256 = sha256(originalBytes);
        boolean matches = Objects.equals(descriptor.getSessionId(), message.getSessionId())
                && Objects.equals(descriptor.getOriginalBytes(), (long) originalBytes.length)
                && Objects.equals(descriptor.getSha256(), sha256)
                && Objects.equals(descriptor.getContentType(), contentType);
        if (!matches) {
            throw new SessionMessagePayloadReplayConflictException(
                    "Stable session message id " + message.getMessageId()
                            + " was replayed with different payload bytes or context");
        }
    }

    private void applyPublicDescriptor(Map<String, Object> payload, SessionMessagePayloadEntity descriptor) {
        Map<String, Object> publicDescriptor = new LinkedHashMap<>();
        publicDescriptor.put("payloadId", descriptor.getId());
        publicDescriptor.put("status", descriptor.getStatus().name());
        publicDescriptor.put("contentType", descriptor.getContentType());
        publicDescriptor.put("contentEncoding", descriptor.getContentEncoding());
        publicDescriptor.put("originalBytes", descriptor.getOriginalBytes());
        publicDescriptor.put("storedBytes", descriptor.getStoredBytes());
        publicDescriptor.put("sha256", descriptor.getSha256());
        publicDescriptor.put("expiresAt", descriptor.getExpiresAt() != null
                ? descriptor.getExpiresAt().toString() : null);
        // This is the public descriptor schema version, not the JPA optimistic-lock value.
        publicDescriptor.put("version", 1);
        payload.put("payloadDescriptor", publicDescriptor);
    }

    private void boundPreview(AgentMessage message, Map<String, Object> payload, ToolOutput toolOutput,
                              int originalDataBytes, SessionMessagePayloadEntity descriptor) {
        String output = toolOutput.value();
        payload.put("dataTruncated", true);
        payload.put("originalDataBytes", originalDataBytes);
        payload.put("truncationReason", truncationReason(descriptor));

        String notice = truncationNotice(message, descriptor, originalDataBytes);
        int codePointCount = output.codePointCount(0, output.length());
        int low = 0;
        // One code point occupies at least one UTF-8 byte, so any candidate
        // above the metadata budget cannot fit. Cap the binary-search input
        // before constructing strings to avoid repeatedly serializing a
        // multi-megabyte/64 MiB payload merely to prove it is too large.
        int maximumCandidateCodePoints = (int) Math.min(
                properties.getInlinePreviewBytes(), (long) Integer.MAX_VALUE);
        int high = Math.min(codePointCount, Math.max(0, maximumCandidateCodePoints));
        String boundedData = notice;

        while (low <= high) {
            int retainedCodePoints = low + (high - low) / 2;
            String candidate = retainOutputEdges(output, retainedCodePoints, notice);
            if (toolOutput.replaceData()) {
                payload.put("data", candidate);
            }
            if (toolOutput.replaceContent()) {
                payload.put("content", candidate);
            }
            if (serializedSessionMetadataBytes(message, payload) <= properties.getInlinePreviewBytes()) {
                boundedData = candidate;
                low = retainedCodePoints + 1;
            } else {
                high = retainedCodePoints - 1;
            }
        }

        if (toolOutput.replaceData()) {
            payload.put("data", boundedData);
        }
        // LangGraph-compatible events carry parsed JSON in data and the same
        // JSON text in content. Both copies become the same bounded preview.
        if (toolOutput.replaceContent()) {
            payload.put("content", boundedData);
        }
        long metadataBytes = serializedSessionMetadataBytes(message, payload);
        if (metadataBytes > properties.getInlinePreviewBytes()) {
            throw new IllegalStateException("SESSION_MESSAGE_PAYLOAD_PREVIEW_OVERHEAD_EXCEEDS_LIMIT");
        }
        log.warn("Bound oversized tool output before durable session persistence: "
                        + "sessionId={}, messageId={}, originalBytes={}, metadataBytes={}, payloadStatus={}",
                message.getSessionId(), message.getMessageId(), originalDataBytes, metadataBytes,
                descriptor != null ? descriptor.getStatus() : "INLINE_FALLBACK");
    }

    private String truncationReason(SessionMessagePayloadEntity descriptor) {
        if (descriptor == null) {
            return LEGACY_TRUNCATION_REASON;
        }
        return descriptor.getStatus() == SessionMessagePayloadStatus.READY
                ? PAYLOAD_STORE_TRUNCATION_REASON
                : PAYLOAD_UNAVAILABLE_TRUNCATION_REASON;
    }

    private String truncationNotice(AgentMessage message, SessionMessagePayloadEntity descriptor, int originalDataBytes) {
        String label = message.getAgentId() != null && message.getAgentId().startsWith("codex")
                ? "Codex tool output" : "Tool output";
        String availability;
        if (descriptor == null) {
            availability = "full output remains in the Worker event log";
        } else if (descriptor.getStatus() == SessionMessagePayloadStatus.READY) {
            availability = "full output is available from session payload storage";
        } else {
            availability = "full output is unavailable because payload storage failed";
        }
        return "\n\n[" + label + " truncated; " + availability
                + "; original UTF-8 bytes: " + originalDataBytes + "]\n\n";
    }

    private long serializedSessionMetadataBytes(AgentMessage message, Map<String, Object> payload) {
        Map<String, Object> metadata = new LinkedHashMap<>(payload);
        metadata.put("type", message.getType().name());
        metadata.put("agentId", message.getAgentId());
        try {
            return objectMapper.writeValueAsBytes(metadata).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize session message metadata", e);
        }
    }

    private boolean isToolOutput(AgentMessage message) {
        return message != null && (message.getType() == MessageType.TOOL_CALL_RESULT
                || message.getType() == MessageType.TOOL_CALL_ERROR);
    }

    private boolean hasStableIdentity(AgentMessage message) {
        return hasText(message.getMessageId()) && hasText(message.getSessionId());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String retainOutputEdges(String output, int retainedCodePoints, String notice) {
        int codePointCount = output.codePointCount(0, output.length());
        if (retainedCodePoints >= codePointCount) {
            return output;
        }
        int headCodePoints = (retainedCodePoints + 1) / 2;
        int tailCodePoints = retainedCodePoints - headCodePoints;
        int headEnd = output.offsetByCodePoints(0, headCodePoints);
        int tailStart = output.offsetByCodePoints(output.length(), -tailCodePoints);
        return output.substring(0, headEnd) + notice + output.substring(tailStart);
    }

    private LocalDateTime expiresAt() {
        Duration retention = properties.getRetention();
        return retention != null && !retention.isZero() && !retention.isNegative()
                ? LocalDateTime.now().plus(retention) : null;
    }

    private String payloadId(String messageId) {
        return sha256("payload:" + messageId).substring(0, 64);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private record ToolOutput(String value, boolean replaceData, boolean replaceContent, String contentType) {
    }
}
