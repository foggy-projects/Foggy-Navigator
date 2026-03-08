package com.foggy.navigator.task.assistant.bridge;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskAssistantEventBridgeTest {

    @Mock private SessionManager sessionManager;
    @Mock private TaskAssistantFacade assistantFacade;

    private TaskAssistantEventBridge bridge;

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-1";
    private static final String ASSISTANT_SESSION_ID = "assistant-session-1";

    @BeforeEach
    void setUp() {
        bridge = new TaskAssistantEventBridge(sessionManager, assistantFacade);
    }

    private TaskCompletionEvent completionEvent(String targetAgentId, String parentSessionId) {
        return TaskCompletionEvent.builder()
                .taskId("task-1")
                .externalTaskId("ext-1")
                .parentSessionId(parentSessionId)
                .targetAgentId(targetAgentId)
                .status("COMPLETED")
                .resultSummary("Done")
                .build();
    }

    private TaskStartedEvent startedEvent(String targetAgentId, String parentSessionId) {
        return TaskStartedEvent.builder()
                .taskId("task-1")
                .externalTaskId("ext-1")
                .parentSessionId(parentSessionId)
                .targetAgentId(targetAgentId)
                .prompt("Do something")
                .build();
    }

    // --- onTaskCompletion ---

    @Nested
    class OnTaskCompletion {

        @Test
        void skipsTaskAssistantOwnEvents() {
            bridge.onTaskCompletion(completionEvent("task-assistant", SESSION_ID));

            verifyNoInteractions(sessionManager, assistantFacade);
        }

        @Test
        void skipsWhenUserIdCannotBeResolved() {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

            bridge.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
        }

        @Test
        void skipsWhenParentSessionIdNull() {
            bridge.onTaskCompletion(completionEvent("claude-worker", null));

            verifyNoInteractions(assistantFacade);
        }

        @Test
        void skipsWhenAssistantSessionResolvesException() {
            when(sessionManager.getSession(SESSION_ID))
                    .thenThrow(new RuntimeException("DB error"));

            bridge.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
        }

        @Test
        void skipsEventsFromAssistantOwnSession() {
            when(sessionManager.getSession(ASSISTANT_SESSION_ID)).thenReturn(
                    Session.builder().id(ASSISTANT_SESSION_ID).userId(USER_ID).build());

            TaskAssistantConfig config = TaskAssistantConfig.builder()
                    .foggySessionId(ASSISTANT_SESSION_ID)
                    .build();
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.of(config));

            bridge.onTaskCompletion(completionEvent("claude-worker", ASSISTANT_SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
            verify(assistantFacade, never()).isAvailable(anyString());
        }

        @Test
        void skipsWhenAssistantNotAvailable() {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.empty());
            when(assistantFacade.isAvailable(USER_ID)).thenReturn(false);

            bridge.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
        }

        @Test
        void addsEventAndTriggersProcessing() throws Exception {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.empty());
            when(assistantFacade.isAvailable(USER_ID)).thenReturn(true);

            bridge.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));

            // Due to 5s debounce, verify facade is called after delay
            // We need to wait for the scheduler to fire
            verify(assistantFacade, timeout(7000)).processEvents(eq(USER_ID), argThat(events -> {
                assertThat(events).hasSize(1);
                Map<String, Object> event = events.get(0);
                assertThat(event.get("type")).isEqualTo("task_completed");
                assertThat(event.get("taskId")).isEqualTo("task-1");
                assertThat(event.get("status")).isEqualTo("COMPLETED");
                assertThat(event.get("agent")).isEqualTo("claude-worker");
                assertThat(event.get("summary")).isEqualTo("Done");
                assertThat(event.get("timestamp")).isNotNull();
                return true;
            }));
        }
    }

    // --- onTaskStarted ---

    @Nested
    class OnTaskStarted {

        @Test
        void skipsTaskAssistantOwnEvents() {
            bridge.onTaskStarted(startedEvent("task-assistant", SESSION_ID));

            verifyNoInteractions(sessionManager, assistantFacade);
        }

        @Test
        void skipsWhenUserIdCannotBeResolved() {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(null);

            bridge.onTaskStarted(startedEvent("claude-worker", SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
        }

        @Test
        void skipsEventsFromAssistantOwnSession() {
            when(sessionManager.getSession(ASSISTANT_SESSION_ID)).thenReturn(
                    Session.builder().id(ASSISTANT_SESSION_ID).userId(USER_ID).build());

            TaskAssistantConfig config = TaskAssistantConfig.builder()
                    .foggySessionId(ASSISTANT_SESSION_ID)
                    .build();
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.of(config));

            bridge.onTaskStarted(startedEvent("claude-worker", ASSISTANT_SESSION_ID));

            verify(assistantFacade, never()).processEvents(anyString(), anyList());
        }

        @Test
        void addsStartedEventAndTriggersProcessing() throws Exception {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.empty());
            when(assistantFacade.isAvailable(USER_ID)).thenReturn(true);

            bridge.onTaskStarted(startedEvent("claude-worker", SESSION_ID));

            verify(assistantFacade, timeout(7000)).processEvents(eq(USER_ID), argThat(events -> {
                assertThat(events).hasSize(1);
                Map<String, Object> event = events.get(0);
                assertThat(event.get("type")).isEqualTo("task_started");
                assertThat(event.get("taskId")).isEqualTo("ext-1");
                assertThat(event.get("agent")).isEqualTo("claude-worker");
                assertThat(event.get("prompt")).isEqualTo("Do something");
                return true;
            }));
        }
    }

    // --- Batching behavior ---

    @Nested
    class BatchingBehavior {

        @Test
        void batchesMultipleEventsIntoSingleProcessCall() throws Exception {
            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());
            when(assistantFacade.getConfig(USER_ID)).thenReturn(Optional.empty());
            when(assistantFacade.isAvailable(USER_ID)).thenReturn(true);

            // Fire multiple events rapidly
            bridge.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));
            bridge.onTaskStarted(startedEvent("coding-agent", SESSION_ID));

            // Both should be batched into one processEvents call
            verify(assistantFacade, timeout(7000)).processEvents(eq(USER_ID), argThat(events -> {
                assertThat(events).hasSizeGreaterThanOrEqualTo(2);
                return true;
            }));
        }
    }

    // --- Null facade ---

    @Nested
    class NullFacade {

        @Test
        void handlesNullFacadeGracefully() {
            TaskAssistantEventBridge bridgeNoFacade = new TaskAssistantEventBridge(sessionManager, null);

            when(sessionManager.getSession(SESSION_ID)).thenReturn(
                    Session.builder().id(SESSION_ID).userId(USER_ID).build());

            // Should not throw
            bridgeNoFacade.onTaskCompletion(completionEvent("claude-worker", SESSION_ID));
            bridgeNoFacade.onTaskStarted(startedEvent("claude-worker", SESSION_ID));
        }
    }
}
