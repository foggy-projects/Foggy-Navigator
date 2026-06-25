package com.foggy.navigator.session.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedSseEmitterTest {

    private UnifiedSseEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new UnifiedSseEmitter(new ObjectMapper(), null);
    }

    @AfterEach
    void tearDown() {
        emitter.destroy();
    }

    // ========== subscribe / unsubscribe ==========

    @Test
    void subscribe_addsToMappings() {
        emitter.subscribe("user1", "session1");
        emitter.subscribe("user1", "session2");

        Set<String> subs = emitter.getSubscriptions("user1");
        assertEquals(2, subs.size());
        assertTrue(subs.contains("session1"));
        assertTrue(subs.contains("session2"));
    }

    @Test
    void unsubscribe_removesFromMappings() {
        emitter.subscribe("user1", "session1");
        emitter.subscribe("user1", "session2");

        emitter.unsubscribe("user1", "session1");

        Set<String> subs = emitter.getSubscriptions("user1");
        assertEquals(1, subs.size());
        assertTrue(subs.contains("session2"));
    }

    @Test
    void unsubscribe_lastSession_cleansUpUser() {
        emitter.subscribe("user1", "session1");
        emitter.unsubscribe("user1", "session1");

        Set<String> subs = emitter.getSubscriptions("user1");
        assertTrue(subs.isEmpty());
    }

    @Test
    void subscribe_multipleUsers_sameSession() {
        emitter.subscribe("user1", "session1");
        emitter.subscribe("user2", "session1");

        assertTrue(emitter.getSubscriptions("user1").contains("session1"));
        assertTrue(emitter.getSubscriptions("user2").contains("session1"));
    }

    @Test
    void getSubscriptions_noSubscriptions_returnsEmptySet() {
        Set<String> subs = emitter.getSubscriptions("nonexistent");
        assertNotNull(subs);
        assertTrue(subs.isEmpty());
    }

    // ========== sendSessionEvent ==========

    @Test
    void sendSessionEvent_noSubscribers_doesNotThrow() {
        AgentMessage message = AgentMessage.of("session1", "agent1", MessageType.TEXT_CHUNK, Map.of("content", "hello"));
        // Should not throw
        emitter.sendSessionEvent("session1", message);
    }

    @Test
    void sendSessionEvent_withSubscriberButNoEmitter_doesNotThrow() {
        emitter.subscribe("user1", "session1");
        // user1 has subscription but no emitter — should not throw
        AgentMessage message = AgentMessage.of("session1", "agent1", MessageType.TEXT_CHUNK, Map.of("content", "hello"));
        emitter.sendSessionEvent("session1", message);
    }

    @Test
    void sendSessionEvent_failedEmitter_cleansSubscriptions() {
        emitter.subscribe("user1", "session1");
        addFailingEmitter("user1");

        AgentMessage message = AgentMessage.of("session1", "agent1", MessageType.TEXT_CHUNK, Map.of("content", "hello"));
        emitter.sendSessionEvent("session1", message);

        assertFalse(emitter.hasActiveEmitters("user1"));
        assertTrue(emitter.getSubscriptions("user1").isEmpty());
        assertEquals(0, emitter.getTotalEmitterCount());
    }

    // ========== emitter lifecycle ==========

    @Test
    void createEmitter_returnsNonNull() {
        SseEmitter sse = emitter.createEmitter("user1");
        assertNotNull(sse);
        assertTrue(emitter.hasActiveEmitters("user1"));
    }

    @Test
    void hasActiveEmitters_noEmitters_returnsFalse() {
        assertFalse(emitter.hasActiveEmitters("nonexistent"));
    }

    @Test
    void getTotalEmitterCount_tracksEmitters() {
        emitter.createEmitter("user1");
        emitter.createEmitter("user2");
        assertEquals(2, emitter.getTotalEmitterCount());
    }

    @Test
    void heartbeat_failedEmitter_cleansSubscriptions() {
        emitter.subscribe("user1", "session1");
        addFailingEmitter("user1");

        ReflectionTestUtils.invokeMethod(emitter, "sendHeartbeats");

        assertFalse(emitter.hasActiveEmitters("user1"));
        assertTrue(emitter.getSubscriptions("user1").isEmpty());
        assertEquals(0, emitter.getTotalEmitterCount());
    }

    @Test
    void reconnectAfterCleanup_canSubscribeAgain() {
        emitter.subscribe("user1", "session1");
        addFailingEmitter("user1");

        AgentMessage message = AgentMessage.of("session1", "agent1", MessageType.TEXT_CHUNK, Map.of("content", "hello"));
        emitter.sendSessionEvent("session1", message);

        emitter.createEmitter("user1");
        emitter.subscribe("user1", "session1");

        assertTrue(emitter.hasActiveEmitters("user1"));
        assertTrue(emitter.getSubscriptions("user1").contains("session1"));
    }

    // ========== concurrent safety ==========

    @Test
    void concurrentSubscribeUnsubscribe_doesNotThrow() throws InterruptedException {
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                String userId = "user" + (idx % 3);
                String sessionId = "session" + idx;
                emitter.subscribe(userId, sessionId);
                emitter.unsubscribe(userId, sessionId);
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        // Should not throw and all subscriptions should be cleaned up
    }

    @SuppressWarnings("unchecked")
    private Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters() {
        return (Map<String, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(emitter, "userEmitters");
    }

    private void addFailingEmitter(String userId) {
        userEmitters().put(userId, new CopyOnWriteArrayList<>(List.of(new FailingSseEmitter())));
    }

    private static final class FailingSseEmitter extends SseEmitter {
        private FailingSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("simulated disconnect");
        }
    }
}
