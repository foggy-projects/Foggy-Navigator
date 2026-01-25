package com.foggy.navigator.agent.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Session {
    private String id;
    private String userId;
    private String tenantId;
    private String agentId;
    private String parentSessionId;
    private SessionStatus status;
    private String taskName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
