package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.model.Event;
import com.foggy.navigator.coding.agent.api.service.EventService;
import com.foggy.navigator.coding.agent.api.sse.SseEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 全局事件查询 Controller
 * 提供不限于特定会话的事件查询接口
 */
@RestController
@RequestMapping("/api/v1/events")
@Slf4j
public class GlobalEventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private SseEventEmitter sseEventEmitter;

    /**
     * 查询事件列表（全局）
     * GET /api/v1/events?conversationId=xxx&type=xxx&limit=100
     */
    @GetMapping
    public ResponseEntity<List<Event>> listEvents(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) Event.EventKind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestampGte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestampLt,
            @RequestParam(required = false) String pageId,
            @RequestParam(defaultValue = "100") int limit) {

        log.info("GET /api/v1/events - 查询事件列表: conversationId={}, kind={}, limit={}",
                conversationId, kind, limit);

        // 如果提供了 conversationId，则查询该会话的事件
        if (conversationId != null && !conversationId.isEmpty()) {
            List<Event> events = eventService.getEvents(conversationId, kind, timestampGte, timestampLt, pageId, limit);
            return ResponseEntity.ok(events);
        }

        // 否则返回所有事件（限制数量）
        List<Event> events = eventService.getAllRecentEvents(limit);
        return ResponseEntity.ok(events);
    }

    /**
     * SSE 事件流（全局）
     * GET /api/v1/events/stream?conversationId=xxx
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@RequestParam(required = false) String conversationId) {
        log.info("GET /api/v1/events/stream - SSE 事件流: conversationId={}", conversationId);

        if (conversationId != null && !conversationId.isEmpty()) {
            return sseEventEmitter.createEmitter(conversationId);
        }

        // 如果没有指定 conversationId，创建全局事件流
        log.warn("未指定 conversationId，创建全局事件流（功能待实现）");
        return new SseEmitter();
    }
}
