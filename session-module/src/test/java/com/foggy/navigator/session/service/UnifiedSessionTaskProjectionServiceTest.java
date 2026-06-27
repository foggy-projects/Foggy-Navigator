package com.foggy.navigator.session.service;

import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskPageResult;
import com.foggy.navigator.spi.agent.TaskSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void taskListingProviderTypedMethodsAdaptLegacyEnvelopes() {
        TaskListingProvider provider = new TaskListingProvider() {
            @Override
            public String getProviderType() {
                return "legacy";
            }

            @Override
            public Object listTasksPaged(String userId, int page, int size, String state) {
                return Map.of(
                        "content", List.of(Map.of("taskId", "task-legacy")),
                        "totalSessions", "2");
            }

            @Override
            public Object searchSessions(String userId, String keyword, String workerId,
                                         String directoryId, int page, int size) {
                return new LegacySearchPage(List.of(Map.of("sessionId", "session-legacy")), 4L);
            }
        };

        TaskPageResult page = provider.listTaskPage("user-1", 1, 10, null);
        TaskSearchResult search = provider.searchSessionPage("user-1", "auth", null, null, 2, 5);

        assertEquals(2L, page.totalSessions());
        assertEquals(1, page.page());
        assertEquals(10, page.size());
        assertEquals("task-legacy", assertInstanceOf(Map.class, page.content().get(0)).get("taskId"));
        assertEquals(4L, search.total());
        assertEquals(2, search.page());
        assertEquals(5, search.size());
        assertEquals("session-legacy", assertInstanceOf(Map.class, search.results().get(0)).get("sessionId"));
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
