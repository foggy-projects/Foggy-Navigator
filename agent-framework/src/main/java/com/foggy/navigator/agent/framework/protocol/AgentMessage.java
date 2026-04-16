package com.foggy.navigator.agent.framework.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent消息（统一消息格式）
 * Agent与前端之间通信的基础消息结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {

    private String messageId;
    private String sessionId;
    private String agentId;
    /** 关联的平台任务 ID（可空） */
    private String taskId;
    private long timestamp;
    @Builder.Default
    private String version = "1.0";
    private MessageType type;
    private Object payload;

    public static AgentMessage of(String sessionId, String agentId, MessageType type, Object payload) {
        return AgentMessage.builder()
                .messageId(java.util.UUID.randomUUID().toString())
                .sessionId(sessionId)
                .agentId(agentId)
                .timestamp(System.currentTimeMillis())
                .type(type)
                .payload(payload)
                .build();
    }
}
