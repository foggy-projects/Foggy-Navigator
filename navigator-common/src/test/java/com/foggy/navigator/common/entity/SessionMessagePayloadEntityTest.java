package com.foggy.navigator.common.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMessagePayloadEntityTest {

    @Test
    void neverSerializesBackendPrivateStorageKey() throws Exception {
        SessionMessagePayloadEntity descriptor = new SessionMessagePayloadEntity();
        descriptor.setId("payload-1");
        descriptor.setMessageId("message-1");
        descriptor.setStorageKey("backend-private-object.gz");

        String json = new ObjectMapper().writeValueAsString(descriptor);

        assertFalse(json.contains("storageKey"));
        assertFalse(json.contains("backend-private-object.gz"));
        assertTrue(json.contains("messageId"));
    }
}
