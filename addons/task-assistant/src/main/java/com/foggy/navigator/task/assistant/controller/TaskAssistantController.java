package com.foggy.navigator.task.assistant.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.task.assistant.spi.TaskAssistantConfig;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import com.foggyframework.core.ex.RX;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务助手 API (含 A2A Agent Card 端点)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/task-assistant")
@RequireAuth
@RequiredArgsConstructor
public class TaskAssistantController {

    private final TaskAssistantFacade assistantFacade;

    @GetMapping("/config")
    public RX<TaskAssistantConfig> getConfig() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(assistantFacade.getConfig(userId).orElse(null));
    }

    @PostMapping("/config")
    public RX<TaskAssistantConfig> createAssistant(@RequestBody CreateForm form) {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(assistantFacade.createOrUpdate(userId, form.getWorkerId(), form.getDirectoryPath()));
    }

    @PutMapping("/config")
    public RX<TaskAssistantConfig> updateConfig(@RequestBody UpdateForm form) {
        String userId = UserContext.getCurrentUserId();
        if (form.getEnabled() != null) {
            assistantFacade.setEnabled(userId, form.getEnabled());
        }
        return RX.ok(assistantFacade.getConfig(userId).orElse(null));
    }

    @DeleteMapping("/config")
    public RX<Void> deleteAssistant() {
        String userId = UserContext.getCurrentUserId();
        assistantFacade.delete(userId);
        return RX.ok(null);
    }

    @PostMapping("/test")
    public RX<A2aMessage> testNotification() {
        String userId = UserContext.getCurrentUserId();

        A2aMessage testEvents = A2aMessage.user(List.of(
                A2aPart.data(Map.of("events", List.of(
                        Map.of(
                                "type", "task_completed",
                                "taskId", "test-001",
                                "status", "SUCCESS",
                                "agent", "claude-worker",
                                "summary", "Test task completed successfully",
                                "timestamp", Instant.now().toString()
                        )
                )))
        ));

        Optional<A2aMessage> response = assistantFacade.sendEvents(userId, testEvents);
        return RX.ok(response.orElse(null));
    }

    @GetMapping("/agent-card")
    public A2aAgentCard getAgentCard() {
        return assistantFacade.getAgentCard();
    }

    @Data
    public static class CreateForm {
        private String workerId;
        private String directoryPath;
    }

    @Data
    public static class UpdateForm {
        private Boolean enabled;
    }
}
