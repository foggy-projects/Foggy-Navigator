package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.session.registry.DefaultA2aAgentRegistry;
import com.foggy.navigator.session.repository.SessionRepository;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Shared API 下的任务/会话查询端点，统一使用 X-Sharing-Key 鉴权。
 */
@RestController
@RequestMapping("/api/v1/shared")
@RequiredArgsConstructor
public class SharedTaskController {

    private final SharingKeyService sharingKeyService;
    private final DefaultA2aAgentRegistry registry;
    private final TaskDispatchFacade taskDispatchFacade;
    private final SessionRepository sessionRepository;
    private final SessionManager sessionManager;

    @GetMapping("/tasks/{taskId}")
    public RX<A2aTask> getTask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String taskId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            Optional<DispatchTaskDTO> taskOpt = findAuthorizedTask(taskId, keyEntity);
            if (taskOpt.isEmpty()) {
                return RX.failA("Task not found: " + taskId);
            }

            A2aAgent agent = resolveSharedAgent(keyEntity);
            if (agent == null) {
                return RX.failA("Shared agent not available");
            }

            return agent.getTask(taskId)
                    .map(RX::ok)
                    .orElseGet(() -> RX.failA("Task not found: " + taskId));
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public RX<String> cancelTask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String taskId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            Optional<DispatchTaskDTO> taskOpt = findAuthorizedTask(taskId, keyEntity);
            if (taskOpt.isEmpty()) {
                return RX.failA("Task not found: " + taskId);
            }

            AgentResolveContext context = AgentResolveContext.builder()
                    .userId(keyEntity.getOwnerUserId())
                    .requestSource("SHARED_API")
                    .build();
            taskDispatchFacade.cancelTask(taskId, taskOpt.get().getAgentId(), context);
            return RX.ok("Task cancelled");
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    @GetMapping("/sessions/{sessionId}")
    public RX<List<Message>> getSessionMessages(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String sessionId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            Optional<SessionEntity> sessionOpt = sessionRepository.findByIdAndUserId(sessionId, keyEntity.getOwnerUserId())
                    .filter(session -> keyEntity.getAgentId().equals(session.getAgentId()));
            if (sessionOpt.isEmpty()) {
                return RX.failA("Session not found: " + sessionId);
            }
            return RX.ok(sessionManager.getAllMessages(sessionId));
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    private Optional<DispatchTaskDTO> findAuthorizedTask(String taskId, SharingKeyEntity keyEntity) {
        AgentResolveContext context = AgentResolveContext.builder()
                .userId(keyEntity.getOwnerUserId())
                .requestSource("SHARED_API")
                .build();
        return taskDispatchFacade.getTask(taskId, context)
                .filter(task -> keyEntity.getAgentId().equals(task.getAgentId()));
    }

    private A2aAgent resolveSharedAgent(SharingKeyEntity keyEntity) {
        return registry.resolveAgent(keyEntity.getAgentId(), keyEntity.getOwnerUserId())
                .orElse(null);
    }
}
