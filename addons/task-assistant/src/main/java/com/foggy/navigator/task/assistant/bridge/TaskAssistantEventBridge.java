package com.foggy.navigator.task.assistant.bridge;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.common.dto.a2a.A2aPart;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 事件桥接：监听平台事件 → 批次聚合 → 调用 TaskAssistantFacade → 通过 SPI 推送通知
 *
 * 批次策略：5s 滑动窗口 + 15s 硬上限
 */
@Slf4j
@Component
public class TaskAssistantEventBridge {

    private final SessionManager sessionManager;
    private final UserNotificationSender notificationSender;
    @Nullable
    private final TaskAssistantFacade assistantFacade;

    /** userId → 待发送事件列表 */
    private final ConcurrentHashMap<String, List<Map<String, Object>>> pendingEvents = new ConcurrentHashMap<>();
    /** userId → 窗口首次事件时间 */
    private final ConcurrentHashMap<String, Instant> windowStart = new ConcurrentHashMap<>();
    /** userId → 上次事件时间 */
    private final ConcurrentHashMap<String, Instant> lastEventTime = new ConcurrentHashMap<>();
    /** userId → 是否已调度 flush */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> pendingFlush = new ConcurrentHashMap<>();

    private static final long DEBOUNCE_MS = 5_000;
    private static final long HARD_LIMIT_MS = 15_000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "assistant-event-bridge");
        t.setDaemon(true);
        return t;
    });

    public TaskAssistantEventBridge(SessionManager sessionManager,
                                     UserNotificationSender notificationSender,
                                     @Nullable TaskAssistantFacade assistantFacade) {
        this.sessionManager = sessionManager;
        this.notificationSender = notificationSender;
        this.assistantFacade = assistantFacade;
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
        eventData.put("type", "task_completed");
        eventData.put("taskId", event.getTaskId());
        eventData.put("externalTaskId", event.getExternalTaskId());
        eventData.put("status", event.getStatus());
        eventData.put("agent", event.getTargetAgentId());
        eventData.put("summary", event.getResultSummary());
        eventData.put("timestamp", Instant.now().toString());

        addEvent(userId, eventData);
    }

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskStarted(TaskStartedEvent event) {
        String userId = resolveUserId(event.getParentSessionId());
        if (userId == null) {
            log.debug("Cannot resolve userId for parentSessionId={}", event.getParentSessionId());
            return;
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("type", "task_started");
        eventData.put("taskId", event.getExternalTaskId());
        eventData.put("agent", event.getTargetAgentId());
        eventData.put("prompt", event.getPrompt());
        eventData.put("timestamp", Instant.now().toString());

        addEvent(userId, eventData);
    }

    private void addEvent(String userId, Map<String, Object> eventData) {
        if (assistantFacade == null || !assistantFacade.isAvailable(userId)) {
            return;
        }

        Instant now = Instant.now();
        pendingEvents.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(eventData);
        windowStart.putIfAbsent(userId, now);
        lastEventTime.put(userId, now);

        // 检查硬上限
        Instant start = windowStart.get(userId);
        if (start != null && now.toEpochMilli() - start.toEpochMilli() >= HARD_LIMIT_MS) {
            flushNow(userId);
            return;
        }

        // 取消已有的 debounce 调度，重新设置
        ScheduledFuture<?> existing = pendingFlush.get(userId);
        if (existing != null) {
            existing.cancel(false);
        }
        ScheduledFuture<?> future = scheduler.schedule(
                () -> flushNow(userId),
                DEBOUNCE_MS, TimeUnit.MILLISECONDS
        );
        pendingFlush.put(userId, future);
    }

    private void flushNow(String userId) {
        List<Map<String, Object>> events = pendingEvents.remove(userId);
        windowStart.remove(userId);
        lastEventTime.remove(userId);
        pendingFlush.remove(userId);

        if (events == null || events.isEmpty()) {
            return;
        }

        log.info("Flushing {} events to task assistant for userId={}", events.size(), userId);

        try {
            A2aMessage request = A2aMessage.user(List.of(
                    A2aPart.data(Map.of("events", events))
            ));

            Optional<A2aMessage> response = assistantFacade.sendEvents(userId, request);
            response.ifPresent(msg -> notificationSender.sendNotification(userId, msg));
        } catch (Exception e) {
            log.error("Failed to send events to task assistant for userId={}", userId, e);
        }
    }

    @Nullable
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
