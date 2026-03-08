package com.foggy.navigator.session.sse;

import com.foggy.navigator.common.dto.a2a.A2aMessage;
import com.foggy.navigator.spi.notification.UserNotificationSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * UserNotificationSender SPI 实现，委托给 UnifiedSseEmitter
 */
@Component
@RequiredArgsConstructor
public class UserNotificationSenderImpl implements UserNotificationSender {

    private final UnifiedSseEmitter unifiedSseEmitter;

    @Override
    public void sendNotification(String userId, A2aMessage notification) {
        unifiedSseEmitter.sendNotification(userId, notification);
    }

    @Override
    public void sendTaskUpdate(String userId, Map<String, Object> update) {
        unifiedSseEmitter.sendTaskUpdate(userId, update);
    }

    @Override
    public boolean hasActiveConnection(String userId) {
        return unifiedSseEmitter.hasActiveEmitters(userId);
    }
}
