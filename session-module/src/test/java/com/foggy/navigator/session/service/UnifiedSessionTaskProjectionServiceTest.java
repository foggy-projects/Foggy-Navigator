package com.foggy.navigator.session.service;

import com.foggy.navigator.common.entity.SessionTaskEntity;
import com.foggy.navigator.common.util.ProviderStateCodec;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class UnifiedSessionTaskProjectionServiceTest {

    private final UnifiedSessionTaskProjectionService service =
            new UnifiedSessionTaskProjectionService(mock(SessionRepository.class), null);

    @Test
    void toTaskPageEnvelope_readsTypedProviderEnvelope() {
        Map<String, Object> task = Map.of("taskId", "task-1");

        UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope =
                service.toTaskPageEnvelope(TaskPageResult.of(List.of(task), 7L, 1, 20));

        assertEquals(7L, envelope.totalSessions());
        assertEquals("task-1", assertInstanceOf(Map.class, envelope.content().get(0)).get("taskId"));
    }

    @Test
    void toSearchEnvelope_readsTypedProviderEnvelope() {
        Map<String, Object> result = Map.of("sessionId", "session-1");

        UnifiedSessionTaskProjectionService.SearchEnvelope envelope =
                service.toSearchEnvelope(TaskSearchResult.of(List.of(result), 3L, 0, 10));

        assertEquals(3L, envelope.total());
        assertEquals("session-1", assertInstanceOf(Map.class, envelope.results().get(0)).get("sessionId"));
    }

    @Test
    void toTaskPageEnvelope_keepsLegacyMapFallback() {
        Map<String, Object> task = Map.of("taskId", "task-legacy");
        Map<String, Object> legacy = Map.of("content", List.of(task), "totalSessions", "2");

        UnifiedSessionTaskProjectionService.TaskPageEnvelope envelope = service.toTaskPageEnvelope(legacy);

        assertEquals(2L, envelope.totalSessions());
        assertEquals("task-legacy", assertInstanceOf(Map.class, envelope.content().get(0)).get("taskId"));
    }

    @Test
    void toSearchEnvelope_keepsLegacyBeanFallback() {
        LegacySearchPage legacy = new LegacySearchPage(List.of(Map.of("sessionId", "session-legacy")), 4L);

        UnifiedSessionTaskProjectionService.SearchEnvelope envelope = service.toSearchEnvelope(legacy);

        assertEquals(4L, envelope.total());
        assertEquals("session-legacy", assertInstanceOf(Map.class, envelope.results().get(0)).get("sessionId"));
    }

    @Test
    void runtimeProjectionRejectsFractionalAndOutOfRangeNumbers() {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId("task-invalid-runtime-numbers");
        entity.setSessionId("session-invalid-runtime-numbers");
        entity.setTaskStateJson("{\"" + ProviderStateCodec.FIELD_CODEX_RUNTIME_REVISION
                + "\":1.5,\"" + ProviderStateCodec.FIELD_CODEX_ROUTING_EPOCH
                + "\":9223372036854775808}");

        var projected = service.toDispatchTaskDTO(entity);

        assertNull(projected.getRuntimeRevision());
        assertNull(projected.getRoutingEpoch());
    }

    @Test
    void taskProjectionReadsAuthoritativeCreationEpochFromVersionedState() {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId("task-created-epoch");
        entity.setSessionId("session-created-epoch");
        entity.setTaskStateJson("{\"" + ProviderStateCodec.FIELD_SCHEMA_VERSION + "\":"
                + ProviderStateCodec.CURRENT_SCHEMA_VERSION + ",\""
                + ProviderStateCodec.FIELD_CREATED_AT_EPOCH_MS + "\":1783685415123}");

        var projected = service.toDispatchTaskDTO(entity);

        assertEquals(1_783_685_415_123L, projected.getCreatedAtEpochMs());
    }

    @Test
    void taskProjectionReadsStructuredOutputFromVersionedProviderState() {
        SessionTaskEntity entity = new SessionTaskEntity();
        entity.setTaskId("task-structured-output");
        entity.setSessionId("session-structured-output");
        entity.setTaskStateJson(ProviderStateCodec.mergeTaskValue(
                null,
                "langgraph-biz-worker",
                ProviderStateCodec.FIELD_STRUCTURED_OUTPUT,
                "{\"type\":\"OPEN_ARTIFACT\"}"));

        var projected = service.toDispatchTaskDTO(entity);

        assertEquals("{\"type\":\"OPEN_ARTIFACT\"}", projected.getStructuredOutput());
    }

    public static class LegacySearchPage {
        private final List<Object> results;
        private final long total;

        private LegacySearchPage(List<Object> results, long total) {
            this.results = results;
            this.total = total;
        }

        public List<Object> getResults() {
            return results;
        }

        public long getTotal() {
            return total;
        }
    }
}
