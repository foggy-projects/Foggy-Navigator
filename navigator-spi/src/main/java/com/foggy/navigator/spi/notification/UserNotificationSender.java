package com.foggy.navigator.spi.notification;

import com.foggy.navigator.common.dto.a2a.A2aMessage;

import java.util.Map;

/**
 * 用户通知推送 SPI，解耦 addon 模块对 session-module 的直接依赖
 */
public interface UserNotificationSender {

    /** Push an assistant notification to user via SSE */
    void sendNotification(String userId, A2aMessage notification);

    /** Push a task update event to user via SSE */
    void sendTaskUpdate(String userId, Map<String, Object> update);

    /** Check if user has active SSE connections */
    boolean hasActiveConnection(String userId);
}
