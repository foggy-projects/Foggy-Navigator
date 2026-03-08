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

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/events")
@Slf4j
public class EventController {

    @Autowired
    private EventService eventService;

    @Autowired
    private SseEventEmitter sseEventEmitter;

    @GetMapping
    public ResponseEntity<List<Event>> getEvents(
            @PathVariable String conversationId,
            @RequestParam(required = false) Event.EventKind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestampGte,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestampLt,
            @RequestParam(required = false) String pageId,
            @RequestParam(defaultValue = "10") int limit) {
        log.info("GET /api/v1/conversations/{}/events - 查询事件: kind={}, limit={}", conversationId, kind, limit);
        List<Event> events = eventService.getEvents(conversationId, kind, timestampGte, timestampLt, pageId, limit);
        return ResponseEntity.ok(events);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String conversationId) {
        log.info("GET /api/v1/conversations/{}/events/stream - SSE 事件流", conversationId);
        return sseEventEmitter.createEmitter(conversationId);
    }

    @GetMapping("/new")
    public ResponseEntity<List<Event>> getNewEvents(
            @PathVariable String conversationId,
            @RequestParam(required = false) String lastEventId) {
        log.info("GET /api/v1/conversations/{}/events/new - 获取新事件: lastEventId={}", conversationId, lastEventId);
        List<Event> events = eventService.getEventsSince(conversationId, lastEventId);
        return ResponseEntity.ok(events);
    }
}
