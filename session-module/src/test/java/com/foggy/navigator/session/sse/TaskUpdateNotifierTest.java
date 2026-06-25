package com.foggy.navigator.session.sse;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskUpdateNotifierTest {

    @Mock
    private UserNotificationSender notificationSender;

    @Mock
    private SessionManager sessionManager;

    private TaskUpdateNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new TaskUpdateNotifier(notificationSender, sessionManager);
    }

    @Test
    void onTaskStatusChange_pushesTaskUpdateWithInteractionState() {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .userId("user-1")
                .taskId("task-1")
                .sessionId("session-1")
                .status("AWAITING_PERMISSION")
                .previousStatus("RUNNING")
                .agentId("claude-worker")
                .errorMessage("need approval")
                .interactionState("AWAITING_REPLY")
                .build();

        notifier.onTaskStatusChange(event);

        Map<String, Object> update = captureTaskUpdate("user-1");
        assertEquals("task_status_change", update.get("type"));
        assertEquals("task-1", update.get("taskId"));
        assertEquals("session-1", update.get("sessionId"));
        assertEquals("AWAITING_PERMISSION", update.get("status"));
        assertEquals("RUNNING", update.get("previousStatus"));
        assertEquals("claude-worker", update.get("agent"));
        assertEquals("need approval", update.get("errorMessage"));
        assertEquals("AWAITING_REPLY", update.get("interactionState"));
        assertNotNull(update.get("timestamp"));
    }

    @Test
    void onTaskStatusChange_missingUserId_skipsPush() {
        TaskStatusChangeEvent event = TaskStatusChangeEvent.builder()
                .taskId("task-1")
                .sessionId("session-1")
                .status("RUNNING")
                .build();

        notifier.onTaskStatusChange(event);

        verify(notificationSender, never()).sendTaskUpdate(anyString(), anyMap());
    }

    @Test
    void onTaskCompletion_resolvesParentSessionUserAndPushesTaskUpdate() {
        when(sessionManager.getSession("session-1")).thenReturn(Session.builder()
                .id("session-1")
                .userId("user-1")
                .build());
        TaskCompletionEvent event = TaskCompletionEvent.builder()
                .taskId("task-1")
                .externalTaskId("provider-task-1")
                .parentSessionId("session-1")
                .targetAgentId("codex-worker")
                .status("COMPLETED")
                .resultSummary("done")
                .build();

        notifier.onTaskCompletion(event);

        Map<String, Object> update = captureTaskUpdate("user-1");
        assertEquals("task_completion", update.get("type"));
        assertEquals("task-1", update.get("taskId"));
        assertEquals("provider-task-1", update.get("externalTaskId"));
        assertEquals("COMPLETED", update.get("status"));
        assertEquals("codex-worker", update.get("agent"));
        assertEquals("done", update.get("summary"));
        assertNotNull(update.get("timestamp"));
    }

    @Test
    void onTaskCompletion_missingParentSession_skipsPush() {
        when(sessionManager.getSession("missing-session")).thenReturn(null);
        TaskCompletionEvent event = TaskCompletionEvent.builder()
                .taskId("task-1")
                .parentSessionId("missing-session")
                .status("FAILED")
                .build();

        notifier.onTaskCompletion(event);

        verify(notificationSender, never()).sendTaskUpdate(anyString(), anyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureTaskUpdate(String userId) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(notificationSender).sendTaskUpdate(eq(userId), captor.capture());
        return captor.getValue();
    }
}
