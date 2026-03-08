package com.foggy.navigator.agent.framework.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 消息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private String id;
    private String sessionId;
    private MessageRole role;
    private String content;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;

    public static Message user(String sessionId, String content) {
        return Message.builder()
                .id(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.USER)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Message assistant(String sessionId, String content) {
        return Message.builder()
                .id(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public static Message system(String sessionId, String content) {
        return Message.builder()
                .id(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .role(MessageRole.SYSTEM)
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
