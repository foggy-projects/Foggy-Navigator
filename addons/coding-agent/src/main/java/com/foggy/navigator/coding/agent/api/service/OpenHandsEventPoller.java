package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import com.foggy.navigator.coding.agent.git.model.OpenHandsEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class OpenHandsEventPoller {

    @Autowired
    private OpenHandsClientFactory clientFactory;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private ConversationService conversationService;

    private final Map<String, ScheduledFuture<?>> activePollers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, r -> {
        Thread t = new Thread(r);
        t.setName("oh-poller-" + t.getId());
        t.setDaemon(true);
        return t;
    });

    private static final long POLL_INTERVAL_SECONDS = 2;
    private static final long MAX_POLL_DURATION_MINUTES = 10;

    @EventListener
    public void onPollingStart(OpenHandsPollingStartEvent startEvent) {
        startPolling(startEvent.getConversationId(), startEvent.getUserId(), startEvent.getOhConversationId());
    }

    @SuppressWarnings("unchecked")
    public void startPolling(String conversationId, String userId, String ohConversationId) {
        if (activePollers.containsKey(conversationId)) {
            log.debug("已存在轮询任务，跳过: conversationId={}", conversationId);
            return;
        }

        log.info("开始轮询 OpenHands 事件: conversationId={}, ohConversationId={}", conversationId, ohConversationId);

        AtomicInteger lastEventId = new AtomicInteger(0);

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                OpenHandsClient client = clientFactory.getClientForUser(userId);
                // getNewEvents returns List (raw) at runtime - items may be Map or OpenHandsEvent
                List<?> rawEvents = client.getNewEvents(ohConversationId, String.valueOf(lastEventId.get()));

                if (rawEvents == null || rawEvents.isEmpty()) {
                    return;
                }

                log.debug("收到 {} 个 OpenHands 事件: conversationId={}", rawEvents.size(), conversationId);

                for (Object rawEvent : rawEvents) {
                    Map<String, Object> eventMap = toEventMap(rawEvent);
                    if (eventMap == null) continue;

                    Event event = convertOhEvent(conversationId, eventMap);
                    if (event != null) {
                        eventPublisher.publishEvent(event);
                    }

                    int eventId = extractEventId(eventMap);
                    if (eventId > lastEventId.get()) {
                        lastEventId.set(eventId);
                    }

                    if (isTerminalEvent(eventMap)) {
                        log.info("检测到终止事件，停止轮询: conversationId={}", conversationId);
                        updateConversationStatus(conversationId, Conversation.ConversationStatus.IDLE);
                        stopPolling(conversationId);
                        return;
                    }
                }
            } catch (Exception e) {
                log.error("轮询 OpenHands 事件失败: conversationId={}, error={}", conversationId, e.getMessage());
            }
        }, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

        activePollers.put(conversationId, future);

        // Auto-stop after max duration
        scheduler.schedule(() -> {
            if (activePollers.containsKey(conversationId)) {
                log.warn("轮询超时，自动停止: conversationId={}", conversationId);
                stopPolling(conversationId);
            }
        }, MAX_POLL_DURATION_MINUTES, TimeUnit.MINUTES);
    }

    public void stopPolling(String conversationId) {
        ScheduledFuture<?> future = activePollers.remove(conversationId);
        if (future != null) {
            future.cancel(false);
            log.info("已停止轮询: conversationId={}", conversationId);
        }
    }

    public boolean isPolling(String conversationId) {
        return activePollers.containsKey(conversationId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("关闭所有轮询任务, count={}", activePollers.size());
        activePollers.forEach((id, future) -> future.cancel(false));
        activePollers.clear();
        scheduler.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toEventMap(Object rawEvent) {
        if (rawEvent instanceof Map) {
            return (Map<String, Object>) rawEvent;
        }
        if (rawEvent instanceof OpenHandsEvent ohEvent) {
            // Convert OpenHandsEvent to a map-like structure for unified processing
            Map<String, Object> map = new java.util.HashMap<>();
            if (ohEvent.getId() != null) map.put("id", ohEvent.getId());
            if (ohEvent.getKind() != null) map.put("type", ohEvent.getKind());
            if (ohEvent.getTimestamp() != null) map.put("timestamp", ohEvent.getTimestamp());
            if (ohEvent.getData() != null) map.putAll(ohEvent.getData());
            return map;
        }
        log.debug("未知的事件类型: {}", rawEvent.getClass().getName());
        return null;
    }

    Event convertOhEvent(String conversationId, Map<String, Object> ohEvent) {
        String type = getStringValue(ohEvent, "type");
        if (type == null) {
            type = getStringValue(ohEvent, "event_type");
        }
        if (type == null) {
            type = getStringValue(ohEvent, "kind");
        }

        if (type == null) {
            log.debug("OH 事件缺少 type 字段，跳过: {}", ohEvent);
            return null;
        }

        Event.EventKind kind = mapOhEventType(type);

        return Event.builder()
                .conversationId(conversationId)
                .kind(kind)
                .timestamp(LocalDateTime.now())
                .data(ohEvent)
                .build();
    }

    private Event.EventKind mapOhEventType(String ohType) {
        if (ohType == null) {
            return Event.EventKind.AGENT_ACTION;
        }

        String lower = ohType.toLowerCase();
        // Check error/fail first (before observation, since "ErrorObservation" contains both)
        if (lower.contains("error") || lower.contains("fail")) {
            return Event.EventKind.ERROR;
        }
        if (lower.contains("action") || lower.contains("command") || lower.contains("write") || lower.contains("browse")) {
            return Event.EventKind.AGENT_ACTION;
        }
        if (lower.contains("observation") || lower.contains("output") || lower.contains("result")) {
            return Event.EventKind.AGENT_OBSERVATION;
        }
        if (lower.contains("status") || lower.contains("state")) {
            return Event.EventKind.CONVERSATION_STATUS;
        }

        return Event.EventKind.AGENT_ACTION;
    }

    int extractEventId(Map<String, Object> ohEvent) {
        Object id = ohEvent.get("id");
        if (id == null) {
            id = ohEvent.get("event_id");
        }
        if (id instanceof Number) {
            return ((Number) id).intValue();
        }
        if (id instanceof String) {
            try {
                return Integer.parseInt((String) id);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    boolean isTerminalEvent(Map<String, Object> ohEvent) {
        String type = getStringValue(ohEvent, "type");
        if (type == null) {
            type = getStringValue(ohEvent, "event_type");
        }

        if (type == null) {
            return false;
        }

        String lower = type.toLowerCase();
        if (lower.contains("agent_state_changed")) {
            String state = getStringValue(ohEvent, "state");
            if (state == null) {
                Object extras = ohEvent.get("extras");
                if (extras instanceof Map) {
                    state = getStringValue((Map<String, Object>) extras, "agent_state");
                }
            }
            if (state != null) {
                String stateLower = state.toLowerCase();
                return stateLower.contains("finished") || stateLower.contains("stopped")
                        || stateLower.contains("error") || stateLower.contains("awaiting_user_input");
            }
        }

        return lower.contains("finish") || lower.contains("agent_finish");
    }

    private void updateConversationStatus(String conversationId, Conversation.ConversationStatus status) {
        try {
            Conversation conv = conversationService.getConversation(conversationId);
            if (conv != null) {
                conv.setStatus(status);
                conv.setUpdatedAt(LocalDateTime.now());
                eventPublisher.publishEvent(Event.builder()
                        .conversationId(conversationId)
                        .kind(Event.EventKind.CONVERSATION_STATUS)
                        .data(Map.of("status", status.name()))
                        .build());
            }
        } catch (Exception e) {
            log.error("更新会话状态失败: conversationId={}, error={}", conversationId, e.getMessage());
        }
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
