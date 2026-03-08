package com.foggy.navigator.monitoring.service;

import com.foggy.navigator.spi.notification.UserNotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Phase 1 alert rule engine — sends a real-time SSE alert for every ERROR log.
 * Uses UserNotificationSender.sendTaskUpdate() with type=monitoring_alert.
 */
@Slf4j
@Component
public class AlertRuleEngine {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final UserNotificationSender notificationSender;

    public AlertRuleEngine(@Nullable UserNotificationSender notificationSender) {
        this.notificationSender = notificationSender;
    }

    /**
     * Evaluate a monitoring event and fire alerts if rules match.
     */
    public void evaluate(Map<String, Object> envelope) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            if (payload == null) return;

            String level = (String) payload.get("level");
            if (!"ERROR".equalsIgnoreCase(level)) return;

            // ERROR log → immediate alert
            String service = (String) envelope.getOrDefault("service", "unknown");
            String message = (String) payload.getOrDefault("message", "(no message)");
            String logger = (String) payload.getOrDefault("logger", "");

            fireAlert(service, message, logger);
        } catch (Exception e) {
            log.debug("Alert evaluation failed: {}", e.getMessage());
        }
    }

    private void fireAlert(String service, String message, String logger) {
        if (notificationSender == null) {
            log.debug("No UserNotificationSender available, skipping alert");
            return;
        }

        Map<String, Object> alertMap = new HashMap<>();
        alertMap.put("type", "monitoring_alert");
        alertMap.put("service", service);
        alertMap.put("level", "ERROR");
        alertMap.put("message", message);
        alertMap.put("logger", logger);
        alertMap.put("time", LocalDateTime.now().format(FMT));

        try {
            // Push to all users with active connections (Phase 1: just "root")
            notificationSender.sendTaskUpdate("root", alertMap);
            log.debug("Monitoring alert sent: [{}] {}", service, truncate(message, 80));
        } catch (Exception e) {
            log.debug("Failed to send monitoring alert: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
