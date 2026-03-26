package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.claude.worker.service.ClaudeTaskService;
import com.foggy.navigator.claude.worker.service.ClaudeWorkerService;
import com.foggy.navigator.claude.worker.service.ConversationConfigService;
import com.foggy.navigator.claude.worker.service.WorkerStreamRelay;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaudeTaskControllerRewindTest {

    private static final String USER_ID = "user-rw-001";
    private static final String TENANT_ID = "tenant-rw-001";
    private static final String TASK_ID = "task-rw-001";

    private ClaudeTaskService taskService;
    private ClaudeTaskController controller;

    @BeforeEach
    void setUp() {
        taskService = mock(ClaudeTaskService.class);
        ClaudeWorkerService workerService = mock(ClaudeWorkerService.class);
        ConversationConfigService conversationConfigService = mock(ConversationConfigService.class);
        WorkerStreamRelay streamRelay = mock(WorkerStreamRelay.class);

        controller = new ClaudeTaskController(
                taskService,
                workerService,
                conversationConfigService,
                streamRelay
        );

        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void rewindToCheckpoint_returnsServicePayload() {
        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 2);
        Map<String, Object> payload = Map.of(
                "status", "rewound",
                "taskId", TASK_ID,
                "userPrompt", "Generate a file"
        );
        when(taskService.rewindTask(TASK_ID, USER_ID, body)).thenReturn(payload);

        RX<Map<String, Object>> rx = controller.rewindToCheckpoint(TASK_ID, body);

        assertEquals(payload, rx.getData());
    }

    @Test
    void rewindToCheckpoint_returnsFailPayloadOnValidationError() {
        Map<String, Object> body = Map.of("mode", "conversation_fork", "turnIndex", 2);
        when(taskService.rewindTask(TASK_ID, USER_ID, body))
                .thenThrow(new IllegalStateException("Cannot rewind a running task"));

        RX<Map<String, Object>> rx = controller.rewindToCheckpoint(TASK_ID, body);

        assertNull(rx.getData());
    }
}
