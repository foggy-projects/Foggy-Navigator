package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretMaskerTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void structuredJsonRedactionPreservesTypesAndRedactsEverySensitiveValueShape() throws Exception {
        JsonNode input = OBJECT_MAPPER.readTree("""
                {
                  "clientToken":"sensitive-string-value",
                  "enabled_SECRET":true,
                  "disabledCredential":false,
                  "retryApiKey":987654321,
                  "optional_token":null,
                  "scopeToken":["array-sensitive-value",7],
                  "authCredential":{"nested":"object-sensitive-value"},
                  "ordinaryText":"prefix known-sensitive-value suffix",
                  "ordinaryBoolean":false,
                  "ordinaryNumber":73,
                  "ordinaryNull":null,
                  "ordinaryArray":[true,73,null,{"visible":"value"}],
                  "nested":{
                    "workerSecret":"nested-sensitive-value",
                    "alreadyToken":"[REDACTED]",
                    "ordinaryRedacted":"[REDACTED]"
                  }
                }
                """);

        JsonNode redacted = SecretMasker.redactJson(
                OBJECT_MAPPER,
                input,
                text -> SecretMasker.redactKnownSecrets(text, List.of("known-sensitive-value")));
        String output = OBJECT_MAPPER.writeValueAsString(redacted);
        JsonNode reparsed = OBJECT_MAPPER.readTree(output);

        for (String field : List.of(
                "clientToken",
                "enabled_SECRET",
                "disabledCredential",
                "retryApiKey",
                "optional_token",
                "scopeToken",
                "authCredential")) {
            assertEquals("[REDACTED]", reparsed.get(field).textValue());
        }
        assertEquals("[REDACTED]", reparsed.path("nested").path("workerSecret").textValue());
        assertEquals("[REDACTED]", reparsed.path("nested").path("alreadyToken").textValue());
        assertEquals("[REDACTED]", reparsed.path("nested").path("ordinaryRedacted").textValue());
        assertEquals("prefix [REDACTED] suffix", reparsed.path("ordinaryText").textValue());
        assertTrue(reparsed.path("ordinaryBoolean").isBoolean());
        assertFalse(reparsed.path("ordinaryBoolean").booleanValue());
        assertTrue(reparsed.path("ordinaryNumber").isNumber());
        assertEquals(73, reparsed.path("ordinaryNumber").intValue());
        assertTrue(reparsed.path("ordinaryNull").isNull());
        assertTrue(reparsed.path("ordinaryArray").isArray());
        assertFalse(output.contains("sensitive-string-value"));
        assertFalse(output.contains("987654321"));
        assertFalse(output.contains("array-sensitive-value"));
        assertFalse(output.contains("object-sensitive-value"));
        assertFalse(output.contains("nested-sensitive-value"));
        assertFalse(output.contains("known-sensitive-value"));
    }

    @Test
    void knownSecretLiteralsOnlyRedactTextNodesAndNeverCorruptJsonPrimitives() throws Exception {
        JsonNode input = OBJECT_MAPPER.readTree("""
                {
                  "enabled":true,
                  "disabled":false,
                  "count":73,
                  "missing":null,
                  "text":"true false 73 null",
                  "items":[true,false,73,null]
                }
                """);

        JsonNode redacted = SecretMasker.redactJson(
                OBJECT_MAPPER,
                input,
                text -> SecretMasker.redactKnownSecrets(
                        text, List.of("true", "false", "73", "null")));
        String output = OBJECT_MAPPER.writeValueAsString(redacted);
        JsonNode reparsed = OBJECT_MAPPER.readTree(output);

        assertTrue(reparsed.path("enabled").isBoolean());
        assertTrue(reparsed.path("enabled").booleanValue());
        assertTrue(reparsed.path("disabled").isBoolean());
        assertFalse(reparsed.path("disabled").booleanValue());
        assertTrue(reparsed.path("count").isNumber());
        assertEquals(73, reparsed.path("count").intValue());
        assertTrue(reparsed.path("missing").isNull());
        assertEquals("[REDACTED] [REDACTED] [REDACTED] [REDACTED]",
                reparsed.path("text").textValue());
        assertTrue(reparsed.path("items").get(0).isBoolean());
        assertTrue(reparsed.path("items").get(1).isBoolean());
        assertTrue(reparsed.path("items").get(2).isNumber());
        assertTrue(reparsed.path("items").get(3).isNull());
    }
}
