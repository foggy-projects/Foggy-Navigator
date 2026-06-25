package com.foggy.navigator.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderStateCodecTest {

    @Test
    void mergeSessionValue_stampsSchemaAndProviderWhileKeepingUnknownFields() {
        String json = ProviderStateCodec.mergeSessionValue(
                "{\"custom\":\"keep\"}",
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderStateCodec.FIELD_CODEX_THREAD_ID,
                "thread-1");

        Map<String, Object> state = ProviderStateCodec.parseObject(json);
        assertEquals(ProviderStateCodec.CURRENT_SCHEMA_VERSION, state.get(ProviderStateCodec.FIELD_SCHEMA_VERSION));
        assertEquals(ProviderRouteRegistry.PROVIDER_CODEX_WORKER, state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        assertEquals("thread-1", state.get(ProviderStateCodec.FIELD_CODEX_THREAD_ID));
        assertEquals("keep", state.get("custom"));
    }

    @Test
    void mergeTaskValues_preservesNestedCheckpointPayload() {
        String json = ProviderStateCodec.mergeTaskValues(
                null,
                ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER,
                Map.of(
                        ProviderStateCodec.FIELD_CLAUDE_SESSION_ID, "claude-session-1",
                        ProviderStateCodec.FIELD_CHECKPOINTS, List.of(Map.of("id", "ckpt-1"))
                ));

        Map<String, Object> state = ProviderStateCodec.parseObject(json);
        assertEquals(ProviderRouteRegistry.PROVIDER_CLAUDE_WORKER, state.get(ProviderStateCodec.FIELD_PROVIDER_TYPE));
        assertEquals("claude-session-1", state.get(ProviderStateCodec.FIELD_CLAUDE_SESSION_ID));
        assertTrue(state.get(ProviderStateCodec.FIELD_CHECKPOINTS) instanceof List<?>);
    }

    @Test
    void mergeTaskValue_removesBlankValueAndReturnsNullWhenNoPayloadFieldsRemain() {
        String json = ProviderStateCodec.mergeTaskValue(
                "{\"codexThreadId\":\"thread-1\"}",
                ProviderRouteRegistry.PROVIDER_CODEX_WORKER,
                ProviderStateCodec.FIELD_CODEX_THREAD_ID,
                " ");

        assertNull(json);
    }

    @Test
    void readString_supportsLegacyJsonWithoutSchemaVersion() {
        assertEquals("gemini-session-1", ProviderStateCodec.readStringOrNull(
                "{\"geminiSessionId\":\"gemini-session-1\"}",
                ProviderStateCodec.FIELD_GEMINI_SESSION_ID));
        assertFalse(ProviderStateCodec.readString("{broken", ProviderStateCodec.FIELD_GEMINI_SESSION_ID).isPresent());
    }

    @Test
    void parseObject_returnsEmptyMapForBlankOrInvalidJson() {
        assertTrue(ProviderStateCodec.parseObject(null).isEmpty());
        assertTrue(ProviderStateCodec.parseObject(" ").isEmpty());
        assertTrue(ProviderStateCodec.parseObject("{broken").isEmpty());
    }
}
