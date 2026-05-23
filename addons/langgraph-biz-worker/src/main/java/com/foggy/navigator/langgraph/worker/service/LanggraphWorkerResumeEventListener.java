package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.common.event.WorkerGatewayResumeEvent;
import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Listens for {@link WorkerGatewayResumeEvent} published by the control plane after
 * a suspension resume decision and dispatches a conversation-state notification to
 * the appropriate Python Worker.
 *
 * <p>Business function side effects are not executed by this listener. Approved
 * suspension execution is owned by the Java BusinessFunctionSuspensionService via a
 * separate business execution decision event.
 *
 * <p>Stage 7B binding hardening:
 * <ul>
 *   <li>Session ID must match (unchanged from Stage 4C).</li>
 *   <li>If the event carries a {@code tenantId}, the task's {@code tenantId} must match.
 *       Missing task tenantId when event tenantId is present → fail closed.</li>
 * </ul>
 *
 * <p>Fields not present on {@link LanggraphTaskEntity} (clientAppId, functionId, inputHash)
 * cannot be validated here and are future hardening items.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanggraphWorkerResumeEventListener {

    private final LanggraphTaskRepository taskRepository;
    private final LanggraphWorkerService workerService;
    private final SessionEventListener sessionEventListener;

    @EventListener
    public void handleWorkerGatewayResumeEvent(WorkerGatewayResumeEvent event) {
        log.info("Received WorkerGatewayResumeEvent for taskId: {}, suspendId: {}", event.getTaskId(), event.getSuspendId());

        Optional<LanggraphTaskEntity> taskOpt = taskRepository.findByTaskId(event.getTaskId());
        if (taskOpt.isEmpty()) {
            log.warn("Task not found for taskId: {}, ignoring resume event", event.getTaskId());
            return;
        }

        LanggraphTaskEntity task = taskOpt.get();

        // --- Stage 7B: tenant binding validation ---
        String eventTenant = event.getTenantId();
        if (StringUtils.hasText(eventTenant)) {
            String taskTenant = task.getTenantId();
            if (!StringUtils.hasText(taskTenant)) {
                // Event carries tenantId but task record has no tenantId — fail closed
                log.error("Resume binding rejected for taskId={}: event tenantId={} present but task tenantId is absent (fail-closed)",
                        event.getTaskId(), eventTenant);
                return;
            }
            if (!eventTenant.equals(taskTenant)) {
                log.error("Resume binding rejected for taskId={}: tenantId mismatch. Event={}, task={}",
                        event.getTaskId(), eventTenant, taskTenant);
                return;
            }
        }
        // Note: if event tenantId is absent, we allow through (backward-compatible)
        // and log a warning so this can be promoted to fail-closed in a future stage.
        if (!StringUtils.hasText(event.getTenantId())) {
            log.debug("Resume event for taskId={} carries no tenantId — tenant validation skipped (future hardening item)",
                    event.getTaskId());
        }

        // --- Session validation (unchanged) ---
        if (!task.getSessionId().equals(event.getSessionId())) {
            log.error("Session ID mismatch for taskId: {}. Expected {}, got {}", event.getTaskId(), task.getSessionId(), event.getSessionId());
            return;
        }

        String workerId = task.getWorkerId();
        if (workerId == null || workerId.isBlank()) {
            log.warn("Worker ID is missing for taskId: {}, cannot dispatch resume", event.getTaskId());
            return;
        }
        String contextId = task.getContextId();
        if (!StringUtils.hasText(contextId)) {
            log.error("Resume binding rejected for taskId={}: task contextId is absent", event.getTaskId());
            return;
        }

        LanggraphWorkerEntity worker = workerService.getWorkerEntity(workerId);
        var client = workerService.createClient(worker);

        client.resumeTask(
                event.getTaskId(),
                event.getSessionId(),
                contextId,
                event.getApprovalResult(),
                event.getComment()
        )
                .doOnSuccess(resp -> {
                    log.info("Successfully dispatched worker conversation resume notification: taskId={}, workerId={}",
                            event.getTaskId(), workerId);
                    publishResumeMessageIfPresent(task, event, resp);
                })
                .doOnError(e -> {
                    log.error("Failed to dispatch worker conversation resume notification: taskId={}, workerId={}",
                            event.getTaskId(), workerId, e);
                    if (isWorkerResumeFrameNotFound(e)) {
                        log.warn("Worker has no awaiting approval frame for taskId={}, suspendId={}; business execution is handled by Java suspension service",
                                event.getTaskId(), event.getSuspendId());
                    }
                })
                .subscribe(ignored -> { }, e -> { });
    }

    @SuppressWarnings("unchecked")
    private void publishResumeMessageIfPresent(
            LanggraphTaskEntity task,
            WorkerGatewayResumeEvent event,
            Map<String, Object> response
    ) {
        if (response == null) {
            return;
        }
        Object rawMessage = response.get("resume_message");
        if (!(rawMessage instanceof Map<?, ?> rawMap)) {
            return;
        }
        Map<String, Object> resumeMessage = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                resumeMessage.put(String.valueOf(key), value);
            }
        });
        Object content = resumeMessage.get("content");
        if (!(content instanceof String contentText) || contentText.isBlank()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>(resumeMessage);
        payload.put("content", contentText);
        payload.put("taskId", event.getTaskId());
        payload.put("suspendId", event.getSuspendId());
        payload.put("approvalResult", event.getApprovalResult());
        payload.put("subtype", "post_approval_message");

        AgentMessage message = AgentMessage.of(
                task.getSessionId(),
                LanggraphTaskService.PROVIDER_TYPE,
                MessageType.TEXT_COMPLETE,
                payload
        );
        message.setTaskId(event.getTaskId());
        sessionEventListener.handleMessage(message);
    }

    private boolean isWorkerResumeFrameNotFound(Throwable error) {
        if (error instanceof WebClientResponseException webError) {
            return webError.getStatusCode().value() == 404;
        }
        Throwable cause = error.getCause();
        return cause != null && isWorkerResumeFrameNotFound(cause);
    }
}
