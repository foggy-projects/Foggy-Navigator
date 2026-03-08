package com.foggy.navigator.coding.agent.api.service;

import com.foggy.navigator.coding.agent.api.model.Conversation;
import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.model.OpenHandsPollingStartEvent;
import com.foggy.navigator.coding.agent.git.OpenHandsClient;
import com.foggy.navigator.coding.agent.git.OpenHandsClientFactory;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

        // Use string-based page token for pagination (OH V1 uses UUID-based next_page_id)
        AtomicReference<String> nextPageId = new AtomicReference<>(null);
        // Track seen event IDs to avoid emitting duplicates.
        // When next_page_id is null (all events fit in one page), the server returns ALL events
        // on every poll. We use this set to skip already-processed events.
        Set<String> seenEventIds = new HashSet<>();

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                OpenHandsClient client = clientFactory.getClientForUser(userId);
                Map<String, Object> response = client.getNewEvents(ohConversationId, nextPageId.get());

                List<?> items = (List<?>) response.get("items");
                if (items == null || items.isEmpty()) {
                    return;
                }

                // Update page token for next request
                Object newPageId = response.get("next_page_id");
                if (newPageId != null) {
                    nextPageId.set(newPageId.toString());
                }

                int newCount = 0;
                for (Object rawItem : items) {
                    if (!(rawItem instanceof Map)) continue;
                    Map<String, Object> eventMap = (Map<String, Object>) rawItem;

                    // Deduplicate: skip events we've already processed
                    String eventId = extractEventId(eventMap);
                    if (eventId != null && !seenEventIds.add(eventId)) {
                        continue;
                    }

                    newCount++;
                    Event event = convertOhEvent(conversationId, eventMap);
                    if (event != null) {
                        eventPublisher.publishEvent(event);
                    }

                    if (isTerminalEvent(eventMap)) {
                        log.info("检测到终止事件，停止轮询: conversationId={}, kind={}",
                                conversationId, eventMap.get("kind"));
                        updateConversationStatus(conversationId, Conversation.ConversationStatus.IDLE);
                        stopPolling(conversationId);
                        return;
                    }
                }

                if (newCount > 0) {
                    log.info("收到 {} 个新 OpenHands 事件 (总计已处理 {}): conversationId={}",
                            newCount, seenEventIds.size(), conversationId);
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

    /**
     * Convert an OH agent server event to our internal Event model.
     * OH V1 events have a "kind" field like "SystemPromptEvent", "MessageEvent",
     * "ConversationStateUpdateEvent", "ConversationErrorEvent", "TerminalAction", etc.
     */
    Event convertOhEvent(String conversationId, Map<String, Object> ohEvent) {
        String kind = getStringValue(ohEvent, "kind");
        if (kind == null) {
            kind = getStringValue(ohEvent, "type");
        }
        if (kind == null) {
            log.debug("OH 事件缺少 kind 字段，跳过: keys={}", ohEvent.keySet());
            return null;
        }

        // Skip SystemPromptEvent (too large, not useful to relay)
        if (kind.equals("SystemPromptEvent")) {
            return null;
        }

        Event.EventKind eventKind = mapOhEventKind(kind);

        return Event.builder()
                .conversationId(conversationId)
                .kind(eventKind)
                .timestamp(LocalDateTime.now())
                .data(ohEvent)
                .build();
    }

    private Event.EventKind mapOhEventKind(String ohKind) {
        if (ohKind == null) {
            return Event.EventKind.AGENT_ACTION;
        }

        String lower = ohKind.toLowerCase();

        // Error events
        if (lower.contains("error")) {
            return Event.EventKind.ERROR;
        }
        // State/status updates
        if (lower.contains("state") || lower.contains("status")) {
            return Event.EventKind.CONVERSATION_STATUS;
        }
        // User messages
        if (lower.equals("messageevent")) {
            return Event.EventKind.MESSAGE_SENT;
        }
        // Observations (command output, file content, etc.)
        if (lower.contains("observation") || lower.contains("output") || lower.contains("result")) {
            return Event.EventKind.AGENT_OBSERVATION;
        }
        // Actions (terminal, file edit, browse, etc.)
        if (lower.contains("action") || lower.contains("command") || lower.contains("terminal")
                || lower.contains("write") || lower.contains("browse") || lower.contains("edit")) {
            return Event.EventKind.AGENT_ACTION;
        }

        return Event.EventKind.AGENT_ACTION;
    }

    @SuppressWarnings("unchecked")
    boolean isTerminalEvent(Map<String, Object> ohEvent) {
        String kind = getStringValue(ohEvent, "kind");
        if (kind == null) return false;

        // ConversationErrorEvent = agent error
        if (kind.equals("ConversationErrorEvent")) {
            return true;
        }

        // ConversationStateUpdateEvent with execution_status = error/completed
        if (kind.equals("ConversationStateUpdateEvent")) {
            String key = getStringValue(ohEvent, "key");
            String value = getStringValue(ohEvent, "value");
            if ("execution_status".equals(key) && value != null) {
                return value.equals("error") || value.equals("completed")
                        || value.equals("stopped") || value.equals("finished");
            }
        }

        // AgentStateChangedObservation with terminal states
        String lower = kind.toLowerCase();
        if (lower.contains("agent_state_changed") || lower.contains("agentstatechanged")) {
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

        return false;
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

    /**
     * Extract a unique identifier for an OH event.
     * OH V1 events have an "id" field (UUID string). Falls back to "event_id" or index-based key.
     */
    private String extractEventId(Map<String, Object> eventMap) {
        Object id = eventMap.get("id");
        if (id != null) {
            return id.toString();
        }
        Object eventId = eventMap.get("event_id");
        if (eventId != null) {
            return eventId.toString();
        }
        // Fallback: compose a key from kind + timestamp/content hash to deduplicate
        String kind = getStringValue(eventMap, "kind");
        Object timestamp = eventMap.get("timestamp");
        if (kind != null && timestamp != null) {
            return kind + ":" + timestamp;
        }
        return null;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}
