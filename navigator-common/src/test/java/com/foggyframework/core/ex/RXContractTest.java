package com.foggyframework.core.ex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RXContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void okPreservesSuccessEnvelopeAndOmitsNullFields() throws Exception {
        RX<Void> response = RX.ok();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

        assertEquals(200, response.getCode());
        assertTrue(response.isOk());
        assertEquals(200, json.path("code").asInt());
        assertFalse(json.has("data"));
        assertFalse(json.has("msg"));
        assertFalse(json.has("message"));
        assertFalse(json.has("exCode"));
        assertFalse(json.has("ok"));
    }

    @Test
    void okWithDataPreservesPayload() throws Exception {
        RX<String> response = RX.ok("payload");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

        assertEquals("payload", response.getData());
        assertEquals("payload", json.path("data").asText());
    }

    @Test
    void userAndBusinessFailuresKeepLegacyCodes() {
        RX<Void> userFailure = RX.failA("invalid request");
        RX<Void> businessFailure = RX.failB("operation failed");

        assertEquals(600, userFailure.getCode());
        assertEquals("A600", userFailure.getExCode());
        assertEquals("invalid request", userFailure.getMsg());
        assertFalse(userFailure.isOk());

        assertEquals(600, businessFailure.getCode());
        assertEquals("B600", businessFailure.getExCode());
        assertEquals("operation failed", businessFailure.getMsg());
        assertFalse(businessFailure.isOk());
        assertEquals("B600", RX.error("operation failed").getExCode());
    }

    @Test
    void throwBPreservesExceptionAndHandlerEnvelopeContract() {
        ExRuntimeExceptionImpl exception = assertThrows(
                ExRuntimeExceptionImpl.class,
                () -> {
                    throw RX.throwB("operation failed");
                });

        assertEquals(600, exception.getCode());
        assertEquals("B600", exception.getExCode());
        assertEquals("operation failed", exception.getMessage());

        RX<Void> response = exception.toR();
        assertEquals(600, response.getCode());
        assertEquals("B600", response.getExCode());
        assertEquals("operation failed", response.getMsg());
        assertNull(response.getData());
    }
}
