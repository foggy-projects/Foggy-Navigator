package com.foggy.navigator.task.assistant.bridge;

import com.foggy.navigator.agent.framework.event.TaskCompletionEvent;
import com.foggy.navigator.agent.framework.event.TaskStartedEvent;
import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.task.assistant.spi.TaskAssistantFacade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件桥接：监听平台事件 → 忙碌感知队列 → 调用 TaskAssistantFacade.processEvents()
 *
 * 策略：
 * - 空闲时：5s debounce 后批量处理（等待更多事件到达）
 * - 忙碌时：事件留在缓冲区自然累积
 * - 完成后：检查缓冲区，有事件则立即处理（无额外 debounce）
 *
 * 防递归：syncQuery 模式天然不发布 TaskCompletionEvent，
 * 额外通过 targetAgentId == "task-assistant" 过滤助手自身任务。
 */
@Slf4j
@Component
public class TaskAssistantEventBridge {

    private final SessionManager sessionManager;
    @Nullable
    private final TaskAssistantFacade assistantFacade;

    /** userId → 待处理事件列表 */
    private final ConcurrentHashMap<String, List<Map<String, Object>>> pendingEvents = new ConcurrentHashMap<>();
    /** userId → 是否正在处理中 */
    private final ConcurrentHashMap<String, AtomicBoolean> sessionBusy = new ConcurrentHashMap<>();

    private static final long DEBOUNCE_MS = 5_000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "assistant-event-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "assistant-event-executor");
        t.setDaemon(true);
        return t;
    });

    public TaskAssistantEventBridge(SessionManager sessionManager,
                                     @Nullable TaskAssistantFacade assistantFacade) {
        this.sessionManager = sessionManager;
        this.assistantFacade = assistantFacade;
    }

    @Async("sessionEventExecutor")
    @EventListener
    public void onTaskCompletion(TaskCompletionEvent event) {
        // 防递归：跳过助手自身的任务完成事件
        if ("task-assistant".equals(event.getTargetAgentId())) {
            log.debug("Skipping task-assistant's own completion event: taskId={}", event.getTaskId());
            return;
        }

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
        // 防递归：跳过助手自身的任务开始事件
        if ("task-assistant".equals(event.getTargetAgentId())) {
            return;
        }

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

        pendingEvents.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(eventData);
        tryProcess(userId, true);
    }

    /**
     * 尝试处理用户的待处理事件
     * @param debounce true=首次触发时等待5s让更多事件到达，false=立即处理（回调场景）
     */
    private void tryProcess(String userId, boolean debounce) {
        AtomicBoolean busy = sessionBusy.computeIfAbsent(userId, k -> new AtomicBoolean(false));
        if (!busy.compareAndSet(false, true)) {
            // 忙碌中，事件留在缓冲区自然累积
            return;
        }

        if (debounce) {
            // 5s debounce — 等待更多事件到达
            scheduler.schedule(() -> executeForUser(userId), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        } else {
            // 完成回调 — 立即处理，无额外等待
            executor.submit(() -> executeForUser(userId));
        }
    }

    private void executeForUser(String userId) {
        List<Map<String, Object>> events = drainEvents(userId);
        if (events.isEmpty()) {
            sessionBusy.get(userId).set(false);
            return;
        }

        log.info("Processing {} events for task assistant, userId={}", events.size(), userId);

        try {
            assistantFacade.processEvents(userId, events);
        } catch (Exception e) {
            log.error("Failed to process events for task assistant, userId={}", userId, e);
        } finally {
            sessionBusy.get(userId).set(false);
            // 完成后检查是否有新事件累积
            List<Map<String, Object>> remaining = pendingEvents.get(userId);
            if (remaining != null && !remaining.isEmpty()) {
                tryProcess(userId, false);  // 立即处理，无额外 debounce
            }
        }
    }

    private List<Map<String, Object>> drainEvents(String userId) {
        List<Map<String, Object>> events = pendingEvents.remove(userId);
        return events != null ? new ArrayList<>(events) : List.of();
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
