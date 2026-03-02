package com.foggy.navigator.session.sse;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStatusChangeEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务状态更新推送器 — 监听 TaskCompletionEvent + TaskStatusChangeEvent，立即推送 task_update SSE
 * 从 TaskAssistantEventBridge 拆分出来的"实时面板更新"功能，属于平台基础设施
 */
@Slf4j
@Component
public class TaskUpdateNotifier {

    private final UserNotificationSender notificationSender;
    private final SessionManager sessionManager;

    public TaskUpdateNotifier(UserNotificationSender notificationSender,
                               SessionManager sessionManager) {
        this.notificationSender = notificationSender;
        this.sessionManager = sessionManager;
    }

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskCompletion(TaskCompletionEvent event) {
        String userId = resolveUserId(event.getParentSessionId());
        if (userId == null) {
            log.debug("Cannot resolve userId for parentSessionId={}", event.getParentSessionId());
            return;
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("type", "task_completion");
        eventData.put("taskId", event.getTaskId());
        eventData.put("externalTaskId", event.getExternalTaskId());
        eventData.put("status", event.getStatus());
        eventData.put("agent", event.getTargetAgentId());
        eventData.put("summary", event.getResultSummary());
        eventData.put("timestamp", Instant.now().toString());

        notificationSender.sendTaskUpdate(userId, eventData);
    }

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskStatusChange(TaskStatusChangeEvent event) {
        if (event.getUserId() == null) return;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "task_status_change");
        data.put("taskId", event.getTaskId());
        data.put("sessionId", event.getSessionId());
        data.put("status", event.getStatus());
        data.put("previousStatus", event.getPreviousStatus());
        data.put("agent", event.getAgentId());
        data.put("errorMessage", event.getErrorMessage());
        data.put("interactionState", event.getInteractionState());
        data.put("timestamp", Instant.now().toString());

        notificationSender.sendTaskUpdate(event.getUserId(), data);
    }

    private String resolveUserId(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        try {
            Session session = sessionManager.getSession(sessionId);
            return session != null ? session.getUserId() : null;
        } catch (Exception e) {
            log.debug("Failed to resolve userId from sessionId={}", sessionId, e);
            return null;
        }
    }
}
