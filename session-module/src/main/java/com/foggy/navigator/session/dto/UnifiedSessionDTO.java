package com.foggy.navigator.session.dto;

import com.foggy.navigator.agent.framework.session.Session;
import com.foggy.navigator.agent.framework.session.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 统一会话 DTO
 * 用于前端展示所有类型的会话（tutor-agent, coding-agent 等）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UnifiedSessionDTO {

    private String id;
    private String userId;
    private String tenantId;
    private String agentId;
    private String parentSessionId;
    private SessionStatus status;
    private String taskName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // === 扩展字段 ===

    // 会话类型：tutor, coding 等（用于前端路由和渲染）
    private String type;

    // coding-agent 特有字段
    private String conversationId;
    private String sandboxStatus;
    private String gitRepoUrl;

    /**
     * 从 Session 创建基础 DTO
     */
    public static UnifiedSessionDTO fromSession(Session session) {
        String type = "tutor";  // 默认类型
        if ("coding-agent".equals(session.getAgentId())) {
            type = "coding";
        }

        return UnifiedSessionDTO.builder()
                .id(session.getId())
                .userId(session.getUserId())
                .tenantId(session.getTenantId())
                .agentId(session.getAgentId())
                .parentSessionId(session.getParentSessionId())
                .status(session.getStatus())
                .taskName(session.getTaskName())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .type(type)
                .build();
    }
}
