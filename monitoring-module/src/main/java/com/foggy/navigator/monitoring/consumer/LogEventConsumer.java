package com.foggy.navigator.monitoring.consumer;

import com.foggy.navigator.monitoring.service.AlertRuleEngine;
import com.foggy.navigator.monitoring.service.MonitorEventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes log events from the {@code foggy.monitor.logs} queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogEventConsumer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final MonitorEventService eventService;
    private final AlertRuleEngine alertRuleEngine;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "${foggy.monitoring.queue.logs:foggy.monitor.logs}")
    public void onLogEvent(String message) {
        try {
            Map<String, Object> envelope = objectMapper.readValue(message, MAP_TYPE);
            log.debug("Received monitor event: service={}, type={}",
                    envelope.get("service"), envelope.get("type"));

            // 1. Persist
            eventService.save(envelope);

            // 2. Evaluate alert rules
            alertRuleEngine.evaluate(envelope);
        } catch (Exception e) {
            log.warn("Failed to process monitor log event: {}", e.getMessage());
        }
    }
}
