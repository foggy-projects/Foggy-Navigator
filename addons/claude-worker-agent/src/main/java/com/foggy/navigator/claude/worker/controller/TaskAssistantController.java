package com.foggy.navigator.claude.worker.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.a2a.A2aAgentCard;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.spi.assistant.TaskAssistantConfig;
import com.foggy.navigator.spi.assistant.TaskAssistantFacade;
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

    @PutMapping("/config")
    public RX<TaskAssistantConfig> updateConfig(@RequestBody ConfigForm form) {
        String userId = UserContext.getCurrentUserId();

        TaskAssistantConfig config = assistantFacade.configure(userId, form.getWorkerId(), form.getDirectoryId());

        if (form.getEnabled() != null) {
            assistantFacade.setEnabled(userId, form.getEnabled());
            config.setEnabled(form.getEnabled());
        }

        return RX.ok(config);
    }

    @PostMapping("/test")
    public RX<A2aMessage> testNotification() {
        String userId = UserContext.getCurrentUserId();

        // 构造一个测试事件
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
    public static class ConfigForm {
        private String workerId;
        private String directoryId;
        private Boolean enabled;
    }
}
