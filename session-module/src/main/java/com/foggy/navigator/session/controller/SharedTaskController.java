package com.foggy.navigator.session.controller;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.common.dto.a2a.A2aArtifact;
import com.foggy.navigator.common.dto.a2a.A2aTask;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.common.entity.SharingKeyEntity;
import com.foggy.navigator.common.entity.UserEntity;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.session.registry.UnifiedAgentResolver;
import com.foggy.navigator.session.service.ScopedSharedTaskTerminationCommandAdapter;
import com.foggy.navigator.session.service.SharingKeyService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggy.navigator.session.service.TaskDispatchFacade;
import com.foggy.navigator.spi.agent.A2aAgent;
import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Shared API 下的任务/会话查询端点，统一使用 X-Sharing-Key 鉴权。
 */
@RestController
@RequestMapping("/api/v1/shared")
@RequiredArgsConstructor
public class SharedTaskController {

    private static final Pattern SAFE_TERMINATION_ERROR_CODE =
            Pattern.compile("TERMINATION_[A-Z0-9_]{1,128}");

    private final SharingKeyService sharingKeyService;
    private final UnifiedAgentResolver agentResolver;
    private final TaskDispatchFacade taskDispatchFacade;
    private final SessionManager sessionManager;
    private final UserRepository userRepository;
    private final SessionTaskResourceAccessService resourceAccessService;
    private final ScopedSharedTaskTerminationCommandAdapter taskTerminationCommandAdapter;

    @GetMapping("/tasks/{taskId}")
    public RX<A2aTask> getTask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String taskId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "task:get");
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
            @PathVariable String taskId,
            @RequestHeader(value = "X-Navigator-Client-Request-Id", required = false)
            String clientRequestId) {
        CurrentUser ambientUser = UserContext.getCurrentUser();
        UserContext.clear();
        try {
            taskTerminationCommandAdapter.terminateTask(
                    sharingKey, taskId, clientRequestId);
            return RX.ok("Task cancelled");
        } catch (ScopedSharedTaskTerminationCommandAdapter
                .SharedTerminationAdmissionRejectedException e) {
            return RX.failA(e.getMessage());
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return RX.failA("TERMINATION_REQUEST_NOT_SUPPORTED");
        } catch (IllegalStateException e) {
            return RX.failB(safeTerminationErrorCode(e.getMessage()));
        } catch (org.springframework.dao.PessimisticLockingFailureException e) {
            return RX.failB(
                    "Failed to cancel task due to concurrent update, please retry");
        } finally {
            if (ambientUser == null) {
                UserContext.clear();
            } else {
                UserContext.setCurrentUser(ambientUser);
            }
        }
    }

    private String safeTerminationErrorCode(String message) {
        if (message != null && SAFE_TERMINATION_ERROR_CODE.matcher(message).matches()) {
            return message;
        }
        return "TERMINATION_REQUEST_FAILED";
    }

    @GetMapping("/sessions/{sessionId}")
    public RX<List<Message>> getSessionMessages(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String sessionId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "session:get");
            AgentResolveContext context = buildSharedContext(keyEntity);
            SessionEntity session = resourceAccessService.requireOwnedSession(
                    sessionId, context.getUserId(), context.getTenantId());
            if (!keyEntity.getAgentId().equals(session.getAgentId())) {
                return RX.failA("Session not found: " + sessionId);
            }
            return RX.ok(sessionManager.getAllMessages(sessionId));
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    /**
     * 回复权限请求 / 用户问题（外部系统通过 Sharing Key 回复 Agent 的确认请求）
     */
    @PostMapping("/tasks/{taskId}/respond")
    public RX<String> respondToTask(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String taskId,
            @RequestBody Map<String, Object> body) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "task:respond");
            Optional<DispatchTaskDTO> taskOpt = findAuthorizedTask(taskId, keyEntity);
            if (taskOpt.isEmpty()) {
                return RX.failA("Task not found: " + taskId);
            }

            taskDispatchFacade.respondToTask(taskId, buildSharedContext(keyEntity), body);
            return RX.ok("Response sent");
        } catch (UnsupportedOperationException e) {
            return RX.failA("Respond not supported for this agent: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        } catch (IllegalStateException e) {
            return RX.failB(e.getMessage());
        }
    }

    /**
     * 获取任务产物（A2A artifacts — Agent 生成的文本/代码等输出）
     */
    @GetMapping("/tasks/{taskId}/artifacts")
    public RX<List<A2aArtifact>> getArtifacts(
            @RequestHeader("X-Sharing-Key") String sharingKey,
            @PathVariable String taskId) {
        try {
            SharingKeyEntity keyEntity = sharingKeyService.validateForKeyOnly(sharingKey);
            sharingKeyService.checkOperation(keyEntity, "task:artifacts");
            Optional<DispatchTaskDTO> taskOpt = findAuthorizedTask(taskId, keyEntity);
            if (taskOpt.isEmpty()) {
                return RX.failA("Task not found: " + taskId);
            }

            A2aAgent agent = resolveSharedAgent(keyEntity);
            if (agent == null) {
                return RX.failA("Shared agent not available");
            }

            Optional<A2aTask> a2aTaskOpt = agent.getTask(taskId);
            if (a2aTaskOpt.isEmpty()) {
                return RX.failA("Task details not available");
            }

            List<A2aArtifact> artifacts = a2aTaskOpt.get().getArtifacts();
            return RX.ok(artifacts != null ? artifacts : List.of());
        } catch (IllegalArgumentException e) {
            return RX.failA(e.getMessage());
        }
    }

    private Optional<DispatchTaskDTO> findAuthorizedTask(String taskId, SharingKeyEntity keyEntity) {
        AgentResolveContext context = buildSharedContext(keyEntity);
        return taskDispatchFacade.getTask(taskId, context)
                .filter(task -> keyEntity.getAgentId().equals(task.getAgentId()));
    }

    private A2aAgent resolveSharedAgent(SharingKeyEntity keyEntity) {
        return agentResolver.resolveAgent(keyEntity.getAgentId(), buildSharedContext(keyEntity))
                .orElse(null);
    }

    private AgentResolveContext buildSharedContext(SharingKeyEntity keyEntity) {
        UserEntity owner = userRepository.findById(keyEntity.getOwnerUserId())
                .orElseThrow(() -> new SecurityException("shared resource is not accessible"));
        if (owner.getTenantId() == null || owner.getTenantId().isBlank()) {
            throw new SecurityException("shared resource is not accessible");
        }
        return AgentResolveContext.builder()
                .userId(keyEntity.getOwnerUserId())
                .tenantId(owner.getTenantId())
                .requestSource("SHARED_API")
                .build();
    }
}
