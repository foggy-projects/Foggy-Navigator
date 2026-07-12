package com.foggy.navigator.session.service.payload;

import java.util.Arrays;
import java.util.Objects;

/** Complete, untruncated content to be persisted outside the session_messages row. */
public record SessionMessagePayload(
    String messageId,
    String sessionId,
    String contentType,
    byte[] content
) {

    public SessionMessagePayload {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
